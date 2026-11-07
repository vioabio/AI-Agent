#!/usr/bin/env python3
"""Convert Pokemon JSON data to RAG-friendly Markdown documents."""
import json, os

SRC = r"C:\Users\vivin\Downloads\s-pokemon-main\s-pokemon-main"
DST = r"D:\CSTP\vio-ai-agent\document"

os.makedirs(DST, exist_ok=True)

# ─── 1. Pokedex (full + gen9) ───
with open(f"{SRC}/dark/pokedex_full.json", "r", encoding="utf-8") as f:
    dex = json.load(f)
with open(f"{SRC}/gen9/pokedex_gen9.json", "r", encoding="utf-8") as f:
    gen9 = json.load(f)

all_mon = dex + gen9
TYPE_CN = {"Normal":"一般","Fire":"火","Water":"水","Electric":"电","Grass":"草","Ice":"冰","Fighting":"格斗","Poison":"毒","Ground":"地面","Flying":"飞行","Psychic":"超能力","Bug":"虫","Rock":"岩石","Ghost":"幽灵","Dragon":"龙","Dark":"恶","Steel":"钢","Fairy":"妖精","???":"???"}

lines = ["# 宝可梦图鉴大全\n"]
for m in all_mon:
    name = m["name"]["chinese"]
    types = " / ".join(TYPE_CN.get(t,t) for t in m["type"])
    b = m["base"]
    stats = f"HP:{b['HP']} 攻击:{b['Attack']} 防御:{b['Defense']} 特攻:{b['Sp. Attack']} 特防:{b['Sp. Defense']} 速度:{b['Speed']}"
    desc = m.get("description", "")
    lines.append(f"### #{m['id']} {name}\n"
                 f"**属性**: {types}  \n"
                 f"**种族值**: {stats}  \n"
                 f"**图鉴描述**: {desc}\n"
                 f"---\n")

with open(f"{DST}/宝可梦图鉴大全.md", "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print(f"Pokedex: {len(all_mon)} Pokemon → 宝可梦图鉴大全.md")

# ─── 2. Moves ───
with open(f"{SRC}/dark/moves.json", "r", encoding="utf-8") as f:
    moves = json.load(f)

lines = ["# 宝可梦对战技能库\n"]
for mv in moves:
    name = mv["name"]["chinese"]
    tp = TYPE_CN.get(mv.get("type",""), mv.get("type",""))
    cat = mv.get("category","")
    power = mv.get("power","—")
    acc = mv.get("accuracy","—")
    pp = mv.get("pp","—")
    desc = mv.get("description","")
    lines.append(f"### {name}\n"
                 f"**属性**: {tp} | **类别**: {cat} | **威力**: {power} | **命中**: {acc} | **PP**: {pp}\n"
                 f"**效果**: {desc}\n"
                 f"---\n")

with open(f"{DST}/宝可梦对战技能库.md", "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print(f"Moves: {len(moves)} skills → 宝可梦对战技能库.md")

# ─── 3. Evolution + Meta ───
with open(f"{SRC}/dark/evolution.json", "r", encoding="utf-8") as f:
    evo = json.load(f)
with open(f"{SRC}/dark/pokedex_dark_meta.json", "r", encoding="utf-8") as f:
    meta = json.load(f)

# Build name lookup from dex
name_map = {}
for m in dex:
    name_map[str(m["id"])] = m["name"]["chinese"]

lines = ["# 宝可梦养成与进化\n"]

# Evolution section
lines.append("## 进化链\n")
for pid, info in evo.items():
    src = name_map.get(pid, f"#{pid}")
    dst = name_map.get(str(info["evolves_to"]), f"#{info['evolves_to']}")
    method = info["method"]
    lines.append(f"- **{src}** → **{dst}**: {method}\n")

# Capture guide section
lines.append("\n## 稀有宝可梦获取攻略\n")
for pid, info in meta.items():
    lines.append(f"### #{pid} {name_map.get(pid, '')} ({info.get('category','')})\n"
                 f"- **地点**: {info.get('obtainLocation','未知')}\n"
                 f"- **条件**: {info.get('obtainRequirements','无特殊条件')}\n"
                 f"- **方法**: {info.get('obtainMethod','')}\n"
                 f"---\n")

with open(f"{DST}/宝可梦养成与进化.md", "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print(f"Evolution: {len(evo)} chains + {len(meta)} meta → 宝可梦养成与进化.md")
print("\nDone! 3 knowledge base documents generated.")
