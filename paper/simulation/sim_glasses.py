"""
Phone camera vs. Ray-Ban Meta glasses camera, as seen by the shipped reflex.

Question: if the frames that feed the reflex come from the glasses (via the
Meta Wearables Device Access Toolkit) instead of a chest-held phone, does the
safety reflex get better or worse, and what has to change?

Three configurations run over identical scenes and identical detector noise
(common random numbers, so differences are caused by the configuration alone):

  PHONE      the configuration the paper reports: 46 deg horizontal and
             60 deg vertical field of view, shipped thresholds.
  GLASSES    glasses geometry with the phone-tuned thresholds left unchanged,
             which is what a naive integration would ship.
  GLASSES+R  glasses geometry with every frame-fraction threshold rescaled so
             it keeps the same physical meaning (same corridor half-angle,
             same object-size-to-distance ratio for "near").

The glasses field of view is NOT an official figure. The stream is portrait
9:16 (the toolkit delivers 504x896 or 720x1280), and one review reports a
100 deg ultra-wide lens. If that is a diagonal figure, a 9:16 portrait crop
gives roughly 61 deg horizontal and 92 deg vertical. Because this is
unverified, the horizontal field of view is also swept.

Bluetooth transport latency is unknown until measured on hardware. It delays
every alert by the same amount, so its cost is reported in closed form.
"""

import json
import math
import os
from collections import deque

import matplotlib.pyplot as plt
import numpy as np

import sim_reflex as R

# ---------------------------------------------------------------- geometry
def kv(vfov_deg):
    return 2.0 * math.tan(math.radians(vfov_deg) / 2.0)


def kh(hfov_deg):
    return 2.0 * math.tan(math.radians(hfov_deg) / 2.0)


def portrait_fov_from_diagonal(diag_deg, w=9.0, h=16.0):
    """Split a diagonal field of view into horizontal and vertical for a w:h frame."""
    t = math.tan(math.radians(diag_deg) / 2.0)
    d = math.hypot(w, h)
    return (math.degrees(2 * math.atan(t * w / d)),
            math.degrees(2 * math.atan(t * h / d)))


PHONE = dict(name="Phone", hfov=46.0, vfov=60.0)
G_H, G_V = portrait_fov_from_diagonal(100.0)          # about 60.6 x 92.2 deg


# ---------------------------------------------------------------- thresholds
def shipped_thresholds():
    return dict(cmin=R.CENTER_PATH_MIN_X, cmax=R.CENTER_PATH_MAX_X,
                near_h=R.NEAR_HEIGHT, near_a=R.NEAR_AREA,
                loom_h=R.MIN_LOOM_HEIGHT, loom_a=R.MIN_LOOM_AREA,
                max_w=R.MAX_BOX_WIDTH)


def rescaled_thresholds(hfov, vfov):
    """Keep each gate's physical meaning when the field of view changes.

    Frame fractions mean different physical things under different lenses. A box
    that is 34 percent of a 60 deg frame is a bigger object than 34 percent of a
    92 deg frame, and the corridor 0.32 to 0.68 spans a wider angle in a wider
    lens. This maps every fraction-based gate back to the phone's physical
    meaning. Growth rate and time to contact are ratios, so they need no change.
    """
    p = PHONE
    half = (R.CENTER_PATH_MAX_X - 0.5) * p["hfov"]       # corridor half-angle, deg
    s_h = kv(p["vfov"]) / kv(vfov)                        # height-fraction scale
    s_a = s_h * kh(p["hfov"]) / kh(hfov)                  # area-fraction scale
    return dict(cmin=0.5 - half / hfov, cmax=0.5 + half / hfov,
                near_h=R.NEAR_HEIGHT * s_h, near_a=R.NEAR_AREA * s_a,
                loom_h=R.MIN_LOOM_HEIGHT * s_h, loom_a=R.MIN_LOOM_AREA * s_a,
                max_w=R.MAX_BOX_WIDTH)


# ---------------------------------------------------------------- decision rule
def is_looming(hist, curr_h, curr_a, th):
    """sim_reflex.is_looming with the two size minimums parameterised."""
    if hist is None or len(hist) < 2:
        return False
    if curr_h < th["loom_h"] and curr_a < th["loom_a"]:
        return False
    t0, h0 = hist[0]
    t1, h1 = hist[-1]
    dt = (t1 - t0) / 1000.0
    if dt <= 0 or h0 <= 0:
        return False
    dh = h1 - h0
    if dh <= 0:
        return False
    if (dh / h0) / dt >= R.LOOM_GROWTH_PER_SEC:
        return True
    return 0.0 <= dt * h0 / dh <= R.TTC_IMMINENT_SEC


def frame_danger(hf, wf, cx, hist, t_ms, th):
    """sim_reflex.frame_danger with every frame-fraction threshold parameterised."""
    if wf >= th["max_w"]:
        return False
    af = hf * wf
    left, right = cx - wf / 2.0, cx + wf / 2.0
    center_in = th["cmin"] <= cx <= th["cmax"]
    overlap = min(right, th["cmax"]) - max(left, th["cmin"])
    if not center_in and overlap / (th["cmax"] - th["cmin"]) < R.MIN_CORRIDOR_OVERLAP:
        return False
    hist.append((t_ms, hf))
    while len(hist) > 1 and t_ms - hist[0][0] > R.LOOM_WINDOW_MS:
        hist.popleft()
    while len(hist) > R.MAX_SAMPLES:
        hist.popleft()
    loom = is_looming(hist, hf, af, th)
    return (hf >= th["near_h"] or af >= th["near_a"]) or loom


def parity_check(n=4000, seed=5):
    """With phone thresholds, our rule must match sim_reflex.frame_danger exactly."""
    rng = np.random.default_rng(seed)
    th = shipped_thresholds()
    mism = 0
    for _ in range(n):
        h1, h2 = deque(), deque()
        for k in range(R.MAX_SAMPLES + 2):
            hf, wf, cx = rng.uniform(0.02, 1.0), rng.uniform(0.02, 1.0), rng.uniform(-0.2, 1.2)
            t = k * 100.0
            a, _, _ = R.frame_danger(hf, wf, cx, h1, t)
            b = frame_danger(hf, wf, cx, h2, t, th)
            mism += (a != b)
    return mism


# ---------------------------------------------------------------- scenes
N_HIST = int(R.LOOM_WINDOW_MS / 1000.0 * R.FPS)


def scenes(n_clear, n_haz, seed):
    """Scene parameters plus per-frame noise, drawn once and reused by every config."""
    rng = np.random.default_rng(seed)
    out = []
    while sum(1 for s in out if not s["haz"]) < n_clear:
        if rng.random() < 0.25:
            s = dict(d=rng.uniform(3, 10), H=rng.uniform(1.5, 3.0), W=30.0, x=0.0, haz=False)
        else:
            s = dict(d=rng.uniform(0.8, 9), H=rng.uniform(0.3, 1.9),
                     W=rng.uniform(0.15, 0.9), x=rng.uniform(-2.5, 2.5), haz=False)
            if abs(s["x"]) <= 0.45 and s["d"] <= 2.5:
                continue
        out.append(s)
    for _ in range(n_haz):
        out.append(dict(d=rng.uniform(0.8, 2.5), H=rng.uniform(0.3, 1.9),
                        W=rng.uniform(0.15, 0.9), x=rng.uniform(-0.45, 0.45), haz=True))
    for s in out:
        s["nh"] = rng.normal(1.0, 0.10, N_HIST + 1)
        s["nw"] = rng.normal(1.0, 0.10, N_HIST + 1)
        s["nx"] = rng.normal(0.0, 0.03, N_HIST + 1)
    return out


def run_scene(s, hfov, vfov, th):
    hist = deque()
    prev = now = False
    for k in range(N_HIST + 1):
        dd = s["d"] + R.WALK_SPEED * (N_HIST - k) * R.DT
        hf = max(1e-4, min(1.0, s["H"] / (kv(vfov) * dd)) * s["nh"][k])
        wf = max(1e-4, min(1.0, min(1.0, s["W"] / (kh(hfov) * dd)) * s["nw"][k]))
        cx = 0.5 + math.atan2(s["x"], dd) / math.radians(hfov) + s["nx"][k]
        prev, now = now, frame_danger(hf, wf, cx, hist, k * R.DT * 1000.0, th)
    return now and prev                                   # shipped 2-frame confirmation


def evaluate(cfg, th, sc):
    fa = miss = nf = nh = 0
    off_miss = off_n = 0
    for s in sc:
        alert = run_scene(s, cfg["hfov"], cfg["vfov"], th)
        if s["haz"]:
            nh += 1
            miss += not alert
            if s["d"] < 1.5 and abs(s["x"]) > 0.25:        # close and off to one side
                off_n += 1
                off_miss += not alert
        else:
            nf += 1
            fa += alert
    return dict(fa=100.0 * fa / nf, miss=100.0 * miss / nh,
                off_axis_miss=100.0 * off_miss / max(1, off_n), off_axis_n=off_n)


def first_alert_distance(cfg, th, H=0.9, W=0.15, trials=300, seed=71):
    """Mean distance at first alert walking straight at a 0.9 m bollard."""
    rng = np.random.default_rng(seed)
    dists = []
    for _ in range(trials):
        hist = deque()
        prev = now = False
        d = 6.0
        k = 0
        while d > 0.4:
            hf = max(1e-4, min(1.0, H / (kv(cfg["vfov"]) * d)) * rng.normal(1.0, 0.10))
            wf = max(1e-4, min(1.0, W / (kh(cfg["hfov"]) * d)) * rng.normal(1.0, 0.10))
            cx = 0.5 + rng.normal(0.0, 0.03)
            prev, now = now, frame_danger(hf, wf, cx, hist, k * R.DT * 1000.0, th)
            if now and prev:
                dists.append(d)
                break
            d -= R.WALK_SPEED * R.DT
            k += 1
    return float(np.mean(dists)) if dists else 0.0


# ---------------------------------------------------------------- figure
def figure(path, res, sweep, lat):
    BLUE, AQUA, ORANGE = R.BLUE, R.AQUA, R.ORANGE
    names = ["Phone", "Glasses,\nphone thresholds", "Glasses,\nrescaled"]
    keys = ["PHONE", "GLASSES", "GLASSES_R"]
    cols = ["#9aa3ad", ORANGE, BLUE]
    fig, axes = plt.subplots(2, 2, figsize=(7.4, 5.2))

    def bars(ax, metric, label, fmt):
        vals = [res[k][metric] for k in keys]
        b = ax.bar(range(3), vals, color=cols, width=0.62, zorder=3, edgecolor="white")
        for r, v in zip(b, vals):
            ax.text(r.get_x() + r.get_width() / 2, v + max(vals) * 0.03, fmt % v,
                    ha="center", fontsize=8.5, color=R.INK)
        ax.set_xticks(range(3))
        ax.set_xticklabels(names, fontsize=8)
        ax.set_ylabel(label, fontsize=8.8)
        ax.set_ylim(0, max(vals) * 1.25)
        ax.grid(axis="x", visible=False)
        R.strip_spines(ax)

    bars(axes[0, 0], "fa", "False alarm rate (%)", "%.1f")
    bars(axes[0, 1], "off_axis_miss", "Miss rate, close off-axis\nhazards (%)", "%.1f")
    bars(axes[1, 0], "warn_m", "First alert distance,\n0.9 m bollard (m)", "%.2f")

    ax = axes[1, 1]
    ax.plot(lat * 1000, 2.0 - lat, color=BLUE, lw=2, zorder=3)
    ax.axhline(2.0, color="#9aa3ad", lw=1, ls=(0, (4, 3)))
    ax.text(5, 2.03, "phone camera, no transport delay", fontsize=7.8, color=R.SEC)
    ax.set_xlabel("Bluetooth transport latency (ms)", fontsize=8.8)
    ax.set_ylabel("Warning time on the\nlooming path (s)", fontsize=8.8)
    ax.set_ylim(1.5, 2.15)
    R.strip_spines(ax)

    fig.tight_layout(h_pad=2.0, w_pad=2.4)
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ---------------------------------------------------------------- main
if __name__ == "__main__":
    out = os.path.dirname(os.path.abspath(__file__))
    mism = parity_check()
    print("parity check vs sim_reflex.frame_danger: %d mismatches" % mism)
    assert mism == 0, "parameterised rule diverges from the shipped rule"

    GLASSES = dict(name="Glasses", hfov=G_H, vfov=G_V)
    sc = scenes(12000, 6000, seed=97)
    res = {}
    for key, cfg, th in [("PHONE", PHONE, shipped_thresholds()),
                         ("GLASSES", GLASSES, shipped_thresholds()),
                         ("GLASSES_R", GLASSES, rescaled_thresholds(G_H, G_V))]:
        r = evaluate(cfg, th, sc)
        r["warn_m"] = first_alert_distance(cfg, th)
        r["thresholds"] = {k: round(v, 4) for k, v in th.items()}
        res[key] = r

    sweep = {}
    for h in [50.0, 60.6, 70.0, 80.0]:
        v = math.degrees(2 * math.atan(math.tan(math.radians(h) / 2) * 16.0 / 9.0))
        cfg = dict(hfov=h, vfov=v)
        r = evaluate(cfg, rescaled_thresholds(h, v), sc)
        sweep["%.1f" % h] = dict(vfov=round(v, 1), **{k: round(r[k], 2) for k in ("fa", "miss", "off_axis_miss")})

    lat = np.linspace(0.0, 0.3, 31)
    figure(os.path.join(out, "fig_glasses.png"), res, sweep, lat)

    payload = dict(glasses_fov_deg=dict(h=round(G_H, 1), v=round(G_V, 1),
                                        basis="100 deg diagonal, 9:16 portrait, unverified"),
                   configs=res, hfov_sweep_rescaled=sweep,
                   latency_note="each 100 ms of transport latency removes 0.1 s and 0.14 m of warning at 1.4 m/s")
    with open(os.path.join(out, "sim_glasses_results.json"), "w") as f:
        json.dump(payload, f, indent=2)
    print(json.dumps(payload, indent=2))
