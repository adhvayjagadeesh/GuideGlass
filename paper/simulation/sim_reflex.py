"""
Simulation of the GuideGlass/VisionAI Tier-1 reflex decision logic.

Ports the exact shipped constants and decision rules from
app/src/main/java/com/impairedvision/guideglass/vision/ObstacleDetector.kt
and the alert gating from VisionActivity.kt, then runs synthetic walking
scenarios through them and renders paper figures.

Camera model assumptions (stated in the paper text):
  vertical FOV 60 deg, horizontal FOV 46 deg (portrait phone frame),
  effective detector frame rate 10 fps, walking speed 1.4 m/s.
"""

import json
import math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch, Rectangle
from collections import deque

# ---------------------------------------------------------------- constants
# ObstacleDetector.ReflexTuning (shipped values)
CENTER_PATH_MIN_X = 0.32
CENTER_PATH_MAX_X = 0.68
MIN_CORRIDOR_OVERLAP = 0.45
MAX_BOX_WIDTH = 0.90
NEAR_HEIGHT = 0.34
NEAR_AREA = 0.12
ENTER_STREAK = 2
CLEAR_STREAK = 4
# ObstacleDetector companion (looming / TTC)
LOOM_WINDOW_MS = 900
MAX_SAMPLES = 6
MIN_LOOM_HEIGHT = 0.16
MIN_LOOM_AREA = 0.03
LOOM_GROWTH_PER_SEC = 0.6
TTC_IMMINENT_SEC = 2.0
# Hint geometry
HINT_SIDE_MIN_HEIGHT = 0.15
HINT_OCC_DIFF = 0.20
HINT_CLEAR_OCC = 0.35
CENTER_NEUTRAL_MIN = 0.46
CENTER_NEUTRAL_MAX = 0.54
# VisionActivity gates
STOP_COOLDOWN_MS = 3500
REARM_CLEAR_MS = 1200
GEMINI_THROTTLE_MOVING_MS = 1500

# Simulation environment
VFOV = math.radians(60.0)
HFOV = math.radians(46.0)
FPS = 10.0
DT = 1.0 / FPS
WALK_SPEED = 1.4  # m/s
K_V = 2.0 * math.tan(VFOV / 2.0)   # height_frac = H / (K_V * d)
K_H = 2.0 * math.tan(HFOV / 2.0)   # width_frac  = W / (K_H * d)

RESULTS = {}

# ---------------------------------------------------------------- palette
BLUE = "#2a78d6"; AQUA = "#1baf7a"; ORANGE = "#eb6834"; RED = "#d03b3b"
INK = "#0b0b0b"; SEC = "#52514e"; MUTED = "#898781"
GRID = "#e1e0d9"; BASE = "#c3c2b7"; SURFACE = "#ffffff"

plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "Times", "DejaVu Serif"],
    "font.size": 10,
    "axes.edgecolor": BASE,
    "axes.labelcolor": INK,
    "axes.titlesize": 10.5,
    "xtick.color": MUTED, "ytick.color": MUTED,
    "xtick.labelcolor": SEC, "ytick.labelcolor": SEC,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6,
    "axes.axisbelow": True,
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE,
    "savefig.dpi": 200, "figure.dpi": 110,
})

def strip_spines(ax):
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)


def height_frac(H, d):
    return min(1.0, H / (K_V * d))

def width_frac(W, d):
    return min(1.0, W / (K_H * d))


# ---------------------------------------------------------------- port of the decision logic
def is_looming(hist, curr_h, curr_a):
    """Port of ObstacleDetector.isLooming."""
    if hist is None or len(hist) < 2:
        return False
    if curr_h < MIN_LOOM_HEIGHT and curr_a < MIN_LOOM_AREA:
        return False
    t0, h0 = hist[0]
    t1, h1 = hist[-1]
    dt = (t1 - t0) / 1000.0
    if dt <= 0 or h0 <= 0:
        return False
    dh = h1 - h0
    if dh <= 0:
        return False
    growth = (dh / h0) / dt
    if growth >= LOOM_GROWTH_PER_SEC:
        return True
    ttc = dt * h0 / dh
    return 0.0 <= ttc <= TTC_IMMINENT_SEC


def compute_hint(center_ratio, left_occ, right_occ):
    """Port of ObstacleDetector.computeHint."""
    if CENTER_NEUTRAL_MIN <= center_ratio <= CENTER_NEUTRAL_MAX:
        return "NONE"
    if abs(left_occ - right_occ) < HINT_OCC_DIFF:
        return "NONE"
    if center_ratio < 0.5:
        return "VEER_RIGHT" if right_occ <= HINT_CLEAR_OCC else "NONE"
    return "VEER_LEFT" if left_occ <= HINT_CLEAR_OCC else "NONE"


def frame_danger(hf, wf, cx, hist=None, t_ms=None):
    """Static per-frame danger decision for one box centered at cx (fraction).

    Mirrors evaluatePathObstacle for a single object. Returns (danger, near, loom).
    """
    if wf >= MAX_BOX_WIDTH:
        return False, False, False
    af = hf * wf
    left = cx - wf / 2.0
    right = cx + wf / 2.0
    center_in = CENTER_PATH_MIN_X <= cx <= CENTER_PATH_MAX_X
    overlap = min(right, CENTER_PATH_MAX_X) - max(left, CENTER_PATH_MIN_X)
    overlap_frac = overlap / (CENTER_PATH_MAX_X - CENTER_PATH_MIN_X)
    if not center_in and overlap_frac < MIN_CORRIDOR_OVERLAP:
        return False, False, False
    loom = False
    if hist is not None:
        hist.append((t_ms, hf))
        while len(hist) > 1 and t_ms - hist[0][0] > LOOM_WINDOW_MS:
            hist.popleft()
        while len(hist) > MAX_SAMPLES:
            hist.popleft()
        loom = is_looming(hist, hf, af)
    near = hf >= NEAR_HEIGHT or af >= NEAR_AREA
    return (near or loom), near, loom


# ================================================================ Scenario A
# Warning distance and time to warning versus obstacle size (closed form).
def warning_distances():
    heights = np.linspace(0.1, 2.0, 200)
    d_size, d_loom = [], []
    for H in heights:
        W = 0.4 * H  # generic aspect for the sweep
        dh = H / (K_V * NEAR_HEIGHT)                    # height gate
        da = math.sqrt(H * W / (K_V * K_H * NEAR_AREA)) # area gate
        ds = max(dh, da)
        # looming path fires out to the TTC radius if the box is big enough there
        d_ttc = WALK_SPEED * TTC_IMMINENT_SEC
        d_minsize = H / (K_V * MIN_LOOM_HEIGHT)
        dl = min(d_ttc, d_minsize)
        d_size.append(ds)
        d_loom.append(max(ds, dl))
    return heights, np.array(d_size), np.array(d_loom)


def named_objects():
    objs = [
        ("Person", 1.70, 0.50),
        ("Trash bin", 1.00, 0.60),
        ("Bollard", 0.90, 0.15),
        ("Low curb", 0.15, 2.00),  # spans the walkway
    ]
    out = {}
    for name, H, W in objs:
        dh = H / (K_V * NEAR_HEIGHT)
        da = math.sqrt(H * W / (K_V * K_H * NEAR_AREA))
        ds = max(dh, da)
        dl = max(ds, min(WALK_SPEED * TTC_IMMINENT_SEC, H / (K_V * MIN_LOOM_HEIGHT)))
        # width-gate rejection distance: nearer than this the box spans >90% width
        d_reject = W / (K_H * MAX_BOX_WIDTH)
        # the object is only detectable if its trigger distance is outside the
        # background-rejection radius (a box spanning >90% of the frame is dropped)
        detected = dl > d_reject
        out[name] = dict(H=H, W=W, d_size=ds, d_loom=dl, t_size=ds / WALK_SPEED,
                         t_loom=dl / WALK_SPEED, d_reject=d_reject, detected=detected)
    return out


# ================================================================ Scenario B
# Full state-machine timeline for a two-approach walk toward a bollard.
def timeline_sim():
    H, W = 0.9, 0.15
    # trajectory: approach 8->0.5 m at 1.4 m/s, hold 3 s, retreat to 3.5 m at 1.0 m/s,
    # hold 1.5 s, approach again to 0.5 m at 1.4 m/s
    seg = []
    def add(d0, d1, v, hold=0.0):
        if hold > 0:
            seg.extend([d0] * int(hold * FPS))
        else:
            n = max(1, int(abs(d1 - d0) / v * FPS))
            seg.extend(list(np.linspace(d0, d1, n)))
    add(8.0, 0.5, 1.4)
    add(0.5, 0.5, 0, hold=3.0)
    add(0.5, 3.5, 1.0)
    add(3.5, 3.5, 0, hold=1.5)
    add(3.5, 0.5, 1.4)

    hist = deque()
    present = False
    enter_streak = clear_streak = 0
    last_stop_ms = -10**9
    last_clear_ms = -10**9
    was_cleared = True
    times, hfs, danger_flags = [], [], []
    spoken, naive_spoken = [], []
    loom_first = None
    for i, d in enumerate(seg):
        t_ms = i * DT * 1000.0
        hf = height_frac(H, d)
        wf = width_frac(W, d)
        danger, near, loom = frame_danger(hf, wf, 0.5, hist, t_ms)
        if loom and loom_first is None:
            loom_first = (t_ms / 1000.0, d)
        times.append(t_ms / 1000.0)
        hfs.append(hf)
        danger_flags.append(danger)
        if danger:
            naive_spoken.append(t_ms / 1000.0)  # naive level-triggered baseline
            clear_streak = 0
            if not present:
                enter_streak += 1
                if enter_streak >= ENTER_STREAK:
                    present = True
                    enter_streak = 0
                    cooled = t_ms - last_stop_ms >= STOP_COOLDOWN_MS
                    rearmed = was_cleared and t_ms - last_clear_ms >= REARM_CLEAR_MS
                    if cooled and rearmed:
                        last_stop_ms = t_ms
                        was_cleared = False
                        spoken.append((t_ms / 1000.0, d))
        else:
            enter_streak = 0
            if present:
                clear_streak += 1
                if clear_streak >= CLEAR_STREAK:
                    present = False
                    clear_streak = 0
                    was_cleared = True
                    last_clear_ms = t_ms
    return dict(times=times, hfs=hfs, danger=danger_flags, spoken=spoken,
                naive=naive_spoken, loom_first=loom_first, traj=seg)


# ================================================================ Scenario C
# Monte Carlo comparison with the earlier bottom-edge heuristic.
#
# Each sample is a walker moving at 1.4 m/s toward a static scene object under a
# random hand-held camera pitch. The shipped rule sees the last 0.9 s of frames
# (so its looming cue is active, as on-device). Both rules see the same box
# jitter. Ground truth hazard = object in the walking path within 2.5 m.
def monte_carlo(n=40000, seed=7):
    rng = np.random.default_rng(seed)
    JITTER_DIM = 0.10   # multiplicative box-dimension noise per frame
    JITTER_POS = 0.03   # additive center-position noise per frame

    def legacy_rule(cx, bottom):
        return (0.3 <= cx <= 0.7) and bottom >= 0.8

    def bottom_edge(cam_h, d, pitch):
        alpha = math.atan2(cam_h, d)              # ground contact below horizon
        return min(1.0, max(0.0, 0.5 + (alpha - pitch) / VFOV))

    old_alert = np.zeros(n, bool)
    new1_alert = np.zeros(n, bool)   # shipped rule, single frame
    new2_alert = np.zeros(n, bool)   # shipped rule + 2-frame confirmation
    hazard = np.zeros(n, bool)
    old_flip = np.zeros(n, bool)     # legacy decision changes with +/-8 deg pitch
    new_flip = np.zeros(n, bool)
    miss_low = 0; miss_all = 0       # low-obstacle share of shipped-rule misses

    for i in range(n):
        pitch = math.radians(rng.uniform(-10, 30))   # hand-held pitch variability
        cam_h = rng.normal(1.3, 0.08)
        if rng.random() < 0.25:
            # background span (wall or fence) well ahead, ground-contact box
            d = rng.uniform(3.0, 10.0)
            H, Wm, x = rng.uniform(1.5, 3.0), 30.0, 0.0
            is_bg = True
        else:
            d = rng.uniform(0.8, 9.0)
            H = rng.uniform(0.3, 1.9)
            Wm = rng.uniform(0.15, 0.9)
            x = rng.uniform(-2.5, 2.5)
            is_bg = False
        hazard[i] = (not is_bg) and (abs(x) <= 0.45) and (d <= 2.5)

        cx_true = 0.5 + math.atan2(x, d) / HFOV

        # legacy rule on a jittered frame
        bot = bottom_edge(cam_h, d, pitch) + rng.normal(0.0, JITTER_POS)
        cx_j = cx_true + rng.normal(0.0, JITTER_POS)
        old_alert[i] = legacy_rule(cx_j, bot)
        # pitch sensitivity of each rule (decision under -8 and +8 deg of pitch)
        lo = legacy_rule(cx_true, bottom_edge(cam_h, d, pitch - math.radians(8)))
        hi = legacy_rule(cx_true, bottom_edge(cam_h, d, pitch + math.radians(8)))
        old_flip[i] = lo != hi
        # apparent size does not depend on pitch, so the shipped gates cannot flip
        new_flip[i] = False

        # shipped rule with the walker's last 0.9 s of approach in the history
        def shipped_frame(dd, hist, t_ms):
            hf = height_frac(H, dd) * rng.normal(1.0, JITTER_DIM)
            wf = min(1.0, width_frac(Wm, dd) * rng.normal(1.0, JITTER_DIM))
            cxx = 0.5 + math.atan2(x, dd) / HFOV + rng.normal(0.0, JITTER_POS)
            return frame_danger(max(1e-4, hf), max(1e-4, wf), cxx, hist, t_ms)

        hist = deque()
        n_hist = int(LOOM_WINDOW_MS / 1000.0 * FPS)  # frames inside the loom window
        danger_prev = danger_now = False
        for k in range(n_hist + 1):
            t_back = (n_hist - k) * DT
            dd = d + WALK_SPEED * t_back
            danger_prev = danger_now
            danger_now, _, _ = shipped_frame(dd, hist, k * DT * 1000.0)
        new1_alert[i] = danger_now
        new2_alert[i] = danger_now and danger_prev

        if hazard[i] and not new2_alert[i]:
            miss_all += 1
            if H < 0.5:
                miss_low += 1

    def rates(alert):
        fa = float(np.mean(alert[~hazard]))
        miss = float(np.mean(~alert[hazard]))
        return fa, miss
    return dict(old=rates(old_alert), new=rates(new1_alert), new2=rates(new2_alert),
                n=n, hazard_frac=float(np.mean(hazard)),
                old_flip=float(np.mean(old_flip)), new_flip=float(np.mean(new_flip)),
                miss_low_share=(miss_low / miss_all if miss_all else 0.0))


# ================================================================ Scenario D
# Veer-hint decision regions.
def hint_map(res=400):
    cs = np.linspace(0.0, 1.0, res)
    occs = np.linspace(0.0, 0.8, res)
    Z = np.zeros((res, res), int)  # 0 silent, 1 veer left, 2 veer right
    obstacle_occ = 0.45
    for iy, occ in enumerate(occs):
        for ix, c in enumerate(cs):
            if c < 0.5:
                left_occ, right_occ = obstacle_occ, occ
            else:
                left_occ, right_occ = occ, obstacle_occ
            h = compute_hint(c, left_occ, right_occ)
            Z[iy, ix] = {"NONE": 0, "VEER_LEFT": 1, "VEER_RIGHT": 2}[h]
    silent = float(np.mean(Z == 0))
    return cs, occs, Z, silent


# ================================================================ figures
FIGW = 6.3

def fig1_architecture(path):
    """Left-to-right flow. One fork after the camera, one merge into arbitration,
    horizontal label runs, and no crossing edges."""
    fig, ax = plt.subplots(figsize=(7.6, 4.8))
    ax.set_xlim(-1, 120); ax.set_ylim(-3, 74)
    ax.axis("off")

    def box(x0, x1, y0, y1, title, lines, fc="#ffffff", ec=BASE, lw=1.3):
        ax.add_patch(FancyBboxPatch((x0, y0), x1 - x0, y1 - y0,
                                    boxstyle="round,pad=0.6", fc=fc, ec=ec, lw=lw))
        cx = (x0 + x1) / 2.0
        gap = 5.4
        block = gap * len(lines)
        ty = (y0 + y1) / 2.0 + block / 2.0
        ax.text(cx, ty, title, ha="center", va="center",
                fontsize=11, fontweight="bold", color=INK)
        for k, ln in enumerate(lines):
            ax.text(cx, ty - gap * (k + 1), ln, ha="center", va="center",
                    fontsize=9.5, color=SEC)

    def arrow(p0, p1, color=SEC, lw=1.4):
        ax.add_patch(FancyArrowPatch(p0, p1, arrowstyle="-|>", mutation_scale=13,
                                     color=color, lw=lw, shrinkA=0, shrinkB=0))

    def line(p0, p1, color=SEC, lw=1.4):
        ax.plot([p0[0], p1[0]], [p0[1], p1[1]], color=color, lw=lw,
                solid_capstyle="round", zorder=1)

    # ---- boxes -----------------------------------------------------------
    box(2, 29, 40, 55, "Glasses Camera", ["Frame stream"])
    box(2, 29, 21, 35, "GPS and Compass", ["Location, bearing"])
    box(2, 29, 2, 16, "Google Maps Route", ["Steps, summary"])

    box(39, 73, 49, 70, "Tier 1   On-Device Reflex",
        ["Corridor gate", "Size and looming checks", "Edge-triggered alerts"],
        fc="#eaf1fb", ec=BLUE)
    box(39, 73, 21, 39, "Tier 2   Cloud MLLM",
        ["Scene and context reasoning", "Guidance under 12 words"],
        fc="#e9f7f1", ec=AQUA)
    box(39, 73, 2, 15, "Navigation Context Builder", ["Bearing, next step"])

    box(90, 118, 37, 56, "Arbitration",
        ["Urgent alerts pre-empt", "Duplicates dropped"])
    box(90, 118, 10, 29, "Single Audio Stream", ["Speech and haptics"],
        fc="#fdf0ec", ec=ORANGE)

    # ---- timing annotations ---------------------------------------------
    ax.text(56, 72.2, "Under 100 ms target", fontsize=9.5, color=BLUE,
            ha="center", style="italic")
    ax.text(62, 17.6, "1.5 to 3 s measured", fontsize=9.5, color="#12775a",
            ha="center", style="italic")

    # ---- edges -----------------------------------------------------------
    # camera forks to both tiers from a single junction
    jx, jy = 34.0, 47.5
    line((29, jy), (jx, jy))
    ax.plot([jx], [jy], marker="o", ms=5, color=SEC, zorder=3)
    arrow((jx, jy), (39, 58.0))
    arrow((jx, jy), (39, 31.0))

    # location inputs feed the context builder
    arrow((29, 28), (39, 11.0))
    arrow((29, 9), (39, 7.5))

    # context builder feeds Tier 2 from below
    arrow((50, 15), (50, 21))

    # tiers merge into arbitration along horizontal label runs
    line((73, 58), (86, 58))
    arrow((86, 58), (90, 50.5))
    line((73, 31), (86, 31))
    arrow((86, 31), (90, 42.0))

    # arbitration to audio
    arrow((104, 37), (104, 29))

    ax.text(79.5, 61.0, "Urgent", fontsize=9.5, color=BLUE, ha="center")
    ax.text(79.5, 27.2, "Guidance", fontsize=9.5, color="#12775a", ha="center")

    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def fig2_latency(path):
    fig, ax = plt.subplots(figsize=(FIGW, 2.3))
    # reflex bar
    ax.barh(1, 0.1, height=0.42, color=BLUE, zorder=3)
    # cloud bar: solid to 1.5, lighter to 3.0
    ax.barh(0, 1.5, height=0.42, color=AQUA, zorder=3)
    ax.barh(0, 1.5, left=1.5, height=0.42, color=AQUA, alpha=0.35, zorder=3)
    ax.set_yticks([1, 0])
    ax.set_yticklabels(["On-device reflex\n(budget)", "Cloud MLLM\n(measured range)"],
                       fontsize=9, color=INK)
    ax.set_xlim(0, 4.6)
    ax.set_xlabel("Time from frame capture to spoken output (s)", fontsize=9)
    strip_spines(ax)
    ax.grid(axis="y", visible=False)
    for x, lab in [(0.1, "0.1 s\n0.14 m walked"), (1.5, "1.5 s\n2.1 m walked"),
                   (3.0, "3.0 s\n4.2 m walked")]:
        ax.axvline(x, color=GRID, lw=0.8, zorder=1)
        ax.annotate(lab, (x, 1.62), fontsize=7.8, color=SEC, ha="left",
                    xytext=(x + 0.05, 1.45))
    ax.set_ylim(-0.55, 2.0)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def fig3_warning(path, objs):
    heights, d_size, d_loom = warning_distances()
    fig, ax = plt.subplots(figsize=(FIGW, 3.4))
    ax.plot(heights, d_loom, color=AQUA, lw=2, zorder=4)
    ax.plot(heights, d_size, color=BLUE, lw=2, zorder=4)
    ax.axhline(WALK_SPEED * TTC_IMMINENT_SEC, color=MUTED, lw=1, ls=(0, (4, 3)))
    ax.text(1.995, 2.48, "time-to-contact radius at 1.4 m/s (2.8 m)",
            fontsize=8, color=MUTED, ha="right")
    # one-second reaction band
    ax.axhspan(0, WALK_SPEED * 1.0, color=RED, alpha=0.07, zorder=1)
    ax.text(1.995, 0.35, "under one second of warning", fontsize=8, color=RED,
            ha="right")
    ax.text(1.28, 4.05, "size gate only", color=BLUE, fontsize=9, rotation=31)
    ax.text(0.32, 3.0, "size + looming", color="#12775a", fontsize=9)
    offsets = {"Person": (-0.05, 0.25, "right"),
               "Trash bin": (0.05, -0.42, "left"),
               "Bollard": (-0.05, 0.22, "right")}
    for name, o in objs.items():
        if name == "Low curb":
            continue
        ax.plot([o["H"]], [o["d_loom"]], "o", ms=5, color=INK, zorder=5)
        dx, dy, ha = offsets[name]
        ax.annotate(name, (o["H"], o["d_loom"]),
                    xytext=(o["H"] + dx, o["d_loom"] + dy),
                    fontsize=8.4, color=INK, ha=ha)
    ax.set_xlabel("Obstacle height (m)", fontsize=9)
    ax.set_ylabel("Distance at first alert (m)", fontsize=9)
    ax.set_xlim(0.1, 2.0); ax.set_ylim(0, 5.5)
    strip_spines(ax)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def fig4_timeline(path, tl):
    fig, (ax, ax2) = plt.subplots(2, 1, figsize=(FIGW, 4.2), sharex=True,
                                  height_ratios=[2.1, 1.2])
    t = np.array(tl["times"]); hf = np.array(tl["hfs"])
    ax.plot(t, hf, color=BLUE, lw=1.8, zorder=4)
    ax.axhline(NEAR_HEIGHT, color=MUTED, lw=1, ls=(0, (4, 3)))
    ax.text(t[-1], NEAR_HEIGHT + 0.02, "near-size threshold (0.34)", fontsize=8,
            color=MUTED, ha="right")
    if tl["loom_first"]:
        ax.axvline(tl["loom_first"][0], color=AQUA, lw=1.2, ls=(0, (2, 2)))
        ax.text(tl["loom_first"][0] - 0.15, 0.86, "looming detected", fontsize=8,
                color="#12775a", ha="right")
    for (ts, d) in tl["spoken"]:
        ax.plot([ts], [np.interp(ts, t, hf)], marker="*", ms=13, color=RED, zorder=6)
        ax.annotate("STOP spoken\n%.1f m away" % d, (ts, np.interp(ts, t, hf)),
                    xytext=(ts + 0.3, min(0.9, np.interp(ts, t, hf) + 0.16)),
                    fontsize=8, color=RED)
    ax.set_ylabel("Box height fraction", fontsize=9)
    ax.set_ylim(0, 1.05)
    strip_spines(ax)

    naive = np.array(tl["naive"])
    cum_naive = np.arange(1, len(naive) + 1)
    ax2.step(naive, cum_naive, where="post", color=ORANGE, lw=1.8, zorder=4)
    sp = [s for s, _ in tl["spoken"]]
    ax2.step(sp + [t[-1]], list(range(1, len(sp) + 1)) + [len(sp)], where="post",
             color=BLUE, lw=1.8, zorder=5)
    ax2.text(naive[-1] - 0.2, cum_naive[-1] - 6, "level-triggered (naive), %d alerts"
             % cum_naive[-1], fontsize=8.6, color="#b64c22", ha="right")
    ax2.text(t[-1] - 0.2, len(sp) + 4, "shipped gating, %d alerts" % len(sp),
             fontsize=8.6, color=BLUE, ha="right")
    ax2.set_xlabel("Time (s)", fontsize=9)
    ax2.set_ylabel("Spoken alerts", fontsize=9)
    strip_spines(ax2)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    return len(sp), int(cum_naive[-1])


def fig5_montecarlo(path, mc):
    fig, ax = plt.subplots(figsize=(FIGW, 2.8))
    labels = ["Legacy bottom-edge rule", "Shipped rule, single frame",
              "Shipped rule + 2-frame confirmation"]
    colors = [ORANGE, "#9ec5f4", BLUE]
    fa = [mc["old"][0] * 100, mc["new"][0] * 100, mc["new2"][0] * 100]
    miss = [mc["old"][1] * 100, mc["new"][1] * 100, mc["new2"][1] * 100]
    xs = np.arange(2)
    w = 0.24
    for k, (f, m) in enumerate(zip(fa, miss)):
        b = ax.bar(xs + (k - 1) * (w + 0.02), [f, m], width=w, color=colors[k], zorder=3)
        for rect, v in zip(b, [f, m]):
            ax.text(rect.get_x() + rect.get_width() / 2, v + 0.6, "%.1f%%" % v,
                    ha="center", fontsize=8.2, color=INK)
    ax.set_xticks(xs)
    ax.set_xticklabels(["False alarm rate\n(alert with no hazard present)",
                        "Miss rate\n(hazard present, no alert)"], fontsize=9, color=INK)
    ax.set_ylabel("Rate (%)", fontsize=9)
    ax.grid(axis="x", visible=False)
    strip_spines(ax)
    handles = [Rectangle((0, 0), 1, 1, color=c) for c in colors]
    ax.legend(handles, labels, fontsize=8, frameon=False, loc="upper right")
    ax.set_ylim(0, max(fa + miss) * 1.35)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def fig6_hintmap(path, cs, occs, Z, silent):
    from matplotlib.colors import ListedColormap
    fig, ax = plt.subplots(figsize=(FIGW, 3.0))
    cmap = ListedColormap(["#efeeea", "#9ec5f4", AQUA])
    ax.pcolormesh(cs, occs, Z, cmap=cmap, shading="auto", zorder=2)
    ax.axvspan(CENTER_NEUTRAL_MIN, CENTER_NEUTRAL_MAX, color="#dddcd5", zorder=3, alpha=0.9)
    ax.text(0.5, 0.41, "dead\nahead", ha="center", fontsize=7.8, color=SEC, zorder=4)
    ax.text(0.20, 0.12, "obstacle left,\nright side open\nVEER RIGHT", ha="center",
            fontsize=8.6, color="#083b29", zorder=4)
    ax.text(0.80, 0.12, "obstacle right,\nleft side open\nVEER LEFT", ha="center",
            fontsize=8.6, color="#0d366b", zorder=4)
    ax.text(0.5, 0.62, "system stays silent (ambiguous or blocked both sides)",
            ha="center", fontsize=8.6, color=SEC, zorder=4)
    ax.set_xlabel("Obstacle center position in frame (0 left, 1 right)", fontsize=9)
    ax.set_ylabel("Occupancy of opposite side", fontsize=9)
    ax.grid(visible=False)
    strip_spines(ax)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ================================================================ main
if __name__ == "__main__":
    import os
    out = os.path.dirname(os.path.abspath(__file__))

    objs = named_objects()
    RESULTS["objects"] = objs

    tl = timeline_sim()
    RESULTS["timeline_spoken"] = tl["spoken"]
    RESULTS["timeline_naive_count"] = len(tl["naive"])
    RESULTS["loom_first"] = tl["loom_first"]

    mc = monte_carlo()
    RESULTS["monte_carlo"] = mc

    cs, occs, Z, silent = hint_map()
    RESULTS["hint_silent_fraction"] = silent

    fig1_architecture(os.path.join(out, "fig1_architecture.png"))
    fig2_latency(os.path.join(out, "fig2_latency.png"))
    fig3_warning(os.path.join(out, "fig3_warning.png"), objs)
    n_gated, n_naive = fig4_timeline(os.path.join(out, "fig4_timeline.png"), tl)
    RESULTS["alerts_gated"] = n_gated
    RESULTS["alerts_naive"] = n_naive
    fig5_montecarlo(os.path.join(out, "fig5_montecarlo.png"), mc)
    fig6_hintmap(os.path.join(out, "fig6_hintmap.png"), cs, occs, Z, silent)

    with open(os.path.join(out, "sim_results.json"), "w") as f:
        json.dump(RESULTS, f, indent=2, default=str)
    print(json.dumps(RESULTS, indent=2, default=str))
