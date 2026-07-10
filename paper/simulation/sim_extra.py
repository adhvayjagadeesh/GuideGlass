"""
Extended analyses for the GuideGlass/VisionAI Results section.
Builds on the ported decision logic in sim_reflex.py.
"""

import json
import math
import numpy as np
from collections import deque
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import Rectangle

from sim_reflex import (
    CENTER_PATH_MIN_X, CENTER_PATH_MAX_X, MIN_CORRIDOR_OVERLAP, MAX_BOX_WIDTH,
    NEAR_HEIGHT, NEAR_AREA, ENTER_STREAK, CLEAR_STREAK,
    LOOM_WINDOW_MS, MAX_SAMPLES, MIN_LOOM_HEIGHT, MIN_LOOM_AREA,
    LOOM_GROWTH_PER_SEC, TTC_IMMINENT_SEC, STOP_COOLDOWN_MS,
    HINT_CLEAR_OCC, HINT_OCC_DIFF, CENTER_NEUTRAL_MIN, CENTER_NEUTRAL_MAX,
    VFOV, HFOV, FPS, DT, WALK_SPEED, K_V, K_H,
    height_frac, width_frac, is_looming, compute_hint,
    BLUE, AQUA, ORANGE, RED, INK, SEC, MUTED, GRID, BASE, strip_spines,
)

FIGW = 6.3
EXTRA = {}


# ---------------------------------------------------------------- parametrized rule
def frame_danger_p(hf, wf, cx, hist=None, t_ms=None,
                   near_h=NEAR_HEIGHT, near_a=NEAR_AREA,
                   cmin=CENTER_PATH_MIN_X, cmax=CENTER_PATH_MAX_X):
    """frame_danger with tunable thresholds (mirrors evaluatePathObstacle)."""
    if wf >= MAX_BOX_WIDTH:
        return False
    af = hf * wf
    left, right = cx - wf / 2.0, cx + wf / 2.0
    center_in = cmin <= cx <= cmax
    overlap = min(right, cmax) - max(left, cmin)
    overlap_frac = overlap / (cmax - cmin) if cmax > cmin else 0.0
    if not center_in and overlap_frac < MIN_CORRIDOR_OVERLAP:
        return False
    loom = False
    if hist is not None:
        hist.append((t_ms, hf))
        while len(hist) > 1 and t_ms - hist[0][0] > LOOM_WINDOW_MS:
            hist.popleft()
        while len(hist) > MAX_SAMPLES:
            hist.popleft()
        loom = is_looming(hist, hf, af)
    return (hf >= near_h or af >= near_a) or loom


# ================================================================ 1. detection envelope
def detection_envelope(nd=48, nh=40, draws=50, seed=11):
    rng = np.random.default_rng(seed)
    ds = np.linspace(0.6, 6.0, nd)
    hs = np.linspace(0.2, 2.0, nh)
    P = np.zeros((nh, nd))
    n_hist = int(LOOM_WINDOW_MS / 1000.0 * FPS)
    for iy, H in enumerate(hs):
        Wm = 0.4 * H
        for ix, d in enumerate(ds):
            hits = 0
            for _ in range(draws):
                hist = deque()
                danger = False
                for k in range(n_hist + 1):
                    dd = d + WALK_SPEED * (n_hist - k) * DT
                    hf = height_frac(H, dd) * rng.normal(1.0, 0.10)
                    wf = min(1.0, width_frac(Wm, dd) * rng.normal(1.0, 0.10))
                    cx = 0.5 + rng.normal(0.0, 0.03)
                    danger = frame_danger_p(max(1e-4, hf), max(1e-4, wf), cx,
                                            hist, k * DT * 1000.0)
                hits += danger
            P[iy, ix] = hits / draws
    return ds, hs, P


def fig_envelope(path, ds, hs, P):
    cmap = LinearSegmentedColormap.from_list(
        "blues", ["#ffffff", "#cde2fb", "#86b6ef", "#3987e5", "#1c5cab", "#0d366b"])
    fig, ax = plt.subplots(figsize=(FIGW, 3.4))
    pc = ax.pcolormesh(ds, hs, P, cmap=cmap, vmin=0, vmax=1, shading="auto", zorder=2)
    cb = fig.colorbar(pc, ax=ax, pad=0.015)
    cb.set_label("Probability of alert", fontsize=9, color=INK)
    cb.ax.tick_params(labelsize=8, labelcolor=SEC)
    # analytic boundaries
    dd = np.linspace(0.6, 6.0, 200)
    gate = NEAR_HEIGHT * K_V * dd
    keep = gate <= 2.0
    ax.plot(dd[keep], gate[keep], color=INK, lw=1.1, ls=(0, (4, 3)), zorder=4)
    ax.text(3.95, NEAR_HEIGHT * K_V * 3.95 + 0.06, "near-size gate", fontsize=8,
            color=INK, rotation=29)
    d_ttc = WALK_SPEED * TTC_IMMINENT_SEC
    ax.axvline(d_ttc, color="#ffffff", lw=1.1, ls=(0, (1, 2)), zorder=4)
    ax.text(d_ttc - 0.09, 1.95, "time-to-contact radius", fontsize=8,
            color="#ffffff", rotation=90, va="top", zorder=5)
    ax.set_xlabel("Obstacle distance (m)", fontsize=9)
    ax.set_ylabel("Obstacle height (m)", fontsize=9)
    ax.set_ylim(0.2, 2.0)
    ax.grid(visible=False)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ================================================================ 2. warning time vs speed
def fig_speed(path):
    speeds = np.linspace(0.6, 2.0, 100)
    objs = [("Person", 1.70, 0.50, BLUE), ("Trash bin", 1.00, 0.60, AQUA),
            ("Bollard", 0.90, 0.15, ORANGE)]
    fig, ax = plt.subplots(figsize=(FIGW, 3.0))
    results = {}
    for name, H, Wm, col in objs:
        ts = []
        for v in speeds:
            dh = H / (K_V * NEAR_HEIGHT)
            da = math.sqrt(H * Wm / (K_V * K_H * NEAR_AREA))
            ds_ = max(dh, da)
            # looming fires when growth v/d >= rate, or ttc d/v <= limit, if big enough
            d_growth = v / LOOM_GROWTH_PER_SEC
            d_ttc = v * TTC_IMMINENT_SEC
            d_minsize = H / (K_V * MIN_LOOM_HEIGHT)
            dl = min(max(d_growth, d_ttc), d_minsize)
            d_alert = max(ds_, dl)
            ts.append(d_alert / v)
        ax.plot(speeds, ts, color=col, lw=2, zorder=4)
        results[name] = dict(t_at_14=float(np.interp(1.4, speeds, ts)),
                             t_at_08=float(np.interp(0.8, speeds, ts)),
                             t_at_20=float(np.interp(2.0, speeds, ts)))
    ax.text(0.66, 6.85, "Person", color=BLUE, fontsize=9)
    ax.text(0.66, 4.5, "Trash bin", color="#12775a", fontsize=9)
    ax.text(0.75, 2.5, "Bollard", color="#b64c22", fontsize=9, va="top")
    ax.axhline(TTC_IMMINENT_SEC, color=MUTED, lw=1, ls=(0, (4, 3)))
    ax.text(1.98, TTC_IMMINENT_SEC - 0.45, "time-to-contact floor (2.0 s)",
            fontsize=8, color=MUTED, ha="right")
    ax.axvline(1.4, color=GRID, lw=1)
    ax.text(1.385, 7.35, "typical walking speed", fontsize=8, color=MUTED,
            ha="right")
    ax.set_xlabel("Walking speed (m/s)", fontsize=9)
    ax.set_ylabel("Warning time before contact (s)", fontsize=9)
    ax.set_xlim(0.6, 2.0); ax.set_ylim(0, 8)
    strip_spines(ax)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    return results


# ================================================================ 3. threshold sweeps
def gen_scene(rng):
    if rng.random() < 0.25:
        d = rng.uniform(3.0, 10.0)
        H, Wm, x = rng.uniform(1.5, 3.0), 30.0, 0.0
        is_bg = True
    else:
        d = rng.uniform(0.8, 9.0)
        H = rng.uniform(0.3, 1.9)
        Wm = rng.uniform(0.15, 0.9)
        x = rng.uniform(-2.5, 2.5)
        is_bg = False
    hazard = (not is_bg) and (abs(x) <= 0.45) and (d <= 2.5)
    return d, H, Wm, x, hazard


def eval_rule(rng, n_clear=8000, n_haz=4000, near_h=NEAR_HEIGHT,
              cmin=CENTER_PATH_MIN_X, cmax=CENTER_PATH_MAX_X):
    """Stratified evaluation. FA over non-hazard scenes, miss over hazard scenes."""
    n_hist = int(LOOM_WINDOW_MS / 1000.0 * FPS)

    def run_scene(d, H, Wm, x):
        hist = deque()
        danger_prev = danger_now = False
        for k in range(n_hist + 1):
            dd = d + WALK_SPEED * (n_hist - k) * DT
            hf = height_frac(H, dd) * rng.normal(1.0, 0.10)
            wf = min(1.0, width_frac(Wm, dd) * rng.normal(1.0, 0.10))
            cx = 0.5 + math.atan2(x, dd) / HFOV + rng.normal(0.0, 0.03)
            danger_prev = danger_now
            danger_now = frame_danger_p(max(1e-4, hf), max(1e-4, wf), cx, hist,
                                        k * DT * 1000.0, near_h=near_h,
                                        cmin=cmin, cmax=cmax)
        return danger_now and danger_prev

    fa = miss = 0
    got = 0
    while got < n_clear:
        d, H, Wm, x, hazard = gen_scene(rng)
        if hazard:
            continue
        fa += run_scene(d, H, Wm, x)
        got += 1
    got = 0
    while got < n_haz:
        # hazard-conditioned sampling (in the walking path, within 2.5 m)
        d = rng.uniform(0.8, 2.5)
        H = rng.uniform(0.3, 1.9)
        Wm = rng.uniform(0.15, 0.9)
        x = rng.uniform(-0.45, 0.45)
        miss += (not run_scene(d, H, Wm, x))
        got += 1
    return fa / n_clear * 100, miss / n_haz * 100


def threshold_sweeps(seed=23):
    rng = np.random.default_rng(seed)
    hs = [0.22, 0.26, 0.30, 0.34, 0.40, 0.46]
    ws = [0.10, 0.14, 0.18, 0.22, 0.26]
    h_pts = [eval_rule(rng, near_h=v) for v in hs]
    w_pts = [eval_rule(rng, cmin=0.5 - v, cmax=0.5 + v) for v in ws]
    ship = eval_rule(rng, n_clear=16000, n_haz=8000)
    return hs, h_pts, ws, w_pts, ship


def fig_sweep(path, hs, h_pts, ws, w_pts, ship):
    fig, ax = plt.subplots(figsize=(FIGW, 3.2))
    hx = [p[0] for p in h_pts]; hy = [p[1] for p in h_pts]
    wx = [p[0] for p in w_pts]; wy = [p[1] for p in w_pts]
    ax.plot(hx, hy, "-o", color=BLUE, lw=1.8, ms=4.5, zorder=4)
    ax.plot(wx, wy, "-o", color=AQUA, lw=1.8, ms=4.5, zorder=4)
    for v, x, y in zip(hs, hx, hy):
        dy = -2.4 if abs(v - 0.34) > 1e-9 else 1.6
        ax.annotate("%.2f" % v, (x, y), xytext=(x + 0.12, y + dy), fontsize=7.6,
                    color="#0d366b")
    for v, x, y in zip(ws, wx, wy):
        ax.annotate("%.2f" % (2 * v), (x, y), xytext=(x + 0.12, y + 1.2),
                    fontsize=7.6, color="#0b4a34")
    ax.plot([ship[0]], [ship[1]], marker="*", ms=15, color=RED, zorder=6)
    ax.annotate("shipped operating point", (ship[0], ship[1]),
                xytext=(ship[0] + 0.5, ship[1] + 3.5), fontsize=8.6, color=RED)
    ax.text(hx[0] + 0.15, hy[0] - 5.6, "near-height gate sweep", color=BLUE,
            fontsize=9)
    ax.text(wx[-1] + 0.2, wy[-1] + 3.5, "corridor width sweep", color="#12775a",
            fontsize=9)
    ax.set_xlabel("False alarm rate (%)", fontsize=9)
    ax.set_ylabel("Miss rate (%)", fontsize=9)
    strip_spines(ax)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ================================================================ 4. flicker vs streak
def flicker_study(seed=31, runs=300, dur_s=60.0):
    rng = np.random.default_rng(seed)
    ps = [0.05, 0.10, 0.20]
    ks = [1, 2, 3, 4, 5]
    n_frames = int(dur_s * FPS)
    out = np.zeros((len(ps), len(ks)))
    for ip, p in enumerate(ps):
        for ik, k in enumerate(ks):
            total = 0
            for _ in range(runs):
                flick = rng.random(n_frames) < p
                streak = 0
                last_alert = -1e9
                alerts = 0
                for i in range(n_frames):
                    if flick[i]:
                        streak += 1
                        if streak >= k:
                            t = i * DT * 1000.0
                            if t - last_alert >= STOP_COOLDOWN_MS:
                                alerts += 1
                                last_alert = t
                            streak = 0
                    else:
                        streak = 0
                total += alerts
            out[ip, ik] = total / runs / (dur_s / 60.0)
    return ps, ks, out


def fig_flicker(path, ps, ks, out):
    fig, ax = plt.subplots(figsize=(FIGW, 3.0))
    cols = ["#9ec5f4", "#3987e5", "#0d366b"]
    label_pos = [(1.1, 10.3), (1.1, 14.35), (2.12, 10.1)]
    for ip, p in enumerate(ps):
        ax.plot(ks, out[ip], "-o", color=cols[ip], lw=1.8, ms=4.5, zorder=4)
        ax.text(label_pos[ip][0], label_pos[ip][1], "%d%% flicker" % round(p * 100),
                fontsize=8.6, color=cols[ip])
    ax.axvline(ENTER_STREAK, color=RED, lw=1.1, ls=(0, (4, 3)))
    ax.text(ENTER_STREAK + 0.06, 12.6, "shipped (2 frames)",
            fontsize=8.6, color=RED)
    ax.set_xticks(ks)
    ax.set_xticklabels(["%d\nadds %.1f s" % (k, (k - 1) / FPS) for k in ks],
                       fontsize=8)
    ax.set_xlabel("Consecutive frames required before an alert", fontsize=9)
    ax.set_ylabel("Spurious alerts per minute", fontsize=9)
    strip_spines(ax)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    return {"%.2f" % p: [float(x) for x in out[ip]] for ip, p in enumerate(ps)}


# ================================================================ 5. hint correctness
def hint_correctness(n=30000, seed=41):
    rng = np.random.default_rng(seed)
    buckets = {"low": [0, 0, 0, 0], "medium": [0, 0, 0, 0], "high": [0, 0, 0, 0]}
    counts = {"low": 0, "medium": 0, "high": 0}
    # outcomes: correct hint, conservative silence, correct silence, wrong hint
    for _ in range(n):
        c = rng.uniform(0.20, 0.80)              # obstacle center in frame
        obst_occ = rng.uniform(0.35, 0.70)       # obstacle side occupancy
        opp_occ = rng.uniform(0.0, 0.70)         # true opposite-side occupancy
        bucket = "low" if opp_occ < 0.2 else ("medium" if opp_occ < 0.45 else "high")
        counts[bucket] += 1
        # ground truth. veering toward the opposite side is safe when that side
        # is materially clearer than the obstacle side
        safe_exists = opp_occ <= obst_occ - 0.10 and opp_occ <= 0.45
        # rule under measurement noise
        c_j = c + rng.normal(0.0, 0.03)
        obst_j = min(1.0, max(0.0, obst_occ + rng.normal(0.0, 0.05)))
        opp_j = min(1.0, max(0.0, opp_occ + rng.normal(0.0, 0.05)))
        if c < 0.5:
            hint = compute_hint(c_j, obst_j, opp_j)
            toward_opp = hint == "VEER_RIGHT"
        else:
            hint = compute_hint(c_j, opp_j, obst_j)
            toward_opp = hint == "VEER_LEFT"
        fired = hint != "NONE"
        if fired and toward_opp and safe_exists:
            buckets[bucket][0] += 1
        elif not fired and safe_exists:
            buckets[bucket][1] += 1
        elif not fired and not safe_exists:
            buckets[bucket][2] += 1
        else:
            buckets[bucket][3] += 1   # fired toward an unsafe or wrong side
    frac = {b: [v / counts[b] * 100 for v in buckets[b]] for b in buckets}
    wrong_total = sum(buckets[b][3] for b in buckets) / n * 100
    return frac, wrong_total


def fig_hint_outcomes(path, frac, wrong_total):
    fig, ax = plt.subplots(figsize=(FIGW, 3.0))
    order = ["low", "medium", "high"]
    labels = ["Opposite side open\n(occupancy under 0.2)",
              "Moderate clutter\n(0.2 to 0.45)",
              "Heavy clutter\n(over 0.45)"]
    cols = [BLUE, "#cfd9e8", "#a6a49d", RED]
    names = ["correct hint", "conservative silence", "correct silence", "wrong hint"]
    xs = np.arange(3)
    bottoms = np.zeros(3)
    for k in range(4):
        vals = [frac[b][k] for b in order]
        ax.bar(xs, vals, bottom=bottoms, width=0.55, color=cols[k],
               edgecolor="#ffffff", linewidth=2, zorder=3)
        for i, v in enumerate(vals):
            if v > 7:
                ax.text(xs[i], bottoms[i] + v / 2, "%.0f%%" % v, ha="center",
                        va="center", fontsize=8.2,
                        color="#ffffff" if k in (0, 3) else INK)
        bottoms += vals
    ax.set_xticks(xs); ax.set_xticklabels(labels, fontsize=8.6, color=INK)
    ax.set_ylabel("Share of scenes (%)", fontsize=9)
    ax.grid(axis="x", visible=False)
    handles = [Rectangle((0, 0), 1, 1, color=c) for c in cols]
    ax.legend(handles, names, fontsize=8, frameon=False, ncol=4,
              loc="upper center", bbox_to_anchor=(0.5, 1.14))
    ax.set_ylim(0, 100)
    strip_spines(ax)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ================================================================ 6. tier 2 cadence
def fig_cadence(path):
    L = np.linspace(0.2, 4.0, 200)
    throttle = 1.5
    updates = 60.0 / np.maximum(throttle, L)
    stale = WALK_SPEED * L
    fig, (ax, ax2) = plt.subplots(2, 1, figsize=(FIGW, 3.8), sharex=True)
    ax.plot(L, updates, color=BLUE, lw=2, zorder=4)
    ax.axvspan(1.5, 3.0, color=ORANGE, alpha=0.12, zorder=1)
    ax.text(2.25, 34, "measured cloud range", fontsize=8.4, color="#b64c22",
            ha="center")
    ax.plot([0.5], [60.0 / max(throttle, 0.5)], "o", ms=6, color=AQUA, zorder=5)
    ax.annotate("hypothetical on-device model (0.5 s)", (0.5, 40),
                xytext=(0.62, 44), fontsize=8.4, color="#12775a")
    ax.text(1.46, 2.5, "throttle cap (1.5 s)", fontsize=8, color=MUTED,
            rotation=90, ha="right", va="bottom")
    ax.axvline(throttle, color=GRID, lw=1)
    ax.set_ylabel("Guidance updates\nper minute", fontsize=9)
    ax.set_ylim(0, 50)
    strip_spines(ax)
    ax2.plot(L, stale, color=BLUE, lw=2, zorder=4)
    ax2.axvspan(1.5, 3.0, color=ORANGE, alpha=0.12, zorder=1)
    ax2.plot([0.5], [WALK_SPEED * 0.5], "o", ms=6, color=AQUA, zorder=5)
    ax2.annotate("0.7 m", (0.5, WALK_SPEED * 0.5), xytext=(0.6, 1.2),
                 fontsize=8.4, color="#12775a")
    ax2.annotate("2.1 to 4.2 m", (2.25, 3.4), fontsize=8.4, color="#b64c22",
                 ha="center")
    ax2.set_xlabel("Model response latency (s)", fontsize=9)
    ax2.set_ylabel("Scene staleness when\nspoken (m at 1.4 m/s)", fontsize=9)
    strip_spines(ax2)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ================================================================ main
if __name__ == "__main__":
    import os
    out = os.path.dirname(os.path.abspath(__file__))

    ds, hs, P = detection_envelope()
    fig_envelope(os.path.join(out, "fig_envelope.png"), ds, hs, P)

    EXTRA["speed"] = fig_speed(os.path.join(out, "fig_speed.png"))

    hsw, h_pts, ws, w_pts, ship = threshold_sweeps()
    fig_sweep(os.path.join(out, "fig_sweep.png"), hsw, h_pts, ws, w_pts, ship)
    EXTRA["sweep"] = dict(near_height={str(v): p for v, p in zip(hsw, h_pts)},
                          corridor={str(2 * v): p for v, p in zip(ws, w_pts)},
                          shipped=ship)

    ps, ks, flick = flicker_study()
    EXTRA["flicker"] = fig_flicker(os.path.join(out, "fig_flicker.png"), ps, ks, flick)

    frac, wrong_total = hint_correctness()
    fig_hint_outcomes(os.path.join(out, "fig_hint_outcomes.png"), frac, wrong_total)
    EXTRA["hint_outcomes"] = dict(frac=frac, wrong_total=wrong_total)

    fig_cadence(os.path.join(out, "fig_cadence.png"))

    with open(os.path.join(out, "sim_extra_results.json"), "w") as f:
        json.dump(EXTRA, f, indent=2, default=str)
    print(json.dumps(EXTRA, indent=2, default=str))
