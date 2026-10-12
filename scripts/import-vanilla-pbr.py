"""Offline pinned Bedrock-to-Java RGB matching and editable schema-1 PNG generation.

Builds only consume frozen registry.rs/PNGs. --check needs no game/reference.
Generation retains edited PNGs; --replace-art explicitly restores baselines.
"""
import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess
from zipfile import ZipFile
import zlib

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
import numpy as np
from PIL import Image, __version__ as pillow_version

SOURCE_COMMIT = "921fafb05c93abeae56c2f2868cd8f942bdbcc0f"
SOURCE_VERSION = "1.26.30.5"
SCHEMA = 1
WORKSPACE = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = WORKSPACE / "crates/prime-minecraft/assets/vanilla_pbr"
DEFAULT_POLICY = WORKSPACE / "scripts/vanilla-pbr-matches.json"
SETTINGS = {"feature_extent": [16, 16], "near": {"rmse":36/255,"mae":28/255,"centered":30/255,"corr":.55,"edge":.35},
            "recolor": {"rmse":96/255,"mae":96/255,"centered":14/255,"corr":.85,"edge":.70},
            "flat": {"rmse":16/255,"centered":8/255,"name_support":.4},
            "mask": "Exact uses all RGB including invisible texels; fuzzy uses Java alpha>0.05 on 16x16 nearest visibility, ignores source alpha",
            "sparse": "visible fraction<0.10 requires name support>=0.4; very low variance requires flat+name gate",
            "tie_break": "exact class; for fuzzy acceptable logical name, then spatial score/category/source ID/frame",
            "rgb24_mers": "Importer compatibility approximation: RGB MERS is interpreted as MER with missing S=0",
            "mers_winner": "Bedrock M>S keeps M and clears S; otherwise keeps S and clears M (tie selects S); E/R unchanged",
            "projection": "Exact whole sheet first; otherwise nearest source RGB frame per Java frame, static source repeated; host alpha/tint preserved",
            "normal": "DirectX source XYZ normalized; green sign retained because Prime bitangent follows +V/image-down; biased XY, mip0 distribution0, IOR85",
            "normal_reference": "https://www.nvidia.com/en-us/geforce/guides/minecraft-rtx-texturing-guide/",
            "frame_tie": "Numbered Java icons prefer equal source index among identical RGB; other animation frames prefer equal sheet index; first used frame fixes selected candidate"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value, compact=False):
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":") if compact else None,
                       indent=None if compact else 2) + "\n").encode()


def read_json(path):
    return json.loads(re.sub(r"(?m)^\s*//.*$", "", path.read_text(encoding="utf-8-sig")))


def png_rgba8(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Expected PNG bytes; download Git LFS objects before building")
    if len(data)<33 or data[12:16]!=b"IHDR":
        raise ValueError("Missing PNG IHDR")
    width,height,depth,color = struct.unpack(">IIBB",data[16:26])
    if depth!=8 or color!=6:
        raise ValueError("Internal PNG must be 8-bit RGBA")
    if not width or not height or max(width,height)>16384:
        raise ValueError("Invalid internal PNG extent")
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        if getattr(image,"is_animated",False):
            raise ValueError("Use a PNG sheet, not APNG")
        return image.copy()


def encode_png(image):
    rgba=Image.frombytes("RGBA",image.size,image.tobytes())
    buffer=io.BytesIO()
    rgba.save(buffer,format="PNG",compress_level=9,optimize=False)
    data=buffer.getvalue()
    if png_rgba8(data).tobytes()!=image.tobytes():
        raise ValueError("PNG roundtrip changed material bytes")
    return data


def source_image(path, required_mode=None):
    with Image.open(path) as image:
        image.load()
        if required_mode and image.mode!=required_mode:
            raise ValueError("Absent MERS alpha requires explicit RGB MER compatibility")
        return image.convert("RGBA")


def mers_image(path):
    with Image.open(path) as image:
        image.load()
        if image.mode not in ("RGB","RGBA"):
            raise ValueError("Unsupported MER/MERS image channels")
        rgba=image.convert("RGBA")
        if image.mode=="RGB":
            rgba.putalpha(0)
        return rgba,image.mode


def canonical_mers(image):
    values=np.array(image,dtype=np.uint8)
    metal=values[...,0]>values[...,3]
    values[...,0]=np.where(metal,values[...,0],0)
    values[...,3]=np.where(metal,0,values[...,3])
    return Image.fromarray(values)


def resolve_sibling(path,name):
    if not isinstance(name,str) or any(c in name for c in "/\\."):
        raise ValueError("Pinned importer requires sibling image stems")
    for suffix in (".tga",".png",".jpg",".jpeg"):
        candidate=path.parent/(name+suffix)
        if candidate.is_file():return candidate
    raise ValueError("Missing source image "+name)


def verify_checkout(source):
    command=["git","-c","safe.directory="+source.as_posix(),"-C",str(source)]
    head=subprocess.run(command+["rev-parse","HEAD"],capture_output=True,text=True,check=True)
    if head.stdout.strip()!=SOURCE_COMMIT:
        raise ValueError("Expected pinned source commit "+SOURCE_COMMIT)
    status=subprocess.run(command+["status","--porcelain","--untracked-files=all","--","resource_pack/textures","LICENSE.md","version.json"],capture_output=True,text=True,check=True)
    if status.stdout.strip():raise ValueError("Pinned reference inputs have tracked or untracked changes")


def frame_rects(size,extent):
    w,h=size;fw,fh=extent
    if not fw or not fh or w%fw or h%fh:raise ValueError("Frame extent does not tile sheet")
    return [(x,y,x+fw,y+fh) for y in range(0,h,fh) for x in range(0,w,fw)]


def java_layout(image,metadata):
    animation=metadata.get("animation") if metadata else None
    if animation is None:return image.size,None,[0]
    side=min(image.size)
    extent=(animation["width"],animation["height"]) if "width" in animation and "height" in animation else (animation["width"],image.height) if "width" in animation else (image.width,animation["height"]) if "height" in animation else (side,side)
    count=len(frame_rects(image.size,extent));duration=animation.get("frametime",1)
    sequence=[[f if isinstance(f,int) else f["index"],duration if isinstance(f,int) else f.get("time",duration)]
              for f in animation.get("frames",list(range(count))) ]
    if not sequence or any(i<0 or i>=count or t<=0 for i,t in sequence):raise ValueError("Invalid Java animation")
    return extent,{"frames":sequence,"interpolate":animation.get("interpolate",False)},[i for i,t in sequence]


def rgb_key(image):return image.size,sha256(image.convert("RGB").tobytes())


def feature(image):
    return np.asarray(image.convert("RGB").resize((16,16),Image.Resampling.BILINEAR),dtype=np.float32).reshape(256,3)/255


def tokens(name):
    return set(re.findall(r"[a-z0-9]+",name.lower()))-{"minecraft","textures","block","blocks","item","items","colored","normal"}


def support(a,b):
    a,b=tokens(a),tokens(b)
    return len(a&b)/max(1,len(a|b))


def metric(a,b,mask=None):
    a=feature(a) if isinstance(a,Image.Image) else a
    b=feature(b) if isinstance(b,Image.Image) else b
    if mask is None or not np.any(mask):mask=np.ones(256,dtype=bool)
    av,bv=a[mask],b[mask]
    ca,cb=av-av.mean(axis=0),bv-bv.mean(axis=0)
    gray=np.array([.2126,.7152,.0722])
    la,lb=av@gray,bv@gray;la-=la.mean();lb-=lb.mean()
    denom=float(np.linalg.norm(la)*np.linalg.norm(lb))
    ga,gb=(a@gray).reshape(16,16),(b@gray).reshape(16,16)
    visible=mask.reshape(16,16)
    vertical=(visible[1:] & visible[:-1]).ravel()
    horizontal=(visible[:,1:] & visible[:,:-1]).ravel()
    ea=np.concatenate((np.diff(ga,axis=0).ravel()[vertical],np.diff(ga,axis=1).ravel()[horizontal]))
    eb=np.concatenate((np.diff(gb,axis=0).ravel()[vertical],np.diff(gb,axis=1).ravel()[horizontal]))
    edge_denom=float(np.linalg.norm(ea)*np.linalg.norm(eb))
    return {"rmse":float(np.sqrt(np.mean((av-bv)**2))),"mae":float(np.mean(np.abs(av-bv))),
            "centered":float(np.sqrt(np.mean((ca-cb)**2))),"corr":float(np.clip(np.dot(la,lb)/denom,-1,1)) if min(np.linalg.norm(la),np.linalg.norm(lb))>1e-6 else 1.,
            "edge":float(np.clip(np.dot(ea,eb)/edge_denom,-1,1)) if min(np.linalg.norm(ea),np.linalg.norm(eb))>1e-6 else 1.,
            "min_std":min(float(av.std(axis=0).mean()),float(bv.std(axis=0).mean()))}


def metrics_batch(target,features,mask):
    """One NumPy pass; source features are cached once for the entire inventory."""
    av=target[mask];bv=features[:,mask,:]
    ca=av-av.mean(axis=0);cb=bv-bv.mean(axis=1,keepdims=True)
    gray=np.array([.2126,.7152,.0722],dtype=np.float32)
    la=av@gray;la-=la.mean();lb=bv@gray;lb-=lb.mean(axis=1,keepdims=True)
    denominator=np.linalg.norm(la)*np.linalg.norm(lb,axis=1)
    correlation=np.ones(len(features),dtype=np.float32)
    np.divide(lb@la,denominator,out=correlation,where=(np.linalg.norm(la)>1e-6)&(np.linalg.norm(lb,axis=1)>1e-6))
    ga=(target@gray).reshape(16,16);gb=(features@gray).reshape(-1,16,16)
    visible=mask.reshape(16,16);vertical=(visible[1:]&visible[:-1]).ravel();horizontal=(visible[:,1:]&visible[:,:-1]).ravel()
    ea=np.concatenate((np.diff(ga,axis=0).ravel()[vertical],np.diff(ga,axis=1).ravel()[horizontal]))
    eb=np.concatenate((np.diff(gb,axis=1).reshape(len(features),-1)[:,vertical],np.diff(gb,axis=2).reshape(len(features),-1)[:,horizontal]),axis=1)
    edge_denominator=np.linalg.norm(ea)*np.linalg.norm(eb,axis=1);edge=np.ones(len(features),dtype=np.float32)
    np.divide(eb@ea,edge_denominator,out=edge,where=(np.linalg.norm(ea)>1e-6)&(np.linalg.norm(eb,axis=1)>1e-6))
    return {"rmse":np.sqrt(np.mean((av-bv)**2,axis=(1,2))),"mae":np.mean(np.abs(av-bv),axis=(1,2)),
            "centered":np.sqrt(np.mean((ca-cb)**2,axis=(1,2))),"corr":np.clip(correlation,-1,1),"edge":np.clip(edge,-1,1),
            "min_std":np.minimum(av.std(axis=0).mean(),bv.std(axis=1).mean(axis=1))}


def acceptable(values,name_support,fraction):
    if fraction<.10 and name_support<.4:return None
    if values["min_std"]<.008:
        gate=SETTINGS["flat"]
        return "flat-logical" if values["rmse"]<=gate["rmse"] and values["centered"]<=gate["centered"] and name_support>=gate["name_support"] else None
    for mode in ("near","recolor"):
        if all(values[key]<=SETTINGS[mode][key] for key in ("rmse","mae","centered")) and all(values[key]>=SETTINGS[mode][key] for key in ("corr","edge")):
            return mode
    return None


def public(record):return {k:v for k,v in record.items() if not k.startswith("_")}


def sources_inventory(source,policy):
    base=source/"resource_pack"
    flips={v["flipbook_texture"]:v for v in read_json(base/"textures/flipbook_textures.json")}
    records,frames=[],[]
    for path in sorted(base.rglob("*.texture_set.json")):
        identifier=path.relative_to(base).as_posix().removesuffix(".texture_set.json")
        record={"id":identifier,"category":identifier.split("/")[1] if identifier.count("/")>1 else "root","selected_by":[],"alternatives_for":[],"deferred_candidates_for":[]}
        records.append(record)
        if identifier in policy.get("exclude_bedrock",{}):
            record.update(status="policy-excluded",reason=policy["exclude_bedrock"][identifier]);continue
        try:
            value=read_json(path)["minecraft:texture_set"]
            color_path=resolve_sibling(path,value["color"]);color=source_image(color_path)
            layer=value.get("metalness_emissive_roughness_subsurface",value.get("metalness_emissive_roughness"))
            if isinstance(layer,list):
                mers=Image.new("RGBA",color.size,tuple(layer+([0] if len(layer)==3 else [])));mode="constant";mers_path=None
            else:
                mers_path=resolve_sibling(path,layer);mers,mode=mers_image(mers_path)
            mers=canonical_mers(mers)
            normal_path=resolve_sibling(path,value["normal"]) if "normal" in value else None
            normal=source_image(normal_path) if normal_path else None
            override=policy.get("source_frame_extent",{}).get(identifier)
            extent=tuple(override["extent"] if isinstance(override,dict) else override) if override else (color.width,color.width) if identifier in flips else color.size
            rects=frame_rects(color.size,extent)
            record.update(status="candidate",extent=list(color.size),frame_extent=list(extent),frame_count=len(rects),
                          frame_layout_source=override.get("reason") if isinstance(override,dict) else "flipbook_textures.json" if identifier in flips else "whole source image",
                          rgb_sha256=rgb_key(color)[1],mers_mode=mode,texture_set_path=path.relative_to(source).as_posix(),
                          color_path=color_path.relative_to(source).as_posix(),mers_path=mers_path.relative_to(source).as_posix() if mers_path else None,
                          normal_path=normal_path.relative_to(source).as_posix() if normal_path else None,
                          texture_set_sha256=sha256(path.read_bytes()),color_sha256=sha256(color_path.read_bytes()),
                          mers_sha256=sha256(mers_path.read_bytes()) if mers_path else sha256(mers.tobytes()),
                          canonical_mers_rgba_sha256=sha256(mers.tobytes()),
                          normal_sha256=sha256(normal_path.read_bytes()) if normal_path else None,
                          _color=color,_mers=mers,_normal=normal,_rects=rects)
            for index,rect in enumerate(rects):
                image=color.crop(rect)
                frames.append({"source":record,"index":index,"image":image,"key":rgb_key(image),"feature":feature(image),"aspect":image.width/image.height})
        except (ValueError,KeyError,OSError) as error:record.update(status="missing-source" if str(error).startswith("Missing source") else "invalid-source",reason=str(error))
    return records,frames


def atlas_members(jar,atlas,texture_ids):
    value=json.loads(jar.read("assets/minecraft/atlases/"+atlas+".json"))
    members=set()
    for entry in value["sources"]:
        if entry["type"].endswith(":directory"):
            root=entry["source"]+"/"
            members.update("minecraft:"+entry["prefix"]+p[len(root):] for p in texture_ids if p.startswith(root))
        elif entry["type"].endswith(":single"):
            members.add(entry.get("sprite",entry["resource"]))
    return members


def java_inventory(path,version,enabled_atlases):
    records=[]
    with ZipFile(path) as jar:
        if json.loads(jar.read("version.json"))["id"]!=version:raise ValueError("Wrong Java client jar")
        names=set(jar.namelist());prefix="assets/minecraft/textures/"
        pngs=sorted(n for n in names if n.startswith(prefix) and n.endswith(".png"))
        ids=[n[len(prefix):-4] for n in pngs]
        blocks=atlas_members(jar,"blocks",ids);items=atlas_members(jar,"items",ids)
        for name,target in zip(pngs,ids):
            identifier="minecraft:"+target
            atlas="blocks" if identifier in blocks else "items" if identifier in items else None
            record={"java_id":identifier,"game_version":int(version.replace(".","")),"category":target.split("/")[0],"atlas":atlas}
            records.append(record)
            try:
                with Image.open(io.BytesIO(jar.read(name))) as image:image=image.convert("RGBA")
                metadata=json.loads(jar.read(name+".mcmeta")) if name+".mcmeta" in names else None
                extent,animation,used=java_layout(image,metadata);rects=frame_rects(image.size,extent)
                first=image.crop(rects[used[0]])
                record.update(extent=list(image.size),frame_extent=list(extent),animation=animation,rgb_sha256=rgb_key(image)[1],rgba_sha256=sha256(image.tobytes()),
                              visible_fraction=float(np.mean(np.asarray(first.getchannel("A").resize((16,16),Image.Resampling.NEAREST))>12)),
                              _image=image,_first=first,_rects=rects,status="unmatched" if atlas in enabled_atlases else "runtime-deferred",
                              reason=None if atlas in enabled_atlases else "No trusted active atlas material source; standalone identity/vanilla origin unavailable")
            except (ValueError,KeyError,OSError) as error:record.update(status="invalid-java-layout",reason=str(error))
    return records


def generated_sprite_inventory(java_jars):
    """Generated atlas IDs are not literal PNGs and do not prove pack origin."""
    inventories={}
    for version,path in java_jars.items():
        records=[]
        with ZipFile(path) as jar:
            for name in sorted(n for n in jar.namelist() if n.startswith("assets/minecraft/atlases/") and n.endswith(".json")):
                atlas=Path(name).stem
                for source in json.loads(jar.read(name))["sources"]:
                    if not source["type"].endswith(":paletted_permutations"):continue
                    for texture in source["textures"]:
                        for suffix,palette in sorted(source["permutations"].items()):
                            records.append({"java_id":texture+source.get("separator","_")+suffix,"atlas":atlas,"source_texture":texture,
                                            "palette_key":source["palette_key"],"palette":palette,"status":"runtime-deferred",
                                            "reason":"Generated source texture and palette winning-pack origin are not exposed; no literal textures/<sprite ID>.png proves vanilla origin"})
        inventories[version]=sorted(records,key=lambda r:(r["java_id"],r["atlas"]))
    return inventories


def add_generated_sprite_metadata(report,java_jars):
    report["java_inventory_kind"]="Literal PNG textures, excluding generated atlas permutations"
    report["generated_java_sprites"]=generated_sprite_inventory(java_jars)
    for version,records in report["generated_java_sprites"].items():
        report["summary"]["versions"][version]["generated_palette_sprites_deferred"]=len(records)


def review_metadata(report):
    sources={s["id"]:s for s in report["bedrock"]}
    for source in sources.values():
        for key in ("selected_by","alternatives_for","deferred_candidates_for"):
            source[key]=sorted(set(source[key]))
    for records in report["java"].values():
        for target in records:
            candidates=target.get("candidates",[])
            target["winner_runner_up_score_margin"]=round(candidates[1]["score"]-candidates[0]["score"],6) if len(candidates)>1 else None
            fingerprints={(sources[c["source"]].get("canonical_mers_rgba_sha256",sources[c["source"]].get("mers_sha256")),sources[c["source"]].get("normal_sha256")) for c in candidates}
            target["top_candidates_material_conflict"]=len(fingerprints)>1


def write_unintegrated(output,report):
    """Retain every excluded ID in a reviewable list, combining repeated versions."""
    sections={"Eligible Java textures without an integrated preset":{},"Other literal Java textures deferred or unsupported":{},"Generated Java palette sprites":{},"Unused Bedrock texture sets":{}}
    for version,targets in report["java"].items():
        for target in targets:
            if target["status"]=="integrated":continue
            title="Eligible Java textures without an integrated preset" if target["atlas"] in report["policy"]["enabled_atlases"] else "Other literal Java textures deferred or unsupported"
            reason=target["status"]+": "+target["reason"]
            sections[title].setdefault(reason,{}).setdefault(target["java_id"],[]).append(version)
    for version,targets in report.get("generated_java_sprites",{}).items():
        for target in targets:
            sections["Generated Java palette sprites"].setdefault(target["reason"],{}).setdefault(target["java_id"],[]).append(version)
    for source in report["bedrock"]:
        if source["status"]!="integrated":sections["Unused Bedrock texture sets"].setdefault(source["status"]+": "+source["reason"],{})[source["id"]]=[]
    lines=["# Unintegrated vanilla material entries","","Generated from the pinned sources and matching policy. Every ID is listed; repeated Java versions share a row. Literal PNG inventory and derived palette sprite inventory are separate. Full candidates, metric values and conflict targets are in [matching-report.json](matching-report.json).",""]
    for title,groups in sections.items():
        lines.extend(["## "+title,""])
        for reason,entries in sorted(groups.items()):
            lines.extend(["### "+reason.replace("\n"," "),"","| Texture / sprite ID | Java versions |","| --- | --- |"])
            lines.extend("| `"+identifier+"` | "+", ".join(versions)+" |" for identifier,versions in sorted(entries.items()))
            lines.append("")
    (output/"unintegrated.md").write_text("\n".join(lines).rstrip()+"\n",encoding="utf-8",newline="\n")


def unmatched_family(identifier):
    category,name=identifier.split(":",1)[1].split("/",1)
    if category=="block" and (name.startswith("bed_") or "_bed_" in name or name.endswith("_bed")):return "Bed faces"
    if name.endswith("_sign"):return "Signs and hanging signs"
    if name.startswith("dried_ghast_hydration_"):return "Dried ghast hydration faces"
    if name.startswith("destroy_"):return "Block breaking overlays"
    if name.endswith("_chain"):return "Chains"
    if name.startswith("sulfur_spike"):return "Sulfur spike blocks/items"
    if "poplar" in name:return "Poplar blocks/items"
    if name.endswith("_cushion"):return "Cushion items"
    if category=="item" and "map" in name:return "Map icons/overlays"
    if name.startswith(("debug","test_")) or category=="item" and (name=="light" or name.startswith("light_")):return "Debug/test/light icons"
    if category=="entity" and name.startswith("conduit/") or identifier=="minecraft:block/conduit":return "Conduit"
    return "Other "+category+" textures"


def summary_groups(report,status,key):
    groups={}
    for version,records in report["java"].items():
        for record in records:
            if record["status"]!=status:continue
            label=key(record["java_id"])
            counts=groups.setdefault(label,{})
            counts[version]=counts.get(version,0)+1
    versions=list(report["java"])
    text="| Texture family | "+" | ".join(versions)+" |\n| --- | "+" | ".join("---:" for _ in versions)+" |\n"
    for label,counts in sorted(groups.items(),key=lambda item:(-sum(item[1].values()),item[0])):
        text+="| "+label+" | "+" | ".join(str(counts.get(v,0)) for v in versions)+" |\n"
    return text


def write_summary(output,summary,report):
    text="# Frozen vanilla material matching\n\n"
    text+="Counts below enumerate literal Java PNG textures. Generated palette sprites are inventoried separately and deferred because their winning source and palette origins are unavailable. Eligibility follows the pinned JAR's actual blocks/items atlas declarations.\n\n"
    text+="| Java | Literal PNGs | Eligible atlas PNGs | Integrated | Eligible unmatched | Other deferred | Generated palette sprites deferred |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n"
    for version,values in summary["versions"].items():text+="| "+version+" | "+" | ".join(str(values.get(key,0)) for key in ("textures","eligible","integrated","unmatched","runtime_deferred","generated_palette_sprites_deferred"))+" |\n"
    text+="\n"+str(summary["registry_entries"])+" frozen entries ("+str(summary["common_entries"])+" shared); "+str(summary["png_files"])+" RGBA8 PNGs, "+str(summary.get("referenced_png_bytes",summary["png_bytes"]))+" encoded bytes and "+str(summary["decoded_rgba8_bytes"])+" base-level decoded bytes. These are asset costs, not runtime performance measurements.\n\n"
    text+="Bedrock sets: "+", ".join(str(count)+" "+status for status,count in summary["bedrock_status"].items())+".\n\n"
    text+="## What the long exclusion list contains\n\n"
    text+="Counts are texture/sprite IDs, not distinct block or item types. Bed colors/faces, hydration stages and two Java versions expand a small number of families into many rows. Eligible unmatched IDs have no accepted RGB/frame correspondence; other literal PNGs are outside the activated blocks/items source boundary. Generated palette sprites are counted separately, and unused Bedrock sets are a second source inventory rather than additional Java gaps.\n\n"
    text+="### Eligible atlas textures without a match\n\n"+summary_groups(report,"unmatched",unmatched_family)+"\n"
    text+="### Other literal textures deferred by source boundary\n\n"+summary_groups(report,"runtime-deferred",lambda identifier:identifier.split(":",1)[1].split("/",1)[0])+"\n"
    text+="Entity/particle textures need trustworthy runtime source identity, winning-pack origin and UV/material ownership before activation. GUI/font/map/environment/colormap data also include inputs with no ordinary surface-PBR role; inventory presence alone does not justify attaching a material. Palette-derived armor/item sprites additionally need the winning origins of both source image and palette.\n\n"
    text+="[unintegrated.md](unintegrated.md) lists every excluded ID and reason. Every Java decision, top accepted alternatives, unused Bedrock set and failure reason is in [matching-report.json](matching-report.json). Exact RGB ignores alpha; fuzzy RGB uses Java visible texels and spatial structure. Host color, alpha and tint remain authoritative. Pixel similarity supports an artistic transfer, not model/UV or rendering equivalence.\n\n"
    text+="Source-frame correspondence and normalized layer rescaling are frozen per entry. Repeated RGB frames can have different materials; deterministic same-index ties and the selected first used frame are recorded. RGB24 MER with S=0 is an importer compatibility approximation. Bedrock metal/subsurface winner selection is applied before encoding. Four DirectX normals preserve green sign for Prime's +V basis.\n\n"
    text+="Common routed item geometry is covered. Raw, foil/outline, unknown source or cross-sprite UV fallback, standalone dynamic textures and generated palette sprites retain their existing material behavior. Build/runtime consumes only frozen PNGs and registry.rs.\n"
    (output/"matching-summary.md").write_text(text,encoding="utf-8",newline="\n")


class Matcher:
    def __init__(self,sources,frames):
        self.frames=frames;self.sheet={};self.frame={};self.resampled={}
        for source in sources:
            if source["status"]=="candidate":self.sheet.setdefault(rgb_key(source["_color"]),[]).append(source)
        for f in frames:
            self.frame.setdefault(f["key"],[]).append(f)
            key=(f["aspect"],sha256(f["image"].convert("RGB").resize((16,16),Image.Resampling.NEAREST).tobytes()))
            self.resampled.setdefault(key,[]).append(f)
        self.features=np.stack([f["feature"].ravel() for f in frames]);self.squares=self.features*self.features
        self.aspects=np.array([f["aspect"] for f in frames])

    def rank(self,target,candidates):
        numbered=re.search(r"_(\d+)$",target["java_id"])
        preferred_frame=int(numbered.group(1)) if numbered else 0
        return sorted(candidates,key=lambda c:(c["priority"],-support(target["java_id"],c["source"]["id"]) if c["priority"]==3 else 0,c["score"],
                                               -support(target["java_id"],c["source"]["id"]),c["source"]["category"]!={"block":"blocks","item":"items"}.get(target["category"]),c["source"]["id"],c["frame"]!=preferred_frame,c["frame"]))

    def match(self,target,policy):
        forced=policy.get("forced_matches",{}).get(str(target["game_version"])+":"+target["java_id"],policy.get("forced_matches",{}).get(target["java_id"]))
        if forced:
            f=next((f for f in self.frames if f["source"]["id"]==forced["source"] and f["index"]==forced.get("frame",0)),None)
            if f is None:raise ValueError("Forced source unavailable: "+str(forced))
            winner={"source":f["source"],"frame":f["index"],"priority":-1,"score":0.,"method":"policy-forced","reason":forced["reason"]}
            if "projection" in forced:winner["projection"]=forced["projection"]
            return [winner]+[c for c in self.match(target,{}) if c["source"]["id"]!=f["source"]["id"] or c["frame"]!=f["index"]]
        sheet=self.sheet.get(rgb_key(target["_image"]),[])
        if sheet:return self.rank(target,[{"source":s,"frame":0,"priority":0,"score":0.,"method":"exact-sheet-rgb"} for s in sheet])
        frames=self.frame.get(rgb_key(target["_first"]),[]);kind="exact-frame-rgb";priority=1
        if not frames:
            first=target["_first"];key=(first.width/first.height,sha256(first.convert("RGB").resize((16,16),Image.Resampling.NEAREST).tobytes()))
            frames=self.resampled.get(key,[]);kind="resampled-frame-rgb";priority=2
        if frames:return self.rank(target,[{"source":f["source"],"frame":f["index"],"priority":priority,"score":0.,"method":kind} for f in frames])
        first=target["_first"];mask=np.asarray(first.getchannel("A").resize((16,16),Image.Resampling.NEAREST)).ravel()>12
        if not np.any(mask):mask[:]=True
        weight=np.repeat(mask,3).astype(np.float32);tf=feature(first).ravel()
        mse=np.maximum(0.,(self.squares@weight-2*self.features@(tf*weight)+np.sum(tf*tf*weight))/(3*np.sum(mask)))
        indexes=np.flatnonzero((mse<=(96/255)**2)&(np.abs(self.aspects-first.width/first.height)<1e-6))
        candidates=[]
        if not len(indexes):return candidates
        qualities=metrics_batch(tf.reshape(256,3),self.features[indexes].reshape(-1,256,3),mask)
        name_support={self.frames[i]["source"]["id"]:support(target["java_id"],self.frames[i]["source"]["id"]) for i in indexes}
        for n,i in enumerate(indexes):
            f=self.frames[i];quality={key:float(values[n]) for key,values in qualities.items()};ns=name_support[f["source"]["id"]]
            gate=acceptable(quality,ns,target["visible_fraction"])
            if gate:
                score=quality["rmse"]+.5*quality["centered"]+12/255*(1-quality["corr"])+8/255*(1-quality["edge"])
                candidates.append({"source":f["source"],"frame":f["index"],"priority":3,"score":score,"method":"fuzzy-spatial-rgb","gate":gate,"metrics":quality})
        return self.rank(target,candidates)


def candidate_record(c):return {k:(v["id"] if k=="source" else v) for k,v in c.items()}


def crop_layer(layer,rectangle,color_size):
    w,h=layer.size;cw,ch=color_size
    return layer.crop((round(rectangle[0]*w/cw),round(rectangle[1]*h/ch),round(rectangle[2]*w/cw),round(rectangle[3]*h/ch)))


def auxiliary_image(normal):
    values=np.asarray(normal.convert("RGB"),dtype=np.float64)/127.5-1
    values/=np.maximum(np.linalg.norm(values,axis=2,keepdims=True),1e-12)
    out=np.zeros((*values.shape[:2],4),dtype=np.uint8)
    out[...,0]=np.clip(np.floor(128+127*values[...,0]+.5),1,255)
    out[...,1]=np.clip(np.floor(128+127*values[...,1]+.5),1,255)
    out[...,3]=85
    return Image.fromarray(out)


def project_material(target,winner):
    source=winner["source"];size=tuple(target["extent"]);extent=tuple(target["frame_extent"])
    normal=source["_normal"]
    if winner["method"]=="exact-sheet-rgb":
        return source["_mers"].resize(size,Image.Resampling.NEAREST),auxiliary_image(normal.resize(size,Image.Resampling.NEAREST)) if normal is not None else None,{"mode":"normalized-whole-sheet","layer_rescaled":source["_mers"].size!=size}
    mers=Image.new("RGBA",size);aux=Image.new("RGBA",size,(128,128,0,85)) if normal is not None else None;mapping=[]
    source_frames=[feature(source["_color"].crop(r)) for r in source["_rects"]]
    frame_index_policy=winner.get("projection")=="frame-index"
    if frame_index_policy and len(source_frames)!=len(target["_rects"]):raise ValueError("Forced frame-index projection requires identical frame counts")
    for target_index,rect in enumerate(target["_rects"]):
        image=target["_image"].crop(rect)
        mask=np.asarray(image.getchannel("A").resize((16,16),Image.Resampling.NEAREST)).ravel()>12
        image_feature=feature(image);distances=[metric(image_feature,frame,mask)["rmse"] for frame in source_frames]
        first_used=target["animation"]["frames"][0][0] if target["animation"] else 0
        preferred=winner["frame"] if target_index==first_used else target_index
        index=target_index if frame_index_policy else winner["frame"] if target_index==first_used else min(range(len(distances)),key=lambda i:(distances[i],i!=preferred,i))
        mapping.append({"source_frame":index,"rgb_rmse":round(distances[index],6)})
        mers.paste(crop_layer(source["_mers"],source["_rects"][index],source["_color"].size).resize(extent,Image.Resampling.NEAREST),rect[:2])
        if aux is not None:aux.paste(auxiliary_image(crop_layer(normal,source["_rects"][index],source["_color"].size).resize(extent,Image.Resampling.NEAREST)),rect[:2])
    return mers,aux,{"mode":"policy-frame-index" if frame_index_policy else "static-source-repeat" if len(source_frames)==1 and len(mapping)>1 else "nearest-rgb-source-frame","frames":mapping}


def registry_bytes(entries):
    rows=["// Generated by scripts/import-vanilla-pbr.py; runtime performs no RGB matching.","#[rustfmt::skip]","static PRESETS: [Entry; "+str(len(entries))+"] = ["]
    for e in entries:
        aux='Some(include_bytes!("'+e["auxiliary"]+'"))' if e["auxiliary"] else "None"
        rows.append('    Entry::new("'+e["java_id"]+'", '+str(e["game_version"])+', ['+str(e["extent"][0])+', '+str(e["extent"][1])+'], include_bytes!("'+e["mers"]+'"), '+aux+', '+str(e["authored_normal"]).lower()+'),')
    return ("\n".join(rows)+"\n];\n").encode()


def write_outputs(output,generated,replace=False,old_manifest=None):
    old={e[role]:e["generated_baseline"].get(role+"_rgba_sha256",e["generated_baseline"].get("rgba_sha256")) for e in (old_manifest or {}).get("entries",[]) for role in ("mers","auxiliary") if e.get(role)}
    preserved=[]
    # Validate existing artistic assets before writing any replacement metadata.
    for relative,data in generated.items():
        path=output/relative
        if relative.endswith(".png") and path.exists() and not replace:
            current=png_rgba8(path.read_bytes())
            if old.get(relative) is None or sha256(current.tobytes())!=old[relative]:
                if current.size!=png_rgba8(data).size:raise ValueError("Edited PNG extent differs: "+relative)
                preserved.append(relative)
    for relative,data in generated.items():
        if relative in preserved:continue
        path=output/relative
        if path.exists() and path.read_bytes()==data:continue
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    return preserved


def generate(source,java_jars,output,replace=False,policy_path=DEFAULT_POLICY):
    source=source.resolve();verify_checkout(source);policy=read_json(policy_path)
    sources,frames=sources_inventory(source,policy);matcher=Matcher(sources,frames)
    java={version:java_inventory(path,version,policy["enabled_atlases"]) for version,path in java_jars.items()}
    results=[]
    for version,targets in java.items():
        for target_index,target in enumerate(targets):
            if target_index%500==0:print("Java "+version+" scan "+str(target_index)+"/"+str(len(targets)),flush=True)
            if "_image" not in target:continue
            if target["java_id"] in policy.get("exclude_java",{}):
                target.update(status="policy-excluded",reason=policy["exclude_java"][target["java_id"]]);continue
            candidates=matcher.match(target,policy)
            target["candidates"]=[candidate_record(c) for c in candidates[:5]];target["candidate_count"]=len(candidates)
            if target["status"]=="runtime-deferred":
                distinct={c["source"]["id"]:c["source"] for c in candidates}
                for candidate_source in distinct.values():candidate_source["deferred_candidates_for"].append(version+":"+target["java_id"])
                continue
            if not candidates:
                target["reason"]="No exact RGB/frame candidate or spatial metric gate accepted";continue
            winner=candidates[0];target.update(status="integrated",winner=candidate_record(winner))
            winner["source"]["selected_by"].append(version+":"+target["java_id"])
            distinct={c["source"]["id"]:c["source"] for c in candidates[1:] if c["source"]["id"]!=winner["source"]["id"]}
            for candidate_source in distinct.values():candidate_source["alternatives_for"].append(version+":"+target["java_id"])
            mers,aux,projection=project_material(target,winner)
            results.append({"java_id":target["java_id"],"game_version":target["game_version"],"extent":target["extent"],"frame_extent":target["frame_extent"],"animation":target["animation"],"base":"host","authored_normal":aux is not None,
                            "match":candidate_record(winner),"projection":projection,"source":winner["source"],"_mers_png":encode_png(mers),"_aux_png":encode_png(aux) if aux else None})
        print("Java "+version+": "+str(sum(t["status"]=="integrated" for t in targets))+" integrated",flush=True)
    for s in sources:
        if s["status"]!="candidate":continue
        if s["selected_by"]:s["status"]="integrated"
        elif s["alternatives_for"]:s.update(status="conflict-loser",reason="Accepted RGB correspondence lost deterministic target winner selection")
        elif s["deferred_candidates_for"]:s.update(status="runtime-deferred",reason="RGB matches only unactivated atlas or standalone targets without trusted source identity")
        else:s.update(status="mapping-low-confidence",reason="No accepted RGB/frame spatial correspondence to any Java texture")
    groups={}
    for e in results:groups.setdefault(e["java_id"],[]).append(e)
    entries=[];generated={}
    for identifier,variants in sorted(groups.items()):
        if len(variants)==2 and all(variants[0][k]==variants[1][k] for k in ("extent","frame_extent","_mers_png","_aux_png","authored_normal")):
            common=variants[0]
            common["version_matches"]={str(v["game_version"]):{"match":v["match"],"projection":v["projection"],"animation":v["animation"]} for v in variants}
            common["game_version"]=0;variants=[common]
        for e in variants:
            prefix=identifier.replace(":","/")+('/'+str(e["game_version"]) if e["game_version"] else '')
            e["mers"]=prefix+"/mers.png";e["auxiliary"]=prefix+"/auxiliary.png" if e["_aux_png"] else None
            baseline={}
            for role,blob in (("mers",e["_mers_png"]),("auxiliary",e["_aux_png"])):
                if blob:
                    generated[e[role]]=blob;baseline[role+"_png_sha256"]=sha256(blob);baseline[role+"_rgba_sha256"]=sha256(png_rgba8(blob).tobytes())
            e["generated_baseline"]=baseline
            e["source"]={k:v for k,v in public(e["source"]).items() if k not in ("status","selected_by","alternatives_for","deferred_candidates_for")}
            entries.append(public(e))
    entries.sort(key=lambda e:(e["java_id"],e["game_version"]))
    summary={"source_sets":len(sources),"registry_entries":len(entries),"common_entries":sum(e["game_version"]==0 for e in entries),"png_files":len(generated),"png_bytes":sum(len(v) for v in generated.values()),
             "decoded_rgba8_bytes":sum(png_rgba8(v).width*png_rgba8(v).height*4 for v in generated.values()),
             "versions":{v:{"textures":len(ts),"eligible":sum(t["atlas"] in policy["enabled_atlases"] for t in ts),"integrated":sum(t["status"]=="integrated" for t in ts),"unmatched":sum(t["status"]=="unmatched" for t in ts),"runtime_deferred":sum(t["status"]=="runtime-deferred" for t in ts),"by_method":{m:sum(t.get("winner",{}).get("method")==m for t in ts) for m in ("exact-sheet-rgb","exact-frame-rgb","resampled-frame-rgb","fuzzy-spatial-rgb","policy-forced")}} for v,ts in java.items()},
             "bedrock_status":{status:sum(s["status"]==status for s in sources) for status in sorted({s["status"] for s in sources})}}
    report={"schema":1,"settings":SETTINGS,"policy":policy,"summary":summary,"bedrock":[public(s) for s in sources],"java":{v:[public(t) for t in ts] for v,ts in java.items()}}
    add_generated_sprite_metadata(report,java_jars)
    review_metadata(report)
    manifest={"schema":1,"name":"vanilla-bedrock-reference","default_auxiliary_rgba":[128,128,0,85],"entries":entries}
    lock={"schema":1,"repository":"https://github.com/Mojang/bedrock-samples","commit":SOURCE_COMMIT,"version":SOURCE_VERSION,"tool":"scripts/import-vanilla-pbr.py","pillow_version":pillow_version,"numpy_version":np.__version__,"zlib_version":zlib.ZLIB_RUNTIME_VERSION,"policy_sha256":sha256(policy_path.read_bytes()),"java_clients":{v:{"version":v,"sha256":sha256(p.read_bytes())} for v,p in java_jars.items()},"settings":SETTINGS,"license":{"notice":"SOURCE-LICENSE.md","sha256":sha256((source/"LICENSE.md").read_bytes())}}
    generated.update({"manifest.json":json_bytes(manifest),"source.lock.json":json_bytes(lock),"registry.rs":registry_bytes(entries),"matching-report.json":json_bytes(report,True),"SOURCE-LICENSE.md":(source/"LICENSE.md").read_bytes().replace(b"\r\n",b"\n")})
    old=read_json(output/"manifest.json") if (output/"manifest.json").exists() else None
    preserved=write_outputs(output,generated,replace,old)
    summary["preserved_artist_pngs"]=preserved
    summary["referenced_png_bytes"]=sum((output/e[role]).stat().st_size for e in entries for role in ("mers","auxiliary") if e[role])
    (output/"matching-report.json").write_bytes(json_bytes(report,True))
    write_summary(output,summary,report)
    write_unintegrated(output,report)
    return verify_assets(output)


def verify_assets(output):
    manifest=read_json(output/"manifest.json");report=read_json(output/"matching-report.json")
    if manifest["schema"]!=1 or report["schema"]!=1:raise ValueError("Unsupported internal schema")
    entries=manifest["entries"];identities=[(e["java_id"],e["game_version"]) for e in entries]
    if identities!=sorted(set(identities)):raise ValueError("Registry identities must be sorted and unique")
    if (output/"registry.rs").read_bytes()!=registry_bytes(entries):raise ValueError("Registry/manifest mismatch")
    if len(report["bedrock"])!=report["summary"]["source_sets"] or len(entries)!=report["summary"]["registry_entries"]:raise ValueError("Incomplete matching report")
    source_ids=[s["id"] for s in report["bedrock"]]
    if len(source_ids)!=len(set(source_ids)):raise ValueError("Duplicate Bedrock inventory IDs")
    for version,records in report["java"].items():
        if len(records)!=report["summary"]["versions"][version]["textures"]:raise ValueError("Incomplete Java inventory")
        for record in records:
            if record["status"]!="integrated" and not record.get("reason"):raise ValueError("Missing unintegrated Java reason")
            if record["status"]=="integrated" and not any(e["java_id"]==record["java_id"] and e["game_version"] in (0,int(version.replace(".",""))) for e in entries):raise ValueError("Integrated Java decision absent from registry")
    for version,records in report.get("generated_java_sprites",{}).items():
        if len(records)!=report["summary"]["versions"][version]["generated_palette_sprites_deferred"] or any(r["status"]!="runtime-deferred" or not r.get("reason") for r in records):raise ValueError("Incomplete generated sprite report")
    if any(s["status"]!="integrated" and not s.get("reason") for s in report["bedrock"]):raise ValueError("Missing unused Bedrock reason")
    edits=[];count=0
    for e in entries:
        if e["base"]!="host":raise ValueError("Host base alpha/tint must be retained")
        for role in ("mers","auxiliary"):
            if e[role] is None:continue
            image=png_rgba8((output/e[role]).read_bytes());count+=1
            if image.size!=tuple(e["extent"]):raise ValueError("PNG/registry extent mismatch: "+e[role])
            if sha256(image.tobytes())!=e["generated_baseline"][role+"_rgba_sha256"]:edits.append(e[role])
    return {"schema":1,"entries":len(entries),"images":count,"artist_edited":edits,"coverage":report["summary"]}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source",nargs="?",type=Path);parser.add_argument("--java-26-2",type=Path);parser.add_argument("--java-26-3",type=Path)
    parser.add_argument("--output",type=Path,default=DEFAULT_OUTPUT);parser.add_argument("--policy",type=Path,default=DEFAULT_POLICY)
    parser.add_argument("--check",action="store_true");parser.add_argument("--replace-art",action="store_true")
    args=parser.parse_args()
    if args.check:result=verify_assets(args.output)
    else:
        if args.source is None or args.java_26_2 is None or args.java_26_3 is None:parser.error("Need pinned reference and both Java client jars")
        result=generate(args.source,{"26.2":args.java_26_2,"26.3":args.java_26_3},args.output,args.replace_art,args.policy)
    print(json.dumps(result,indent=2))


if __name__=="__main__":main()
