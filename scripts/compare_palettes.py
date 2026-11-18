import os
import re
import sys

HTML_PATH = r"C:\Users\Amal\.gemini\antigravity\brain\24edfc6f-11e7-41f5-96f3-c41eca888903\.user_uploaded\media_1794882009022_de6d7ed6.html"
KOTLIN_MODELS = r"app\src\main\java\com\geekify\android\ui\ascii\AsciiAnimationModels.kt"
KOTLIN_CREATURES = r"app\src\main\java\com\geekify\android\ui\ascii\AsciiCreaturesPart1.kt"
KOTLIN_SCENES = r"app\src\main\java\com\geekify\android\ui\ascii\AsciiScenes.kt"

TARGET_ANIMALS = [
    "butterflies",
    "koi",
    "jellyfish",
    "frogs",
    "cranes",
    "crabs",
    "whale",
    "rain",
    "snowfall",
    "waves",
    "aurora"
]

def parse_html_catalog(html_text):
    catalog = {}
    for aid in TARGET_ANIMALS:
        pattern = re.compile(
            rf"var\s+{aid}\s*=\s*\{{.*?swatch:\s*\[(\d+),\s*(\d+),\s*(\d+)\].*?dark:\s*\[(\d+),\s*(\d+),\s*(\d+)\]",
            re.DOTALL
        )
        m = pattern.search(html_text)
        if m:
            swatch = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
            dark = (int(m.group(4)), int(m.group(5)), int(m.group(6)))
            catalog[aid] = {"swatch": swatch, "trailDark": dark}
        else:
            pattern2 = re.compile(
                rf"key:\s*['\"]{aid}['\"].*?swatch:\s*\[(\d+),\s*(\d+),\s*(\d+)\].*?dark:\s*\[(\d+),\s*(\d+),\s*(\d+)\]",
                re.DOTALL
            )
            m2 = pattern2.search(html_text)
            if m2:
                swatch = (int(m2.group(1)), int(m2.group(2)), int(m2.group(3)))
                dark = (int(m2.group(4)), int(m2.group(5)), int(m2.group(6)))
                catalog[aid] = {"swatch": swatch, "trailDark": dark}
    return catalog

def parse_kotlin_models(kt_text):
    catalog = {}
    pattern = re.compile(
        r"id\s*=\s*['\"](\w+)['\"].*?"
        r"swatchR\s*=\s*(\d+),\s*swatchG\s*=\s*(\d+),\s*swatchB\s*=\s*(\d+).*?"
        r"trailDarkR\s*=\s*(\d+),\s*trailDarkG\s*=\s*(\d+),\s*trailDarkB\s*=\s*(\d+)",
        re.DOTALL
    )
    for m in pattern.finditer(kt_text):
        animal_id = m.group(1)
        if animal_id in TARGET_ANIMALS:
            swatch = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
            trail_dark = (int(m.group(5)), int(m.group(6)), int(m.group(7)))
            catalog[animal_id] = {"swatch": swatch, "trailDark": trail_dark}
    return catalog

def extract_animal_section(html_text, aid):
    # Locate src/animals/<aid>.ts or src/scenes/<aid>.ts
    for prefix in [f"src/animals/{aid}.ts", f"src/scenes/{aid}.ts"]:
        pos = html_text.find(prefix)
        if pos != -1:
            next_mod = html_text.find("// src/", pos + 10)
            end_pos = next_mod if next_mod != -1 else pos + 10000
            return html_text[pos:end_pos]
    return ""

def extract_kotlin_section(kt_text, aid):
    # Locate section for aid
    pos = kt_text.lower().find(aid.lower())
    if pos != -1:
        # get 200 lines around it
        start = max(0, pos - 500)
        end = min(len(kt_text), pos + 5000)
        return kt_text[start:end]
    return ""

def compare_animal_colors(aid, html_snippet, kt_snippet):
    print(f"\n{'='*20} {aid.upper()} {'='*20}")
    # Extract ink(...) calls from HTML
    html_inks = re.findall(r'ink\([^,]+,\s*([^)]+)\)', html_snippet)
    print(f"[HTML Inks ({len(html_inks)})]:")
    for ink in set(html_inks):
        print(f"  ink({ink})")

    # Extract pen.ink.set(...) calls from Kotlin
    kt_inks = re.findall(r'pen\.ink\.set\(([^)]+)\)', kt_snippet)
    print(f"[Kotlin Inks ({len(kt_inks)})]:")
    for ink in set(kt_inks):
        print(f"  pen.ink.set({ink})")

def main():
    if not os.path.exists(HTML_PATH):
        print(f"Error: HTML not found at {HTML_PATH}")
        return 1

    with open(HTML_PATH, "r", encoding="utf-8") as f:
        html_text = f.read()

    with open(KOTLIN_MODELS, "r", encoding="utf-8") as f:
        kt_models_text = f.read()

    with open(KOTLIN_CREATURES, "r", encoding="utf-8") as f:
        kt_creatures_text = f.read()

    with open(KOTLIN_SCENES, "r", encoding="utf-8") as f:
        kt_scenes_text = f.read()

    html_cat = parse_html_catalog(html_text)
    kt_cat = parse_kotlin_models(kt_models_text)

    print("=" * 60)
    print("1. CATALOG SWATCH & TRAIL DARK AUDIT (11 ANIMALS)")
    print("=" * 60)
    mismatches = 0
    for aid in TARGET_ANIMALS:
        h = html_cat.get(aid)
        k = kt_cat.get(aid)
        if not h or not k:
            print(f"[-] {aid}: Missing from catalog")
            mismatches += 1
            continue
        sm = (h["swatch"] == k["swatch"])
        tm = (h["trailDark"] == k["trailDark"])
        st = "MATCH" if (sm and tm) else "MISMATCH"
        if st == "MISMATCH":
            mismatches += 1
        print(f"[{st}] {aid:12} Swatch: HTML={h['swatch']} KT={k['swatch']} | TrailDark: HTML={h['trailDark']} KT={k['trailDark']}")

    print(f"\nCatalog Swatch Mismatches: {mismatches}")

    print("\n" + "=" * 60)
    print("2. DETAILED LITERAL & COMPUTED COLOR AUDIT (11 ANIMALS)")
    print("=" * 60)
    for aid in TARGET_ANIMALS:
        html_sec = extract_animal_section(html_text, aid)
        kt_sec = extract_kotlin_section(kt_creatures_text if aid not in ["rain", "snowfall", "waves", "aurora"] else kt_scenes_text, aid)
        compare_animal_colors(aid, html_sec, kt_sec)

    return mismatches

if __name__ == "__main__":
    sys.exit(main())
