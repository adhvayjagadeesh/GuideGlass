"""
External-baseline comparison for the VisionAI Results section.

Addresses internal-review comment 11: the shipped rule must be compared against
published alternatives run through the same scene distribution, not only against
our own earlier prototype.

Five decision rules are evaluated on identical synthetic scenes:

  R1  Optical time-to-contact (tau) criterion, Lee 1976 [24]. Alert when the
      tracked box's expansion implies tau <= 2 s. No spatial gate.
  R2  Fixed apparent-size threshold. Alert when box height >= 0.34 of frame.
      No spatial gate, no temporal confirmation.
  R3  Ground-contact bottom-edge heuristic. Alert when the box bottom falls in
      the lowest 20 percent of the frame inside a centre band. (This was our
      first prototype's rule, and is also the classic ground-plane proximity
      heuristic.)
  R4  Idealised monocular relative-depth estimator (MiDaS-style [26]). Alert
      when estimated depth in the central region < 2.5 m, with scale ambiguity
      and ground-plane confusion modelled.
  R5  Shipped VisionAI rule. Conjunction of corridor gate, background-width
      rejection, size gates, tau-based looming, and 2-frame confirmation.

These are reimplementations of published *criteria* under our stated simulation
assumptions. They are not reimplementations of any specific published system,
and no claim is made about any product's real-world performance.
"""

import json
import math
import numpy as np
from collections import deque
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

from sim_reflex import (
    CENTER_PATH_MIN_X, CENTER_PATH_MAX_X, MIN_CORRIDOR_OVERLAP, MAX_BOX_WIDTH,
    NEAR_HEIGHT, NEAR_AREA, ENTER_STREAK,
    LOOM_WINDOW_MS, MAX_SAMPLES, TTC_IMMINENT_SEC,
    VFOV, HFOV, FPS, DT, WALK_SPEED, K_V, K_H,
    height_frac, width_frac, frame_danger,
    BLUE, AQUA, ORANGE, RED, INK, SEC, MUTED, GRID, strip_spines,
)

FIGW = 6.3
JIT_DIM, JIT_POS = 0.10, 0.03
HAZARD_RADIUS = 2.5          # m, in-path object nearer than this is a hazard
HAZARD_HALF_WIDTH = 0.45     # m, half-width of the walking path
N_HIST = int(LOOM_WINDOW_MS / 1000.0 * FPS)


# ---------------------------------------------------------------- helpers
def tau_from_history(hist):
    """Optical time-to-contact from box expansion (Lee 1976). None if diverging."""
    if len(hist) < 2:
        return None
    t0, h0 = hist[0]
    t1, h1 = hist[-1]
    dt = (t1 - t0) / 1000.0
    if dt <= 0 or h0 <= 0:
        return None
    dh = h1 - h0
    if dh <= 0:
        return None
    return dt * h0 / dh          # tau = h / (dh/dt)


def ground_depth_at_center(cam_h, pitch):
    """Distance to the ground plane along the optical axis, or inf if above it."""
    if pitch <= 1e-3:
        return float("inf")
    return cam_h / math.tan(pitch)


# ---------------------------------------------------------------- the five rules
def rule_tau(frames):
    """R1. Pure tau criterion, no spatial gate."""
    hist = deque()
    for f in frames:
        hist.append((f["t_ms"], f["hf"]))
        while len(hist) > 1 and f["t_ms"] - hist[0][0] > LOOM_WINDOW_MS:
            hist.popleft()
        while len(hist) > MAX_SAMPLES:
            hist.popleft()
    tau = tau_from_history(hist)
    return tau is not None and 0.0 <= tau <= TTC_IMMINENT_SEC


def rule_size(frames):
    """R2. Fixed apparent-size threshold, no spatial gate, no confirmation."""
    return frames[-1]["hf"] >= NEAR_HEIGHT


def rule_bottom_edge(frames):
    """R3. Ground-contact bottom-edge heuristic inside a centre band."""
    f = frames[-1]
    return (0.30 <= f["cx"] <= 0.70) and f["bottom"] >= 0.80


def rule_depth(frames):
    """R4. Idealised monocular relative-depth estimator with scale ambiguity."""
    f = frames[-1]
    # Relative depth needs a scale assumption. Model residual scale error plus
    # ground-plane confusion when the camera is pitched down.
    d_obj = f["d"] if (0.30 <= f["cx"] <= 0.70) else float("inf")
    d_seen = min(d_obj, f["d_ground"])
    if not np.isfinite(d_seen):
        return False
    return d_seen * f["depth_scale_err"] < HAZARD_RADIUS


def rule_shipped(frames):
    """R5. Shipped conjunction with 2-frame confirmation."""
    hist = deque()
    prev = now = False
    for f in frames:
        prev = now
        now, _, _ = frame_danger(f["hf"], f["wf"], f["cx"], hist, f["t_ms"])
    return now and prev


RULES = [
    ("R1  Optical tau criterion [24]", rule_tau, ORANGE),
    ("R2  Fixed apparent-size gate", rule_size, "#eda100"),
    ("R3  Ground-contact bottom edge", rule_bottom_edge, "#4a3aa7"),
    ("R4  Monocular relative depth [26]", rule_depth, "#e87ba4"),
    ("R5  Shipped VisionAI rule", rule_shipped, BLUE),
]


# ---------------------------------------------------------------- scene model
def draw_noise(rng):
    """Pre-draw the detector noise for one scene so pitch can be varied alone."""
    return [dict(h=rng.normal(1.0, JIT_DIM), w=rng.normal(1.0, JIT_DIM),
                 x=rng.normal(0.0, JIT_POS), b=rng.normal(0.0, JIT_POS))
            for _ in range(N_HIST + 1)]


def make_frames(noise, d, H, Wm, x, cam_h, pitch, scale_err):
    """Render the last 0.9 s of an approach as noisy detector observations.

    Noise is supplied rather than drawn here, so the pitch-stability test can
    perturb pitch while holding the detector realisation fixed.
    """
    frames = []
    for k in range(N_HIST + 1):
        nz = noise[k]
        dd = d + WALK_SPEED * (N_HIST - k) * DT
        hf = max(1e-4, height_frac(H, dd) * nz["h"])
        wf = max(1e-4, min(1.0, width_frac(Wm, dd) * nz["w"]))
        cx = 0.5 + math.atan2(x, dd) / HFOV + nz["x"]
        alpha = math.atan2(cam_h, dd)
        bottom = min(1.0, max(0.0, 0.5 + (alpha - pitch) / VFOV + nz["b"]))
        frames.append(dict(t_ms=k * DT * 1000.0, hf=hf, wf=wf, cx=cx,
                           bottom=bottom, d=dd,
                           d_ground=ground_depth_at_center(cam_h, pitch),
                           depth_scale_err=scale_err))
    return frames


def sample_scene(rng, force_hazard=None):
    cam_h = rng.normal(1.3, 0.08)
    pitch = math.radians(rng.uniform(-10, 30))
    scale_err = float(np.exp(rng.normal(0.0, 0.30)))   # relative-depth scale ambiguity
    if force_hazard is True:
        d = rng.uniform(0.8, HAZARD_RADIUS)
        H = rng.uniform(0.3, 1.9)
        Wm = rng.uniform(0.15, 0.9)
        x = rng.uniform(-HAZARD_HALF_WIDTH, HAZARD_HALF_WIDTH)
        return d, H, Wm, x, cam_h, pitch, scale_err, True
    while True:
        if rng.random() < 0.25:
            d = rng.uniform(3.0, 10.0)
            H, Wm, x, is_bg = rng.uniform(1.5, 3.0), 30.0, 0.0, True
        else:
            d = rng.uniform(0.8, 9.0)
            H = rng.uniform(0.3, 1.9)
            Wm = rng.uniform(0.15, 0.9)
            x = rng.uniform(-2.5, 2.5)
            is_bg = False
        hazard = (not is_bg) and abs(x) <= HAZARD_HALF_WIDTH and d <= HAZARD_RADIUS
        if force_hazard is False and hazard:
            continue
        return d, H, Wm, x, cam_h, pitch, scale_err, hazard


# ---------------------------------------------------------------- evaluation
def evaluate(n_clear=16000, n_haz=8000, seed=53):
    rng = np.random.default_rng(seed)
    names = [n for n, _, _ in RULES]
    fa = {n: 0 for n in names}
    miss = {n: 0 for n in names}
    flip = {n: 0 for n in names}

    for _ in range(n_clear):
        d, H, Wm, x, cam_h, pitch, se, _ = sample_scene(rng, force_hazard=False)
        nz = draw_noise(rng)
        frames = make_frames(nz, d, H, Wm, x, cam_h, pitch, se)
        for name, fn, _ in RULES:
            fa[name] += fn(frames)
        # Pitch stability: identical scene and identical detector noise, pitch
        # perturbed by +/- 8 degrees. Only pitch differs between lo and hi.
        lo = make_frames(nz, d, H, Wm, x, cam_h, pitch - math.radians(8), se)
        hi = make_frames(nz, d, H, Wm, x, cam_h, pitch + math.radians(8), se)
        for name, fn, _ in RULES:
            flip[name] += (fn(lo) != fn(hi))

    for _ in range(n_haz):
        d, H, Wm, x, cam_h, pitch, se, _ = sample_scene(rng, force_hazard=True)
        nz = draw_noise(rng)
        frames = make_frames(nz, d, H, Wm, x, cam_h, pitch, se)
        for name, fn, _ in RULES:
            miss[name] += (not fn(frames))

    out = {}
    for name in names:
        out[name] = dict(fa=fa[name] / n_clear * 100,
                         miss=miss[name] / n_haz * 100,
                         flip=flip[name] / n_clear * 100)
    return out, n_clear, n_haz


def warning_distance(seed=71, trials=400):
    """Mean distance at first alert on a standard bollard approach, per rule."""
    rng = np.random.default_rng(seed)
    H, Wm = 0.9, 0.15
    res = {n: [] for n, _, _ in RULES}
    for _ in range(trials):
        cam_h = rng.normal(1.3, 0.08)
        pitch = math.radians(rng.uniform(-10, 30))
        se = float(np.exp(rng.normal(0.0, 0.30)))
        fired = {n: None for n, _, _ in RULES}
        # walk in from 6 m, evaluating the trailing window at each step
        for step in range(int((6.0 - 0.4) / (WALK_SPEED * DT))):
            d = 6.0 - step * WALK_SPEED * DT
            if d <= 0.4:
                break
            frames = make_frames(draw_noise(rng), d, H, Wm, 0.0, cam_h, pitch, se)
            for name, fn, _ in RULES:
                if fired[name] is None and fn(frames):
                    fired[name] = d
        for n in res:
            if fired[n] is not None:
                res[n].append(fired[n])
    return {n: (float(np.mean(v)) if v else None) for n, v in res.items()}


# ---------------------------------------------------------------- figure
SHORT = {
    "R1  Optical tau criterion [24]": "R1  Optical tau only [11]",
    "R2  Fixed apparent-size gate": "R2  Apparent size only",
    "R3  Ground-contact bottom edge": "R3  Ground-contact edge",
    "R4  Monocular relative depth [26]": "R4  Monocular depth [13]",
    "R5  Shipped VisionAI rule": "R5  Shipped rule (ours)",
}


def fig_baselines(path, res):
    """Four measures, five rules. R5 is highlighted with a tinted row band drawn
    behind the marks, so the emphasis can never overlap a label or a value."""
    panels = [
        ("fa", "False alarm rate on\nhazard-free scenes (%)"),
        ("mean_warn_dist_m", "Mean distance at\nfirst alert (m)"),
        ("flip", "Decisions changed by\n8 deg pitch shift (%)"),
        ("miss", "Miss rate on\nhazard scenes (%)"),
    ]
    names = [n for n, _, _ in RULES]
    ours = len(names) - 1                      # R5 is the last row
    ypos = np.arange(len(names))[::-1]
    fig, axes = plt.subplots(2, 2, figsize=(7.0, 4.6))

    for ax, (key, label) in zip(axes.ravel(), panels):
        vals = [res[n][key] for n in names]
        top = max(vals) * 1.30

        # highlight band behind everything, full panel width
        ax.axhspan(ypos[ours] - 0.46, ypos[ours] + 0.46,
                   xmin=0, xmax=1, color="#e4eefb", zorder=0)

        for y, n, v in zip(ypos, names, vals):
            is_ours = (n == names[ours])
            ax.barh(y, v, height=0.60, zorder=3,
                    color=BLUE if is_ours else "#ccd8e8",
                    edgecolor="#ffffff", linewidth=1.2)
            ax.text(v + top * 0.028, y,
                    ("%.1f" % v) if key != "mean_warn_dist_m" else ("%.2f" % v),
                    va="center", fontsize=8.6,
                    color=INK if is_ours else SEC,
                    fontweight="bold" if is_ours else "normal", zorder=4)

        ax.set_yticks(ypos)
        ax.set_yticklabels([SHORT[n] for n in names], fontsize=8.4, color=SEC)
        for lbl in ax.get_yticklabels():
            if lbl.get_text() == SHORT[names[ours]]:
                lbl.set_fontweight("bold")
                lbl.set_color(INK)
        ax.set_xlim(0, top)
        ax.set_xlabel(label, fontsize=8.8)
        ax.grid(axis="y", visible=False)
        ax.grid(axis="x", zorder=1)
        ax.tick_params(axis="x", labelsize=8)
        strip_spines(ax)

    fig.tight_layout(h_pad=1.8, w_pad=2.6)
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    import os
    out_dir = os.path.dirname(os.path.abspath(__file__))
    res, n_clear, n_haz = evaluate()
    wd = warning_distance()
    for name, _, _ in RULES:
        res[name]["mean_warn_dist_m"] = wd[name]
    fig_baselines(os.path.join(out_dir, "fig_baselines.png"), res)
    payload = dict(scenes=dict(hazard_free=n_clear, hazard=n_haz), rules=res)
    with open(os.path.join(out_dir, "sim_baseline_results.json"), "w") as f:
        json.dump(payload, f, indent=2)
    print(json.dumps(payload, indent=2))
