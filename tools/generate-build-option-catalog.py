"""Regenerate the public build-option dictionary from a pinned PokeAPI CSV revision."""
import concurrent.futures
import csv
import hashlib
import io
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
import argparse


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "VioBuildOptions/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", help="Full PokeAPI/pokeapi commit SHA; omit to resolve master once")
    args = parser.parse_args()
    revision = args.revision or json.loads(download("https://api.github.com/repos/PokeAPI/pokeapi/commits/master"))["sha"]
    if len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision):
        raise ValueError("Expected a full commit SHA")
    files = ["languages", "abilities", "ability_names", "moves", "move_names", "items", "item_names", "item_flags", "item_flag_map", "item_categories", "ability_flavor_text", "version_groups"]
    base = f"https://raw.githubusercontent.com/PokeAPI/pokeapi/{revision}/data/v2/csv/"
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        contents = dict(zip(files, pool.map(lambda name: download(base + name + ".csv"), files)))
    tables = {name: list(csv.DictReader(io.StringIO(data.decode("utf-8-sig")))) for name, data in contents.items()}
    zh = next(row["id"] for row in tables["languages"] if row["identifier"] == "zh-hans")
    def names(table, key):
        return {row[key]: row["name"] for row in tables[table] if row["local_language_id"] == zh}
    labels = {"ability": names("ability_names", "ability_id"), "move": names("move_names", "move_id"), "item": names("item_names", "item_id")}
    sv = next(row["id"] for row in tables["version_groups"] if row["identifier"] == "scarlet-violet")
    descriptions = {row["ability_id"]: " ".join(row["flavor_text"].split()) for row in tables["ability_flavor_text"]
                    if row["language_id"] == zh and row["version_group_id"] == sv}
    held = next(row["id"] for row in tables["item_flags"] if row["identifier"] == "holdable")
    held_ids = {row["item_id"] for row in tables["item_flag_map"] if row["item_flag_id"] == held}
    # Newer records sometimes have no flags. Include categories explicitly dedicated
    # to held equipment, without treating key items, machines or materials as gear.
    equipment = {"held-items", "choice", "effort-training", "bad-held-items", "plates", "species-specific", "type-enhancement", "jewels", "mega-stones", "memories"}
    held_categories = {row["id"] for row in tables["item_categories"] if row["identifier"] in equipment}
    types = "normal fighting flying poison ground rock bug ghost steel fire water grass electric psychic ice dragon dark fairy".split()
    records = []
    for kind, table in [("ability", "abilities"), ("move", "moves"), ("item", "items")]:
        for row in tables[table]:
            if kind == "item" and row["id"] not in held_ids and row["category_id"] not in held_categories:
                continue
            value = {"kind": kind, "slug": row["identifier"], "nameZh": labels[kind].get(row["id"], row["identifier"])}
            if kind == "ability" and row["id"] in descriptions:
                value["description"] = descriptions[row["id"]]
            if kind == "move":
                type_id = int(row["type_id"])
                value.update(typeKey=types[type_id-1] if 1 <= type_id <= len(types) else "unknown",
                             damageClass={"1": "status", "2": "physical", "3": "special"}[row["damage_class_id"]],
                             power=int(row["power"]) if row["power"] else None,
                             accuracy=int(row["accuracy"]) if row["accuracy"] else None)
            records.append(value)
    root = Path(__file__).resolve().parents[1] / "src/main/resources/pokemon-build-options"
    root.mkdir(exist_ok=True)
    payload = (json.dumps({"schemaVersion": 1, "records": records}, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    (root / "catalog-v1.json").write_bytes(payload)
    meta = {"revision": revision, "retrievedAt": datetime.now(timezone.utc).isoformat(), "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "scope": "UPSTREAM_REFERENCE_NOT_PATCH_FROZEN", "sources": [{"url": base + name + ".csv", "sha256": "sha256:" + hashlib.sha256(data).hexdigest()} for name, data in contents.items()]}
    (root / "catalog-v1.meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print("Pinned revision:", revision, "records:", len(records), "bytes:", len(payload), flush=True)


if __name__ == "__main__":
    main()
