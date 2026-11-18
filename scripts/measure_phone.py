import subprocess
import time
import os
from PIL import Image

ADB = os.path.expandvars(r"$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
DEVICE = "RFGYC1RM4CL"
OUT_DIR = r"C:\Users\Amal\.gemini\antigravity\brain\24edfc6f-11e7-41f5-96f3-c41eca888903"

CALIBRATION_COLORS = [
    ("Pure Red", (255, 0, 0)),
    ("Pure Green", (0, 255, 0)),
    ("Pure Blue", (0, 0, 255)),
    ("Butterfly", (50, 135, 255)),
    ("Crab", (204, 82, 52)),
    ("Koi", (244, 196, 44)),
    ("Aurora", (64, 242, 168)),
]

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

def adb_cmd(args):
    cmd = [ADB, "-s", DEVICE] + args
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

def screencap(dest):
    temp = "/sdcard/screen_phone_temp.png"
    adb_cmd(["shell", "screencap", "-p", temp])
    adb_cmd(["pull", temp, dest])

def measure_calibration_bands(img_path):
    im = Image.open(img_path).convert("RGB")
    width, height = im.size
    
    # In calibration mode, rows are partitioned into 7 bands across the top 75% of height (y=0 to y ~ height*0.75).
    # Height = 2340, top 75% is y = 0 to 1755.
    # Each band has height = 1755 / 7 = 250 px.
    # We sample the left half (x ~ 100 to 250) at the center of each band.
    
    usable_h = height * 0.75
    band_h = usable_h / 7.0
    x_solid = int(width * 0.2)
    
    measured = []
    for i, (name, exp_rgb) in enumerate(CALIBRATION_COLORS):
        y_center = int((i + 0.5) * band_h)
        # Find exact matches in a window around (x_solid, y_center)
        r_sum, g_sum, b_sum = 0, 0, 0
        cnt = 0
        exact_found = 0
        for dy in range(-30, 31, 3):
            for dx in range(-30, 31, 3):
                px = im.getpixel((x_solid + dx, y_center + dy))
                # Ignore black grid lines (0,0,0)
                if max(px) > 10:
                    r_sum += px[0]
                    g_sum += px[1]
                    b_sum += px[2]
                    cnt += 1
                    if px == exp_rgb:
                        exact_found += 1
                        
        if cnt > 0:
            avg_rgb = (int(round(r_sum / cnt)), int(round(g_sum / cnt)), int(round(b_sum / cnt)))
        else:
            avg_rgb = (0, 0, 0)
        measured.append((name, exp_rgb, avg_rgb, exact_found))
    return measured

def measure_animal_dominant(img_path, expected_rgb):
    im = Image.open(img_path).convert("RGB")
    width, height = im.size
    exp_r, exp_g, exp_b = expected_rgb
    
    y_start = int(height * 0.20)
    y_end = int(height * 0.70)
    x_start = int(width * 0.10)
    x_end = int(width * 0.90)
    
    pixel_candidates = []
    for y in range(y_start, y_end, 4):
        for x in range(x_start, x_end, 4):
            r, g, b = im.getpixel((x, y))
            # Ignore black background
            if max(r, g, b) < 40:
                continue
            # Ignore UI gray
            if abs(r - g) < 8 and abs(g - b) < 8 and r > 180:
                continue
            pixel_candidates.append((r, g, b))
            
    if not pixel_candidates:
        return (0, 0, 0)

    # Sort by proximity to expected swatch
    candidates_sorted = sorted(pixel_candidates, key=lambda p: ((p[0] - exp_r)**2 + (p[1] - exp_g)**2 + (p[2] - exp_b)**2))
    top = candidates_sorted[:30]
    avg_r = int(sum(p[0] for p in top) / len(top))
    avg_g = int(sum(p[1] for p in top) / len(top))
    avg_b = int(sum(p[2] for p in top) / len(top))
    return (avg_r, avg_g, avg_b)

def main():
    print("=== MEASURING CALIBRATION ON USER PHONE (SM_A366B, Android 17 / API 37) ===")
    
    # Ensure Search route is open so screen is clean
    adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "route", "search", "--user", "0"])
    time.sleep(1.0)
    
    # 1. CPU Calibration
    print("\n--- Triggering CPU Calibration ---")
    adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "ascii_calibrate", "cpu", "--es", "route", "search", "--user", "0"])
    time.sleep(2.0)
    cpu_calib_img = os.path.join(OUT_DIR, "phone_calib_cpu.png")
    screencap(cpu_calib_img)
    cpu_bands = measure_calibration_bands(cpu_calib_img)
    print("CPU Calibration Results:")
    for name, exp, act, exact in cpu_bands:
        status = "EXACT MATCH" if exp == act else f"AVG={act} (Exact samples={exact})"
        print(f"  {name:12}: Expected={exp} Measured={act} -> {status}")

    # 2. GPU Calibration
    print("\n--- Triggering GPU Calibration ---")
    adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "ascii_calibrate", "gpu", "--es", "route", "search", "--user", "0"])
    time.sleep(2.0)
    gpu_calib_img = os.path.join(OUT_DIR, "phone_calib_gpu.png")
    screencap(gpu_calib_img)
    gpu_bands = measure_calibration_bands(gpu_calib_img)
    print("GPU Calibration Results:")
    for name, exp, act, exact in gpu_bands:
        status = "EXACT MATCH" if exp == act else f"AVG={act} (Exact samples={exact})"
        print(f"  {name:12}: Expected={exp} Measured={act} -> {status}")

    # Turn off calibration grid
    adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "ascii_calibrate", "off", "--es", "route", "search", "--user", "0"])
    time.sleep(1.0)

    # 3. Measure Animals on CPU and GPU paths
    print("\n=== MEASURING ALL 11 ANIMALS ON USER PHONE (SM_A366B, Android 17 / API 37) ===")
    for path in ["cpu", "gpu"]:
        print(f"\n--- Testing Path: {path.upper()} ---")
        adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "ascii_path", path, "--es", "route", "search", "--user", "0"])
        time.sleep(1.0)
        
        for animal, swatch in ANIMALS:
            adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "ascii_animal", animal, "--es", "route", "search", "--user", "0"])
            time.sleep(1.2)
            img_name = f"phone_{animal}_{path}.png"
            dest = os.path.join(OUT_DIR, img_name)
            screencap(dest)
            dom_rgb = measure_animal_dominant(dest, swatch)
            
            exp_r, exp_g, exp_b = swatch
            dist_correct = ((dom_rgb[0] - exp_r)**2 + (dom_rgb[1] - exp_g)**2 + (dom_rgb[2] - exp_b)**2)**0.5
            dist_swapped = ((dom_rgb[0] - exp_b)**2 + (dom_rgb[1] - exp_g)**2 + (dom_rgb[2] - exp_r)**2)**0.5
            is_swapped = dist_swapped < dist_correct
            swap_str = "SWAPPED (WRONG)" if is_swapped else "CORRECT"
            print(f"  {animal:12}: Swatch={swatch} Measured={dom_rgb} -> {swap_str}")

    # Reset
    adb_cmd(["shell", "am", "start", "-n", "com.geekify.android/.MainActivity", "--es", "ascii_animal", "off", "--es", "ascii_path", "off", "--user", "0"])
    print("\nFinished phone measurements.")

if __name__ == "__main__":
    main()
