import subprocess
import time
import os
from PIL import Image

ADB = os.path.expandvars(r"$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
DEVICE = "emulator-5554"
OUT_DIR = r"C:\Users\Amal\.gemini\antigravity\brain\24edfc6f-11e7-41f5-96f3-c41eca888903"

ANIMALS = [
    ("butterflies", (50, 135, 255)),
    ("koi", (244, 196, 44)),
    ("jellyfish", (232, 56, 182)),
    ("frogs", (104, 184, 76)),
    ("cranes", (214, 220, 238)),
    ("crabs", (204, 82, 52)),
    ("whale", (78, 168, 238)),
    ("rain", (94, 196, 255)),
    ("snowfall", (214, 236, 255)),
    ("waves", (56, 178, 232)),
    ("aurora", (64, 242, 168)),
]

def adb_command(args):
    cmd = [ADB, "-s", DEVICE] + args
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

def capture_screenshot(dest_path):
    temp_device = "/sdcard/screen_temp.png"
    adb_command(["shell", "screencap", "-p", temp_device])
    adb_command(["pull", temp_device, dest_path])

def get_dominant_creature_color(img_path, expected_rgb):
    im = Image.open(img_path).convert("RGB")
    width, height = im.size
    
    # We examine the central canvas area (exclude top status bar 0-150px and bottom nav/player 1200-1600px)
    # Also background in Geekify is dark/black (R,G,B < 30)
    # Creature cells have distinct brightness.
    # We find non-black pixels and look for the ones closest to the expected hue/swatch.
    # Or find clusters of active colored cells.
    
    exp_r, exp_g, exp_b = expected_rgb
    
    # Collect non-black pixels in simulation region:
    # Tablet is 1600x2560 or similar; let's inspect actual dimensions
    # Exclude top 15% and bottom 25% to avoid UI
    y_start = int(height * 0.15)
    y_end = int(height * 0.75)
    x_start = int(width * 0.05)
    x_end = int(width * 0.95)
    
    pixel_candidates = []
    
    for y in range(y_start, y_end, 4): # sample grid
        for x in range(x_start, x_end, 4):
            r, g, b = im.getpixel((x, y))
            # ignore background / dark pixels
            if max(r, g, b) < 40:
                continue
            # ignore pure white text from headers if any
            if abs(r - g) < 10 and abs(g - b) < 10 and r > 200:
                continue
            pixel_candidates.append((r, g, b))
            
    if not pixel_candidates:
        # Fallback to any colored pixel
        for y in range(0, height, 4):
            for x in range(0, width, 4):
                r, g, b = im.getpixel((x, y))
                if max(r, g, b) > 50:
                    pixel_candidates.append((r, g, b))

    if not pixel_candidates:
        return (0, 0, 0), "None found"

    # Find the pixel with maximum saturation / color match to expected swatch or most prominent hue
    # To detect red/blue swap: compare distance to (exp_r, exp_g, exp_b) vs distance to (exp_b, exp_g, exp_r)
    best_pixel = None
    min_dist = float('inf')
    
    # Score candidates: we want pixels that represent the creature body
    # Distance to expected swatch:
    for p in pixel_candidates:
        # Euclidean distance in RGB space to expected swatch
        dist = ((p[0] - exp_r)**2 + (p[1] - exp_g)**2 + (p[2] - exp_b)**2)**0.5
        if dist < min_dist:
            min_dist = dist
            best_pixel = p
            
    # Also find average of the top 20 closest pixels to smooth out font anti-aliasing
    candidates_sorted = sorted(pixel_candidates, key=lambda p: ((p[0] - exp_r)**2 + (p[1] - exp_g)**2 + (p[2] - exp_b)**2))
    top = candidates_sorted[:20]
    avg_r = int(sum(p[0] for p in top) / len(top))
    avg_g = int(sum(p[1] for p in top) / len(top))
    avg_b = int(sum(p[2] for p in top) / len(top))
    
    # Check if swapped:
    dist_correct = ((avg_r - exp_r)**2 + (avg_g - exp_g)**2 + (avg_b - exp_b)**2)**0.5
    dist_swapped = ((avg_r - exp_b)**2 + (avg_g - exp_g)**2 + (avg_b - exp_r)**2)**0.5
    
    status = "CORRECT" if dist_correct < dist_swapped else "SWAPPED (RED/BLUE)"
    return (avg_r, avg_g, avg_b), status

print("Starting animal capture and color verification...")
results = []

for animal, expected in ANIMALS:
    print(f"\nTesting {animal} (Expected swatch: {expected})...")
    # 1. CPU
    adb_command(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity",
                 "--es", "ascii_calibrate", "off",
                 "--es", "ascii_path", "cpu",
                 "--es", "ascii_animal", animal])
    time.sleep(2.0)
    cpu_path = os.path.join(OUT_DIR, f"{animal}_cpu.png")
    capture_screenshot(cpu_path)
    cpu_color, cpu_status = get_dominant_creature_color(cpu_path, expected)
    print(f"  CPU: Measured={cpu_color}, Status={cpu_status}")

    # 2. GPU
    adb_command(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity",
                 "--es", "ascii_calibrate", "off",
                 "--es", "ascii_path", "gpu",
                 "--es", "ascii_animal", animal])
    time.sleep(2.0)
    gpu_path = os.path.join(OUT_DIR, f"{animal}_gpu.png")
    capture_screenshot(gpu_path)
    gpu_color, gpu_status = get_dominant_creature_color(gpu_path, expected)
    print(f"  GPU: Measured={gpu_color}, Status={gpu_status}")

    results.append({
        "animal": animal,
        "expected": expected,
        "cpu_color": cpu_color,
        "cpu_status": cpu_status,
        "gpu_color": gpu_color,
        "gpu_status": gpu_status
    })

print("\n" + "="*80)
print(f"{'Animal':<12} | {'Expected Swatch':<18} | {'CPU Measured':<18} | {'GPU Measured':<18} | {'Status'}")
print("="*80)
for r in results:
    exp_str = f"({r['expected'][0]},{r['expected'][1]},{r['expected'][2]})"
    cpu_str = f"({r['cpu_color'][0]},{r['cpu_color'][1]},{r['cpu_color'][2]})"
    gpu_str = f"({r['gpu_color'][0]},{r['gpu_color'][1]},{r['gpu_color'][2]})"
    print(f"{r['animal']:<12} | {exp_str:<18} | {cpu_str:<18} | {gpu_str:<18} | {r['cpu_status']}/{r['gpu_status']}")
