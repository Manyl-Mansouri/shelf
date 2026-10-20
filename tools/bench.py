#!/usr/bin/env python3
"""
Frame-pacing benchmark for Shelf on a real TV.

Runs fixed remote-control sequences several times, reads Android's per-frame timing
(dumpsys gfxinfo framestats) after each, and reports how evenly frames arrived.

  python3 tools/bench.py [--serial <tv-ip>:5555] [--reps 3] [--label before]

Without --serial it uses $ANDROID_SERIAL, else the only device adb sees.

Jank is Android's own accounting (dumpsys gfxinfo): a frame that missed its display deadline,
counted over every frame drawn. Frame-time percentiles come from the same histogram. Gaps
between frames are not used: a gap after the screen came to rest is idle time, not a stutter.
"""
import argparse
import json
import os
import statistics
import subprocess
import time

PKG = "com.manyl.shelf"

SCENARIOS = {
    # Along the top row: every step swaps the full-screen Top Shelf art.
    "top row": ["RIGHT"] * 4 + ["LEFT"] * 4,
    # Down into the grid and back: the whole screen scrolls and the art turns into the blur.
    "scroll": ["DOWN", "DOWN", "UP", "UP"],
    # Across a lower row: only tiles move.
    "grid": ["DOWN", "RIGHT", "RIGHT", "RIGHT", "LEFT", "LEFT", "LEFT", "UP"],
}


def adb(serial, *args):
    target = ["-s", serial] if serial else []
    return subprocess.run(["adb", *target, *args], capture_output=True, text=True).stdout


def frames(serial):
    out = adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "framestats")
    header, rows = None, []
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Flags,"):
            header = line.split(",")
        elif header and line[:1].isdigit():
            values = line.split(",")
            if len(values) >= len(header) - 1:
                row = dict(zip(header, (int(v) if v.lstrip("-").isdigit() else 0 for v in values)))
                if row.get("Flags") == 0:
                    rows.append(row)
    return sorted(rows, key=lambda r: r["FrameCompleted"])


def summary(serial):
    out = adb(serial, "shell", "dumpsys", "gfxinfo", PKG)
    stats, hist = {}, {}
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Total frames rendered:"):
            stats["total"] = int(line.split(":")[1])
        elif line.startswith("Number Frame deadline missed:"):
            stats["missed"] = int(line.split(":")[1])
        elif line.startswith("Number Slow UI thread:"):
            stats["slow_ui"] = int(line.split(":")[1])
        elif line.startswith("Number Slow bitmap uploads:"):
            stats["slow_upload"] = int(line.split(":")[1])
        elif line.startswith("Number Slow issue draw commands:"):
            stats["slow_draw"] = int(line.split(":")[1])
        elif line.startswith("HISTOGRAM:"):
            for kv in line.split(":", 1)[1].split():
                ms, n = kv.rstrip("ms").split("ms=") if "ms=" in kv else (None, None)
                if ms:
                    hist[int(ms)] = hist.get(int(ms), 0) + int(n)
            break
    return stats, hist


def run(serial, keys, gap):
    adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "reset")
    for k in keys:
        adb(serial, "shell", "input", "keyevent", f"KEYCODE_DPAD_{k}")
        time.sleep(gap)
    time.sleep(1.0)
    return frames(serial), summary(serial)


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))] if xs else 0.0


def hist_pct(hist, p):
    total = sum(hist.values())
    seen = 0
    for ms in sorted(hist):
        seen += hist[ms]
        if seen >= total * p:
            return ms
    return 0


def summarize(runs):
    gpu, cpu = [], []
    totals = {"total": 0, "missed": 0, "slow_ui": 0, "slow_upload": 0, "slow_draw": 0}
    hist = {}
    for rows, (stats, h) in runs:
        for k in totals:
            totals[k] += stats.get(k, 0)
        for ms, n in h.items():
            hist[ms] = hist.get(ms, 0) + n
        for r in rows:
            cpu.append((r["SwapBuffers"] - r["HandleInputStart"]) / 1e6)
            if r.get("GpuCompleted", 0) > 0:
                gpu.append((r["GpuCompleted"] - r["SwapBuffers"]) / 1e6)
    return {
        "frames": totals["total"],
        "jank_pct": round(100 * totals["missed"] / max(1, totals["total"]), 1),
        "frame_p50_ms": hist_pct(hist, 0.5),
        "frame_p90_ms": hist_pct(hist, 0.9),
        "frame_p99_ms": hist_pct(hist, 0.99),
        "cpu_p90_ms": round(pct(cpu, 0.9), 1),
        "gpu_p50_ms": round(pct(gpu, 0.5), 1),
        "gpu_p90_ms": round(pct(gpu, 0.9), 1),
        "slow_ui": totals["slow_ui"],
        "slow_uploads": totals["slow_upload"],
        "slow_draw": totals["slow_draw"],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--label", default="run")
    args = ap.parse_args()

    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.5)
    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    results = {}
    for name, keys in SCENARIOS.items():
        runs = [run(args.serial, keys, gap=0.6) for _ in range(args.reps)]
        results[name] = summarize(runs)
    print(json.dumps({"label": args.label, **results}, indent=1))


if __name__ == "__main__":
    main()
