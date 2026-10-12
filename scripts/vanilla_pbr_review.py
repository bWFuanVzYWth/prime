"""Read-only evidence for eligible Java textures rejected by the frozen matcher.

This never adds matches or changes PNG/registry data. Crop probes establish only
the reported patch, never a complete model/UV mapping.
"""
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
from zipfile import ZipFile

import numpy as np
from PIL import Image


LABELS = {
    "missing-texture-set": "源声明缺失：有基色/孤立 MERS，未声明 TextureSet",
    "new-java-only": "新版本 Java 独有纹理，固定 Bedrock 源无对应项",
    "missing-source": "固定 Bedrock 源缺对应的材质或图标",
    "atlas-repack": "共享 atlas / 拆分、重排、裁剪 UV",
    "color-tint": "颜色、染色层或图案结构差异",
    "debug-overlay": "调试、光级图标或破坏/UI 覆盖层",
    "low-confidence": "相近源存在，空间指标未达到可信阈值",
}


def family(identifier):
    name = identifier.split(":", 1)[1].split("/", 1)[1]
    if "_bed_" in name or name.startswith("bed_") or name.startswith("straw_bed"):
        return "床面"
    if name.endswith(("_sign", "_hanging_sign")):
        return "告示牌"
    if name.startswith("dried_ghast_hydration_"):
        return "干枯恶魂状态面"
    if name.endswith("_chain"):
        return "锁链"
    if name.startswith("conduit/") or name == "conduit":
        return "潮涌核心"
    if "map" in name:
        return "地图图标/染色层"
    if "sulfur_spike" in name:
        return "硫磺尖刺"
    if "poplar" in name:
        return "杨木"
    if name.endswith("_cushion"):
        return "坐垫"
    if name.startswith(("debug", "test_", "destroy_", "light_")) or name in ("light", "structure_void"):
        return "调试/覆盖图标"
    return name


def normalized(name):
    return re.sub("[^a-z0-9]", "", name.lower())


def logical_ids(identifier, sources):
    """Explicit family aliases are candidate evidence, not import authorization."""
    path = identifier.split(":", 1)[1]
    name = path.split("/")[-1]
    ids = []
    bed = re.match(r"(.+)_bed_(?:head|foot)_", name)
    if bed:
        color = "silver" if bed[1] == "light_gray" else bed[1]
        ids.append("textures/entity/bed/" + color)
    elif name in ("bed_down", "bed_head_north"):
        ids += ["textures/entity/bed/white", "textures/blocks/bed_head_end"]
    if name.endswith("_hanging_sign"):
        ids.append("textures/entity/" + name)
    elif name.endswith("_sign"):
        wood = name.removesuffix("_sign")
        aliases = {"oak": "sign", "dark_oak": "sign_darkoak", "pale_oak": "pale_oak_sign", "bamboo": "bamboo_sign", "cherry": "cherry_sign", "mangrove": "mangrove_sign"}
        ids.append("textures/entity/" + aliases.get(wood, "sign_" + wood))
    dried = re.match(r"dried_ghast_hydration_(\d+)_(.+)", name)
    if dried:
        # Pinned resource_pack/blocks.json: north=back, south=front, east=left, west=right.
        face = {"north": "back", "south": "front", "east": "left", "west": "right", "top": "top", "bottom": "bottom"}[dried[2]]
        ids.append("textures/blocks/dried_ghast_state_" + str(int(dried[1]) + 1) + "_" + face)
    if name.endswith("_chain"):
        prefix = "" if name == "iron_chain" else name.removesuffix("_chain") + "_"
        ids += ["textures/blocks/" + prefix + "chain1", "textures/blocks/" + prefix + "chain2"]
    conduit = {"base": "conduit_base", "break_particle": "conduit_base", "cage": "conduit_cage", "closed_eye": "conduit_closed", "open_eye": "conduit_open", "conduit": "conduit_base"}
    if name in conduit:
        ids.append("textures/blocks/" + conduit[name])
    aliases = {
        "bamboo_fence_gate_particle": ["blocks/bamboo_fence_gate"], "bamboo_fence_particle": ["blocks/bamboo_fence"],
        "bamboo_small_leaves": ["blocks/bamboo_small_leaf", "blocks/bamboo_singleleaf"],
        "redstone_torch": ["blocks/redstone_torch_on"], "redstone_dust_line0": ["blocks/redstone_dust_line", "blocks/redstone_dust_cross"],
        "potted_azalea_bush_side": ["blocks/potted_azalea_bush_side"],
        "potted_flowering_azalea_bush_side": ["blocks/potted_flowering_azalea_bush_side"],
        "spyglass_model": ["entity/spyglass"], "firework_star_overlay": ["items/fireworks_charge"],
        "filled_map_markings": ["items/map_filled"], "filled_map": ["items/map_filled"], "map": ["items/map_empty"],
        "pointed_dripstone": ["items/pointed_dripstone"], "water_overlay": ["blocks/water_still_grey"],
    }
    ids += ["textures/" + value for value in aliases.get(name, [])]
    names = {normalized(name), normalized(name.replace("_on", ""))}
    if name not in ("base", "cage", "open_eye", "closed_eye", "break_particle"):
        ids += [s["id"] for s in sources if normalized(s["id"].split("/")[-1]) in names]
    if name == "kelp":
        ids += [s["id"] for s in sources if re.match(r"textures/blocks/kelp_[a-d]$", s["id"])]
    if "map" in name:
        ids += [s["id"] for s in sources if s["id"].startswith("textures/items/map_")]
    return sorted(set(ids))


class Models:
    def __init__(self, jar):
        self.data = {n.removeprefix("assets/minecraft/models/").removesuffix(".json"): json.loads(jar.read(n))
                     for n in jar.namelist() if n.startswith("assets/minecraft/models/") and n.endswith(".json")}
        self.cache = {}
        self.uses = defaultdict(list)
        self.definitions = defaultdict(list)
        def definitions(value, path):
            if isinstance(value,dict):
                if isinstance(value.get("model"),str):
                    self.definitions[value["model"].removeprefix("minecraft:")].append({"path":path,"tints":value.get("tints",[])})
                for child in value.values():definitions(child,path)
            elif isinstance(value,list):
                for child in value:definitions(child,path)
        for path in sorted(n for n in jar.namelist() if n.startswith("assets/minecraft/items/") and n.endswith(".json")):
            definitions(json.loads(jar.read(path)),path)
        for name in sorted(self.data):
            value = self.resolve(name)
            textures = value.get("textures", {})
            def texture(ref):
                visited = set()
                if isinstance(ref, dict):
                    ref = ref.get("sprite", "")
                while ref.startswith("#") and ref not in visited:
                    visited.add(ref)
                    ref = textures.get(ref[1:], ref)
                    if isinstance(ref, dict):
                        ref = ref.get("sprite", "")
                return ref if ":" in ref else "minecraft:" + ref
            for slot, ref in textures.items():
                identifier = texture(ref)
                if not identifier.startswith("minecraft:#"):
                    self.uses[identifier].append({"model": name, "slot": slot, "uv": None})
            for element in value.get("elements", []):
                for side, face in element.get("faces", {}).items():
                    identifier = texture(face["texture"])
                    self.uses[identifier].append({"model": name, "slot": side, "uv": face.get("uv"),
                                                  "rotation": face.get("rotation", 0), "tintindex": face.get("tintindex")})

    def resolve(self, name, depth=0):
        if name in self.cache:
            return self.cache[name]
        value = self.data.get(name, {})
        result = dict(self.resolve(value["parent"].removeprefix("minecraft:"), depth + 1)) if "parent" in value and depth < 32 else {}
        result["textures"] = result.get("textures", {}) | value.get("textures", {})
        if "elements" in value:
            result["elements"] = value["elements"]
        self.cache[name] = result
        return result

    def evidence(self, identifier):
        uses = self.uses.get(identifier, [])
        models = sorted({u["model"] for u in uses})
        regions = sorted({tuple(u["uv"]) for u in uses if u.get("uv") is not None})
        return {"model_count": len(models), "model_examples": models[:4], "uv_regions": [list(v) for v in regions],
                "texture_roles": sorted({u["slot"] for u in uses}),
                "tint_indices": sorted({u["tintindex"] for u in uses if u.get("tintindex") is not None}),
                "item_definitions": [d for name in models for d in self.definitions[name]][:4]}


TRANSFORMS = [("identity", None), ("flip-x", Image.Transpose.FLIP_LEFT_RIGHT), ("flip-y", Image.Transpose.FLIP_TOP_BOTTOM),
              ("rotate90", Image.Transpose.ROTATE_90), ("rotate180", Image.Transpose.ROTATE_180), ("rotate270", Image.Transpose.ROTATE_270)]


def crop_proof(image, source, uv_regions):
    regions = [[0, 0, image.width, image.height]]
    for uv in uv_regions:
        regions.append([round(min(uv[0], uv[2]) * image.width / 16), round(min(uv[1], uv[3]) * image.height / 16),
                        round(max(uv[0], uv[2]) * image.width / 16), round(max(uv[1], uv[3]) * image.height / 16)])
    regions = sorted(set(tuple(r) for r in regions), key=lambda r: (-(r[2]-r[0])*(r[3]-r[1]), r))
    for rectangle in regions[:12]:
        patch = image.crop(rectangle)
        if patch.width * patch.height < 16 or patch.width == 0 or patch.height == 0:
            continue
        values = np.asarray(patch.convert("RGB")); visible = np.asarray(patch.getchannel("A")) > 12
        ys, xs = np.nonzero(visible)
        if len(ys) < 8 or len(np.unique(values[visible], axis=0)) < 4:
            continue
        for transform, operation in TRANSFORMS:
            source_image = source if operation is None else source.transpose(operation)
            data = np.asarray(source_image.convert("RGB")); height = data.shape[0]-patch.height+1; width = data.shape[1]-patch.width+1
            if height <= 0 or width <= 0:
                continue
            starts = np.ones((height, width), dtype=bool)
            probes = np.linspace(0, len(ys)-1, min(12, len(ys)), dtype=int)
            for index in probes:
                y, x = ys[index], xs[index]
                starts &= np.all(data[y:y+height, x:x+width] == values[y, x], axis=2)
                if not starts.any():
                    break
            for y, x in zip(*np.nonzero(starts)):
                window = data[y:y+patch.height, x:x+patch.width]
                if np.array_equal(window[visible], values[visible]):
                    total_visible = int((np.asarray(image.getchannel("A")) > 12).sum())
                    return {"target_pixel_rect": list(rectangle), "source_transformed_pixel_rect": [int(x), int(y), int(x+patch.width), int(y+patch.height)],
                            "transform": transform, "visible_pixels": int(visible.sum()), "rgb_rmse": 0,
                            "visible_pixel_coverage": round(int(visible.sum())/max(1,total_visible), 6),
                            "scope": "complete visible texture" if rectangle == (0, 0, image.width, image.height) else "one actual model UV patch; remaining UV regions unproved"}
    return None


def failed_gates(importer, quality, name_score, fraction, same_aspect):
    if not same_aspect:
        return ["aspect/layout mismatch"]
    if fraction < .10 and name_score < .4:
        return ["sparse visibility needs logical-name support"]
    if quality["min_std"] < .008:
        gate = importer.SETTINGS["flat"]
        return ["flat " + k for k in ("rmse", "centered") if quality[k] > gate[k]] + (["flat logical name support"] if name_score < .4 else [])
    failures = []
    for mode in ("near", "recolor"):
        gate = importer.SETTINGS[mode]
        failures += [mode + " " + k for k in ("rmse", "mae", "centered") if quality[k] > gate[k]]
        failures += [mode + " " + k for k in ("corr", "edge") if quality[k] < gate[k]]
    return failures


def diagnose(importer, target, sources, frames, features, aspects, models, raw_paths, present_in_other):
    first = target["_first"]; tf = importer.feature(first)
    mask = np.asarray(first.getchannel("A").resize((16, 16), Image.Resampling.NEAREST)).ravel() > 12
    if not mask.any():
        mask[:] = True
    related_ids = logical_ids(target["java_id"], sources)
    by_id = {s["id"]: s for s in sources}
    related = [by_id[i] for i in related_ids if i in by_id and "_color" in by_id[i]]
    same_aspect = np.abs(aspects-first.width/first.height) < 1e-6
    mse = np.mean((features[:, mask]-tf[mask])**2, axis=(1, 2))
    closest = np.argsort(np.where(same_aspect, mse, np.inf))[:3]
    candidates = []
    for index in closest:
        f = frames[index]; score = importer.support(target["java_id"], f["source"]["id"])
        quality = importer.metric(tf, f["feature"], mask)
        candidates.append({"source": f["source"]["id"], "frame": f["index"], "name_support": round(score, 6),
                           "metrics": {k: round(v, 6) for k, v in quality.items()},
                           "rejected_by": failed_gates(importer, quality, score, target["visible_fraction"], True)})
    model = models.evidence(target["java_id"])
    logical = []
    for source in related[:12]:
        qualities = [(i, importer.metric(tf, importer.feature(source["_color"].crop(rect)), mask)) for i, rect in enumerate(source["_rects"])]
        index, quality = min(qualities, key=lambda value: (value[1]["rmse"], value[0]))
        aspect = source["frame_extent"][0]/source["frame_extent"][1] == first.width/first.height
        proof = crop_proof(first, source["_color"], model["uv_regions"]) if max(source["extent"]) <= 128 and source["frame_count"] == 1 else None
        logical.append({"source": source["id"], "source_extent": source["extent"], "source_frames": source["frame_count"], "best_frame": index,
                        "texture_set_path": source["texture_set_path"], "color_path": source["color_path"], "mers_path": source["mers_path"],
                        "same_aspect": aspect, "metrics": {k: round(v, 6) for k, v in quality.items()}, "crop_proof": proof,
                        "rejected_by": failed_gates(importer, quality, importer.support(target["java_id"], source["id"]), target["visible_fraction"], aspect)})
    name = target["java_id"].split("/")[-1]
    exact_raw = [p for p in raw_paths if normalized(Path(p).stem.removesuffix("_mers")) == normalized(name)]
    color_raw = [p for p in exact_raw if not Path(p).stem.endswith("_mers")]
    mers_raw = [p for p in exact_raw if Path(p).stem.endswith("_mers")]
    raw_evidence = []
    for color_path in color_raw:
        path = Path(color_path)
        matching = [p for p in mers_raw if Path(p).parent == path.parent]
        if not matching:
            continue
        with Image.open(raw_paths[color_path]) as color:
            color.load(); color = color.convert("RGBA").convert("RGB")
            expected = first.convert("RGB")
            same_size = color.size == expected.size
            equal = same_size and color.tobytes() == expected.tobytes()
            visible = np.asarray(first.getchannel("A")) > 12
            visible_equal = same_size and np.array_equal(np.asarray(color)[visible],np.asarray(expected)[visible])
            difference = np.any(np.asarray(color) != np.asarray(expected), axis=2) if same_size else None
        with Image.open(raw_paths[matching[0]]) as layer:
            layer.load()
            raw_evidence.append({"color_path": color_path, "mers_path": matching[0], "rgb_exact": equal,"visible_rgb_exact":visible_equal,
                                 "mers_mode": layer.mode, "mers_extent": list(layer.size),
                                 "rgb_different_pixels": int(difference.sum()) if difference is not None else None,
                                 "visible_rgb_different_pixels": int((difference & visible).sum()) if difference is not None else None,
                                 "rgb_different_pixel_examples": [[int(x), int(y)] for y, x in zip(*np.nonzero(difference))][:8] if difference is not None else [],
                                 "declaration_present": raw_paths[color_path].with_suffix(".texture_set.json").is_file(),
                                 "encoding_note": "_mers naming matches sample convention; an explicit import declaration is still required"})
    layout_family = family(target["java_id"]) in {"床面", "告示牌", "锁链", "潮涌核心", "干枯恶魂状态面"}
    debug = name.startswith(("debug", "test_", "destroy_")) or re.fullmatch(r"light_\d{2}", name) is not None or name in ("light", "structure_void")
    if debug:
        category = "debug-overlay"
        reason = "实际资源是调试/光级符号或破坏覆盖图；没有可信整图 PBR 对应，未把符号当普通表面材质。"
        option = "优先保留默认材质；若需物品图标 PBR，应单独指定艺术参数/源，不借用无关最近颜色。"
    elif color_raw and not related:
        category = "missing-texture-set"
        reason = "固定源存在同名基色" + ("及 _mers 文件" if mers_raw else "，未发现同名 MERS") + "，但没有可用 TextureSet 声明进入导入库存。"
        option = "先核验孤立通道文件的编码并补显式来源声明；没有 MERS 时需手绘或选择近似材料。"
    elif not present_in_other and not related:
        category = "new-java-only"
        reason = "此 ID 不存在于另一固定 Java JAR，Bedrock TextureSet 库及同名原始文件也没有对应项。"
        option = "更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。"
    elif any(v["crop_proof"] and v["crop_proof"]["visible_pixel_coverage"] == 1 and v["source_extent"] == list(first.size)
             and v["crop_proof"]["transform"] == "identity"
             and v["crop_proof"]["source_transformed_pixel_rect"] == v["crop_proof"]["target_pixel_rect"] for v in logical):
        category = "color-tint"
        reason = "全部可见像素 RGB 在原坐标一致；隐藏 RGB/小尺寸 feature 过滤或稀疏像素的名字门限导致拒绝。"
        option = "可在真实材质身份/染色层核验后单独批准 alias/forced-source；没有必要为了此例放宽所有稀疏纹理。"
    elif related and (layout_family or any(v["crop_proof"] for v in logical)):
        category = "atlas-repack"
        reason = "相关源材质存在，源/目标采用不同 atlas、裁切、状态命名或排列。"
        reason += " Java 模型 UV 已记录。" if model["model_count"] else " JSON 模型没有此实体 UV，需另核验特殊渲染器布局。"
        if any(v["crop_proof"] for v in logical):
            reason += " 已证明表中具体 RGB 裁切区域相同，尚未证明其余 UV 的完整重排。"
        option = "按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。"
    elif related:
        structural = any(v["same_aspect"] and v["metrics"]["corr"] >= .85 and v["metrics"]["edge"] >= .70 for v in logical)
        category = "color-tint" if structural or model["tint_indices"] or name in {"filled_map_markings", "firework_star_overlay"} else "low-confidence"
        reason = "同名/别名候选存在，但完整像素或可见空间结构未通过现有门限；详列失败指标，不能视作同 UV 材质。"
        option = "可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。"
    else:
        category = "missing-source"
        reason = "没有同名/已知别名的已声明 PBR 源，最近空间候选也未通过门限。不能把弱最近邻当对应源。"
        option = "寻找新的官方源，或由用户选择手绘/同类材质近似；继续保留默认材质也可。"
    if name == "water_overlay":
        option = "水面已主动忽略原版 PBR；此覆盖层继续保留宿主默认，除非用户以后改变水面艺术选择。"
    return {"java_id": target["java_id"], "version": str(target["game_version"]), "extent": target["extent"], "frame_extent": target["frame_extent"],
            "animation": target["animation"], "visible_fraction": target["visible_fraction"], "family": family(target["java_id"]),
            "category": category, "reason": reason, "new_in_this_version": not present_in_other,
            "model_evidence": model, "raw_same_name_colors": color_raw, "raw_same_name_mers": mers_raw,
            "raw_pair_evidence": raw_evidence,
            "logical_candidates": logical, "nearest_rejected_candidates": candidates, "possible_action": option,
            "needs_user_decision": "是否投入专用 UV 重排，或接受指定材料的艺术近似；本报告不改变任何匹配。"}


def write_review(importer, source, java_jars, output):
    report = importer.read_json(output / "matching-report.json")
    policy = report["policy"]
    sources, frames = importer.sources_inventory(source, policy)
    targets = {v: importer.java_inventory(path, v, policy["enabled_atlases"]) for v, path in java_jars.items()}
    status = {v: {t["java_id"]: t["status"] for t in rows} for v, rows in report["java"].items()}
    raw_root = source / "resource_pack"
    raw_paths = {p.relative_to(raw_root).as_posix(): p for p in sorted((raw_root / "textures").rglob("*"))
                 if p.is_file() and p.suffix.lower() in {".png", ".tga", ".jpg", ".jpeg"} and "_normal" not in p.stem}
    entries = []
    features = np.stack([f["feature"] for f in frames])
    aspects = np.array([f["aspect"] for f in frames])
    for version, rows in targets.items():
        other = next(v for v in targets if v != version)
        other_ids = {t["java_id"] for t in targets[other]}
        with ZipFile(java_jars[version]) as jar:
            models = Models(jar)
            for target in rows:
                if status[version][target["java_id"]] == "unmatched":
                    entries.append(diagnose(importer, target, sources, frames, features, aspects, models, raw_paths, target["java_id"] in other_ids))
    counts = {v: Counter(e["category"] for e in entries if e["version"] == v.replace(".", "")) for v in targets}
    value = {"schema": 1, "scope": "Every eligible unmatched literal PNG; diagnosis only, no added matches", "source_commit": importer.SOURCE_COMMIT,
             "matching_report_sha256": importer.sha256((output / "matching-report.json").read_bytes()), "category_labels": LABELS,
             "counts": {v: dict(sorted(c.items())) for v, c in counts.items()}, "entries": entries,
             "limitations": "Logical aliases are investigation candidates only. Exact crop evidence proves that patch, not the rest of the UV layout. UV evidence covers explicit JSON model regions, not implicit UV derivation or special-renderer UVs. Nearest rejected candidates are diagnostic and never imported."}
    blocks = importer.read_json(source / "resource_pack/blocks.json")
    value["source_binding_evidence"] = {
        "path":"resource_pack/blocks.json", "sha256":importer.sha256((source/"resource_pack/blocks.json").read_bytes()),
        "dried_ghast":blocks["dried_ghast"],
        "chains":{key:blocks[key] for key in ("chain","copper_chain","exposed_copper_chain","weathered_copper_chain","oxidized_copper_chain")},
    }
    (output / "unmatched-review.json").write_bytes(importer.json_bytes(value))
    lines = ["# 未匹配原版 PBR：逐项证据与可选补法", "", "只分析现有 eligible 未匹配项，不新增匹配。名字仅用于寻找调查候选；裁切证明只覆盖明确列出的区域，不代表整张材质或模型已兼容。完整 UV、指标、原始文件和逐版本证据见 [unmatched-review.json](unmatched-review.json)。", "",
             "| 原因 | 26.2 | 26.3 |", "| --- | ---: | ---: |"]
    for key, label in LABELS.items():
        lines.append("| " + label + " | " + str(counts["26.2"][key]) + " | " + str(counts["26.3"][key]) + " |")
    lines += ["", "水面两项属于用户主动艺术排除，列在 matching-report/unintegrated 中，不计入本表的原有239/297缺口。", ""]
    sulfur_counts = {v: sum(e["raw_pair_evidence"][0]["rgb_exact"] for e in entries if e["version"] == v.replace(".", "") and e["category"] == "missing-texture-set" and e["family"] == "硫磺尖刺" and e["raw_pair_evidence"]) for v in targets}
    lines += ["可选推进顺序：先审查有整图/可见区精确 crop 的床顶和盆栽侧面，再做木牌/床侧的完整 UV 重排。硫磺尖刺有10对孤立 RGB24 MER文件（需明确 MER+S0规则和来源声明）；整图RGB一致数量为26.2 " + str(sulfur_counts["26.2"]) + "/10、26.3 " + str(sulfur_counts["26.3"]) + "/10。26.3的up_tip_merge仅在可见像素(6,0)有一处RGB差异，alpha一致。调试/覆盖图和仅particle角色无需为了数字覆盖强配；新杨木/坐垫等需要新来源或用户接受材质代理。", "",
              "地图/烟花图标还要保留真实染色层；烟花星覆盖层与 Bedrock fireworks_charge 的可见 RGB 为0误差，却因可见覆盖不足10%且别名不同被保守拒绝。红石火把轮廓相关性高，但RGB亮暗变化超过现有门限。它们可单独批准艺术映射，不能据此放宽所有纹理的门限。", ""]
    lines += ["源绑定依据：固定 resource_pack/blocks.json 明确将干枯恶魂 north/south/east/west 对应 back/front/left/right；hydration0..3对应state1..4。锁链原版同时引用chain1/chain2，而Java模板将两组面装进同一纹理的0..3与3..6列；需验证对应重排，并非可直接复用整图。潮涌核心JSON模型没有实体UV，除已验证的整图可见RGB外仍需查特殊渲染器布局。", ""]
    for category, label in LABELS.items():
        rows = [e for e in entries if e["category"] == category]
        if not rows:
            continue
        lines += ["## " + label, ""]
        groups = defaultdict(list)
        for row in rows:
            groups[row["family"]].append(row)
        for name, members in sorted(groups.items()):
            lines += ["### " + name, "", members[0]["possible_action"], "", "| 具体 Java ID | 版本 | 相关源 / 证据 |", "| --- | --- | --- |"]
            ids = defaultdict(list)
            for row in members:
                ids[row["java_id"]].append(row)
            for identifier, items in sorted(ids.items()):
                evidence_versions = defaultdict(list)
                for row in items:
                    evidence_versions[review_evidence(row)].append(row)
                for evidence, evidence_items in evidence_versions.items():
                    lines.append("| `" + identifier + "` | " + ", ".join("26." + e["version"][-1] for e in evidence_items) + " | " + evidence + " |")
            lines.append("")
    (output / "unmatched-review.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8", newline="\n")
    return {"unmatched_review_entries": len(entries), "unique_ids": len({e["java_id"] for e in entries}), "counts": value["counts"]}


def review_evidence(row):
    candidate = next((v for v in row["logical_candidates"] if v["crop_proof"]), row["logical_candidates"][0] if row["logical_candidates"] else None)
    evidence = candidate["source"] if candidate else row["raw_same_name_colors"][0] if row["raw_same_name_colors"] else "无已声明对应源；最近邻被拒绝"
    if row["category"] == "missing-texture-set" and row["raw_pair_evidence"]:
        pair = row["raw_pair_evidence"][0]
        evidence += "; RGB整图一致=" + str(pair["rgb_exact"]) + "/可见一致=" + str(pair["visible_rgb_exact"]) + "; MERS=" + pair["mers_mode"] + str(pair["mers_extent"]) + "; 无TextureSet"
        if pair["rgb_different_pixels"]:
            evidence += "; RGB差异" + str(pair["rgb_different_pixels"]) + "像素(可见" + str(pair["visible_rgb_different_pixels"]) + ") " + str(pair["rgb_different_pixel_examples"])
    if candidate and candidate["crop_proof"]:
        proof = candidate["crop_proof"]
        evidence += "; RGB=0裁切 " + str(proof["target_pixel_rect"]) + "→" + str(proof["source_transformed_pixel_rect"]) + " " + proof["transform"]
    elif candidate:
        evidence += "; " + ", ".join(candidate["rejected_by"][:3])
    return evidence
