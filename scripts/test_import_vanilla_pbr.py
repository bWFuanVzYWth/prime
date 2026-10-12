"""Behavior checks for offline RGB matching, frame projection and editable PNGs."""

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

import numpy as np
from PIL import Image

SPEC = importlib.util.spec_from_file_location("vanilla_pbr_import", Path(__file__).with_name("import-vanilla-pbr.py"))
IMPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMPORTER)
REVIEW_SPEC=importlib.util.spec_from_file_location("vanilla_pbr_review",Path(__file__).with_name("vanilla_pbr_review.py"))
REVIEW=importlib.util.module_from_spec(REVIEW_SPEC)
REVIEW_SPEC.loader.exec_module(REVIEW)


def pattern():
    image = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            image.putpixel((x, y), (20 + x * 11, 30 + y * 10, (x * 13 + y * 7) % 255, 255))
    return image


def source(name, color, mers=None, frame_extent=None):
    extent = frame_extent or color.size
    return {"id": "textures/" + name, "category": name.split("/")[0], "status": "candidate",
            "_color": color, "_mers": mers or Image.new("RGBA", color.size, (0, 0, 255, 0)),
            "_normal": None, "_rects": IMPORTER.frame_rects(color.size, extent)}


def matcher(sources):
    frames = []
    for value in sources:
        for index, rect in enumerate(value["_rects"]):
            image = value["_color"].crop(rect)
            frames.append({"source": value, "index": index, "image": image,
                           "key": IMPORTER.rgb_key(image), "feature": IMPORTER.feature(image),
                           "aspect": image.width / image.height})
    return IMPORTER.Matcher(sources, frames)


def target(name, image, metadata=None):
    extent, animation, used = IMPORTER.java_layout(image, metadata)
    rects = IMPORTER.frame_rects(image.size, extent)
    first = image.crop(rects[used[0]])
    return {"java_id": "minecraft:" + name, "game_version": 263, "category": name.split("/")[0],
            "extent": list(image.size), "frame_extent": list(extent), "animation": animation,
            "_image": image, "_first": first, "_rects": rects,
            "visible_fraction": float(np.mean(np.asarray(first.getchannel("A")) > 12))}


class MatchingTests(unittest.TestCase):
    def test_exact_rgb_ignores_alpha_and_prefers_logical_material(self):
        image = pattern()
        hidden = image.copy()
        hidden.putalpha(0)
        sources = [source("entity/other", hidden), source("blocks/stone", image)]
        candidates = matcher(sources).match(target("block/stone", hidden), {})
        self.assertEqual(candidates[0]["source"]["id"], "textures/blocks/stone")
        self.assertEqual(candidates[0]["method"], "exact-sheet-rgb")
        self.assertEqual(len(candidates), 2)

    def test_transparent_hidden_rgb_does_not_control_fuzzy_metrics(self):
        image = pattern()
        other = image.copy()
        for y in range(16):
            for x in range(8, 16):
                image.putpixel((x, y), (255, 0, 255, 0))
                other.putpixel((x, y), (0, 255, 0, 255))
        candidate = matcher([source("blocks/leaves", other)]).match(target("block/leaves", image), {})[0]
        self.assertEqual(candidate["method"], "fuzzy-spatial-rgb")
        self.assertAlmostEqual(candidate["metrics"]["rmse"], 0, places=6)
        self.assertAlmostEqual(candidate["metrics"]["edge"], 1, places=6)

    def test_equal_average_color_without_spatial_evidence_is_rejected(self):
        image = pattern()
        reversed_image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        self.assertEqual(matcher([source("blocks/stone", reversed_image)]).match(target("block/stone", image), {}), [])

    def test_batch_and_scalar_metrics_agree_with_visible_mask(self):
        image = pattern()
        other = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        mask = np.arange(256) % 3 != 0
        scalar = IMPORTER.metric(image, other, mask)
        batch = IMPORTER.metrics_batch(IMPORTER.feature(image), np.stack([IMPORTER.feature(other)]), mask)
        for key in scalar:
            self.assertAlmostEqual(scalar[key], float(batch[key][0]), places=5)

    def test_forced_frame_is_applied_without_reselection(self):
        sheet = Image.new("RGBA", (16, 32))
        sheet.paste(pattern(), (0, 0))
        sheet.paste(pattern(), (0, 16))
        mers = Image.new("RGBA", sheet.size, (0, 0, 200, 0))
        mers.paste(Image.new("RGBA", (16, 16), (200, 80, 30, 0)), (0, 16))
        value = source("items/clock_atlas", sheet, mers, (16, 16))
        host = target("item/clock_00", pattern())
        policy = {"forced_matches": {host["java_id"]: {"source": value["id"], "frame": 1, "reason": "Authored correction"}}}
        winner = matcher([value]).match(host, policy)[0]
        material, _, projection = IMPORTER.project_material(host, winner)
        self.assertEqual(material.getpixel((0, 0)), (200, 80, 30, 0))
        self.assertEqual(projection["frames"][0]["source_frame"], 1)

    def test_duplicate_rgb_animation_frames_preserve_material_index(self):
        sheet = Image.new("RGBA", (16, 32))
        sheet.paste(pattern(), (0, 0))
        sheet.paste(pattern(), (0, 16))
        mers = Image.new("RGBA", sheet.size, (0, 0, 200, 0))
        mers.paste(Image.new("RGBA", (16, 16), (20, 80, 30, 0)), (0, 16))
        value = source("blocks/fire", sheet, mers, (16, 16))
        host = target("block/fire", sheet, {"animation": {"frames": [0, {"index": 1, "time": 5}]}})
        winner = {"source": value, "frame": 0, "method": "exact-frame-rgb"}
        material, _, projection = IMPORTER.project_material(host, winner)
        self.assertEqual([p["source_frame"] for p in projection["frames"]], [0, 1])
        self.assertEqual(material.getpixel((0, 16)), (20, 80, 30, 0))
        self.assertEqual(host["animation"]["frames"], [[0, 1], [1, 5]])

    def test_policy_frame_index_keeps_all_authored_frames(self):
        sheet=Image.new("RGBA",(16,32));sheet.paste(pattern(),(0,0));sheet.paste(pattern(),(0,16))
        mers=Image.new("RGBA",sheet.size,(0,0,200,0));mers.paste(Image.new("RGBA",(16,16),(200,80,30,0)),(0,16))
        value=source("blocks/conduit_wind_horizontal",sheet,mers,(16,16))
        host=target("entity/conduit/wind",sheet,{"animation":{"frametime":3}})
        policy={"forced_matches":{host["java_id"]:{"source":value["id"],"frame":0,"projection":"frame-index","reason":"Evidenced identical frame layouts"}}}
        winner=matcher([value]).match(host,policy)[0]
        material,_,projection=IMPORTER.project_material(host,winner)
        self.assertEqual(projection["mode"],"policy-frame-index")
        self.assertEqual([p["source_frame"] for p in projection["frames"]],[0,1])
        self.assertEqual(material.getpixel((0,16)),(200,80,30,0))

    def test_nonsquare_static_and_explicit_animation_extent(self):
        image = Image.new("RGBA", (32, 64))
        self.assertEqual(IMPORTER.java_layout(image, None)[0], (32, 64))
        self.assertEqual(IMPORTER.java_layout(image, {"animation": {"width": 16}})[0], (16, 64))
        self.assertEqual(IMPORTER.java_layout(image, {"animation": {"height": 16}})[0], (32, 16))


class MaterialPngTests(unittest.TestCase):
    def test_png_roundtrip_keeps_data_and_invisible_rgb(self):
        pixels = bytes([1, 23, 45, 0, 67, 89, 123, 255]) * 128
        image = Image.frombytes("RGBA", (16, 16), pixels)
        image.info["icc_profile"] = b"not a material color profile"
        decoded = IMPORTER.png_rgba8(IMPORTER.encode_png(image))
        self.assertEqual(decoded.tobytes(), pixels)
        self.assertNotIn("icc_profile", decoded.info)
        self.assertNotIn("gamma", decoded.info)

    def test_rgb_mer_explicitly_sets_subsurface_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "material.tga"
            Image.new("RGB", (16, 16), (191, 34, 100)).save(path)
            rgba, mode = IMPORTER.mers_image(path)
            self.assertEqual(mode, "RGB")
            self.assertEqual(rgba.getpixel((0, 0)), (191, 34, 100, 0))

    def test_bedrock_metal_subsurface_winner_and_tie(self):
        image=Image.frombytes("RGBA",(3,1),bytes([100,23,45,80, 80,23,45,100, 80,23,45,80]))
        self.assertEqual(IMPORTER.canonical_mers(image).tobytes(),bytes([100,23,45,0, 0,23,45,100, 0,23,45,80]))

    def test_rgb_and_sixteen_bit_png_are_rejected(self):
        for image in (Image.new("RGB", (16, 16)), Image.new("I;16", (16, 16))):
            buffer = io.BytesIO()
            image.save(buffer, format="PNG")
            with self.assertRaisesRegex(ValueError, "8-bit RGBA"):
                IMPORTER.png_rgba8(buffer.getvalue())
        with self.assertRaisesRegex(ValueError, "Git LFS"):
            IMPORTER.png_rgba8(b"version https://git-lfs.github.com/spec/v1\n")

    def test_artist_edit_is_preserved_and_explicit_replace_restores_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            image = pattern()
            baseline = IMPORTER.encode_png(image)
            manifest = {"entries": [{"mers": "mers.png", "auxiliary": None, "generated_baseline": {"mers_rgba_sha256": IMPORTER.sha256(image.tobytes())}}]}
            edited = image.copy()
            edited.putpixel((0, 0), (123, 45, 67, 89))
            (output / "mers.png").write_bytes(IMPORTER.encode_png(edited))
            self.assertEqual(IMPORTER.write_outputs(output, {"mers.png": baseline}, old_manifest=manifest), ["mers.png"])
            self.assertEqual(IMPORTER.png_rgba8((output / "mers.png").read_bytes()).tobytes(), edited.tobytes())
            IMPORTER.write_outputs(output, {"mers.png": baseline}, replace=True, old_manifest=manifest)
            self.assertEqual((output / "mers.png").read_bytes(), baseline)

    def test_invalid_existing_art_is_rejected_before_other_outputs_write(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "mers.png").write_bytes(b"invalid artist PNG")
            with self.assertRaises(ValueError):
                IMPORTER.write_outputs(output, {"new.png": IMPORTER.encode_png(pattern()), "mers.png": IMPORTER.encode_png(pattern())})
            self.assertFalse((output / "new.png").exists())

    def test_normal_aux_encoding_has_flat_center_and_default_ior(self):
        auxiliary = IMPORTER.auxiliary_image(Image.new("RGB", (1, 1), (128, 128, 255)))
        self.assertEqual(auxiliary.getpixel((0, 0)), (128, 128, 0, 85))
        self.assertGreater(IMPORTER.auxiliary_image(Image.new("RGB", (1, 1), (128, 190, 240))).getpixel((0, 0))[1], 128)

    def test_frozen_assets_validate_without_source_checkout(self):
        result = IMPORTER.verify_assets(IMPORTER.DEFAULT_OUTPUT)
        self.assertGreater(result["entries"], 0)
        self.assertGreaterEqual(result["images"], result["entries"])

    def test_source_free_validation_accepts_edited_pixels_and_rejects_incomplete_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);image=pattern()
            entry={"java_id":"minecraft:block/stone","game_version":0,"extent":[16,16],"base":"host","mers":"mers.png","auxiliary":None,"authored_normal":False,
                   "generated_baseline":{"mers_rgba_sha256":IMPORTER.sha256(image.tobytes())}}
            manifest={"schema":1,"entries":[entry]}
            report={"schema":1,"summary":{"source_sets":0,"registry_entries":1,"versions":{"26.3":{"textures":1}}},"bedrock":[],
                    "java":{"26.3":[{"java_id":entry["java_id"],"status":"integrated"}]}}
            (output/"manifest.json").write_text(json.dumps(manifest),encoding="utf-8")
            (output/"matching-report.json").write_text(json.dumps(report),encoding="utf-8")
            (output/"registry.rs").write_bytes(IMPORTER.registry_bytes([entry]))
            image.putpixel((0,0),(123,45,67,89));(output/"mers.png").write_bytes(IMPORTER.encode_png(image))
            self.assertEqual(IMPORTER.verify_assets(output)["artist_edited"],["mers.png"])
            report["summary"]["versions"]["26.3"]["textures"]=2
            (output/"matching-report.json").write_text(json.dumps(report),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"Incomplete Java inventory"):IMPORTER.verify_assets(output)


class ReviewEvidenceTests(unittest.TestCase):
    def test_review_does_not_collapse_version_specific_visible_pixel_difference(self):
        pair={"rgb_exact":True,"visible_rgb_exact":True,"mers_mode":"RGB","mers_extent":[16,16],
              "rgb_different_pixels":0,"visible_rgb_different_pixels":0,"rgb_different_pixel_examples":[]}
        row={"category":"missing-texture-set","logical_candidates":[],"raw_same_name_colors":["source.png"],"raw_pair_evidence":[pair]}
        exact=REVIEW.review_evidence(row)
        pair.update(rgb_exact=False,visible_rgb_exact=False,rgb_different_pixels=1,visible_rgb_different_pixels=1,rgb_different_pixel_examples=[[6,0]])
        differing=REVIEW.review_evidence(row)
        self.assertNotEqual(exact,differing)
        self.assertIn("True/可见一致=True",exact)
        self.assertIn("False/可见一致=False",differing)
        self.assertIn("[[6, 0]]",differing)

    def test_crop_proof_preserves_explicit_uv_and_ignores_only_invisible_rgb(self):
        source_image=Image.new("RGBA",(32,32),(0,0,0,255))
        patch=pattern().crop((0,0,8,8));source_image.paste(patch,(4,4))
        target_image=Image.new("RGBA",(16,16),(255,0,255,0));target_image.paste(patch,(4,5))
        proof=REVIEW.crop_proof(target_image,source_image,[[4,5,12,13]])
        self.assertEqual(proof["target_pixel_rect"],[4,5,12,13])
        self.assertEqual(proof["source_transformed_pixel_rect"],[4,4,12,12])
        self.assertEqual(proof["visible_pixel_coverage"],1.)
        self.assertEqual(proof["rgb_rmse"],0)
        target_image.putpixel((4,5),(250,240,230,255))
        self.assertIsNone(REVIEW.crop_proof(target_image,source_image,[[4,5,12,13]]))

    def test_model_evidence_resolves_inherited_dict_sprite_and_item_tint(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"client.jar"
            with ZipFile(path,"w") as jar:
                jar.writestr("assets/minecraft/models/block/root.json",json.dumps({"textures":{"all":{"sprite":"minecraft:block/example","force_translucent":True}},"elements":[{"faces":{"north":{"uv":[4,5,12,13],"texture":"#all"}}}]}))
                jar.writestr("assets/minecraft/models/block/child.json",json.dumps({"parent":"minecraft:block/root"}))
                jar.writestr("assets/minecraft/items/example.json",json.dumps({"model":{"type":"minecraft:model","model":"minecraft:block/child","tints":[{"type":"minecraft:constant","value":42}]}}))
            with ZipFile(path) as jar:
                evidence=REVIEW.Models(jar).evidence("minecraft:block/example")
            self.assertEqual(evidence["model_count"],2)
            self.assertEqual(evidence["uv_regions"],[[4,5,12,13]])
            self.assertEqual(evidence["item_definitions"][0]["tints"][0]["value"],42)


if __name__ == "__main__":
    unittest.main()
