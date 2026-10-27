"""Compare our conjunction catalog against SOCRATES's.

Inputs (this directory): socrates.csv, ours.csv, active.txt, satellite_names.csv.
All cover the same 7-day window from 2026-05-09 19:00 UTC.
"""
import re
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
TCA_TOL_SEC = 60.0
WINDOW_START = pd.Timestamp("2026-05-09 19:00:00", tz="UTC")
WINDOW_HOURS = 168

CONSTELLATION_PATTERNS = [
    ("STARLINK",   re.compile(r"^STARLINK\b")),
    ("ONEWEB",     re.compile(r"^ONEWEB\b")),
    ("KUIPER",     re.compile(r"^KUIPER\b")),
    ("QIANFAN",    re.compile(r"^QIANFAN\b")),
    ("HULIANWANG", re.compile(r"^HULIANWANG\b")),
    ("GEESAT",     re.compile(r"^GEESAT\b")),
]


def canonical_pair(a, b):
    return np.minimum(a, b), np.maximum(a, b)


def load_socrates(path):
    df = pd.read_csv(path)
    df["norad_lo"], df["norad_hi"] = canonical_pair(df["NORAD_CAT_ID_1"], df["NORAD_CAT_ID_2"])
    df["tca"] = pd.to_datetime(df["TCA"], utc=True)
    df = df.rename(columns={
        "TCA_RANGE": "miss_distance_km",
        "TCA_RELATIVE_SPEED": "relative_speed_km_s",
        "MAX_PROB": "collision_probability",
    })
    return df[["norad_lo", "norad_hi", "tca", "miss_distance_km",
               "relative_speed_km_s", "collision_probability"]].copy()


def load_ours(path):
    df = pd.read_csv(path)
    df["norad_lo"], df["norad_hi"] = canonical_pair(df["norad1"], df["norad2"])
    df["tca"] = pd.to_datetime(df["tca"], utc=True)
    return df[["norad_lo", "norad_hi", "tca", "miss_distance_km",
               "relative_speed_km_s", "collision_probability"]].copy()


def match_events(soc, ours, tol_sec):
    """Per canonical pair, match each SOCRATES event to the temporally-closest ours
    event within tol_sec. merge_asof requires the `on` column globally sorted."""
    ours = ours.rename(columns={
        "miss_distance_km": "miss_ours_km",
        "relative_speed_km_s": "rel_speed_ours_kms",
        "collision_probability": "pc_ours",
    })
    ours["oid"] = ours.index
    ours["tca_ours"] = ours["tca"]

    soc = soc.rename(columns={
        "miss_distance_km": "miss_socrates_km",
        "relative_speed_km_s": "rel_speed_socrates_kms",
        "collision_probability": "pc_socrates",
    })
    soc["tca_socrates"] = soc["tca"]

    soc = soc.sort_values("tca").reset_index(drop=True)
    ours = ours.sort_values("tca").reset_index(drop=True)

    m = pd.merge_asof(
        soc, ours, on="tca", by=["norad_lo", "norad_hi"],
        tolerance=pd.Timedelta(seconds=tol_sec), direction="nearest",
    )

    matched_mask = m["oid"].notna()
    matched = m.loc[matched_mask].copy()
    matched["delta_tca_sec"] = (matched["tca_ours"] - matched["tca_socrates"]).dt.total_seconds()
    matched["delta_miss_km"] = (matched["miss_ours_km"].round(3) - matched["miss_socrates_km"]).round(3)
    matched["delta_rel_speed_kms"] = (matched["rel_speed_ours_kms"].round(3)
                                      - matched["rel_speed_socrates_kms"]).round(3)
    matched = matched[[
        "norad_lo", "norad_hi", "tca_socrates", "tca_ours", "delta_tca_sec",
        "miss_socrates_km", "miss_ours_km", "delta_miss_km",
        "rel_speed_socrates_kms", "rel_speed_ours_kms", "delta_rel_speed_kms",
        "pc_socrates", "pc_ours",
    ]]

    soc_un = m.loc[~matched_mask, [
        "norad_lo", "norad_hi", "tca",
        "miss_socrates_km", "rel_speed_socrates_kms", "pc_socrates",
    ]].rename(columns={
        "miss_socrates_km": "miss_distance_km",
        "rel_speed_socrates_kms": "relative_speed_km_s",
        "pc_socrates": "collision_probability",
    })

    used_oids = m.loc[matched_mask, "oid"].astype(np.int64).to_numpy()
    ours_un = ours.loc[~ours["oid"].isin(used_oids), [
        "norad_lo", "norad_hi", "tca", "miss_ours_km", "rel_speed_ours_kms", "pc_ours",
    ]].rename(columns={
        "miss_ours_km": "miss_distance_km",
        "rel_speed_ours_kms": "relative_speed_km_s",
        "pc_ours": "collision_probability",
    })
    return matched, soc_un, ours_un


def stats(label, x):
    if x.empty:
        return f"{label}: (empty)"
    return f"{label}: identical={(x == 0).mean() * 100:.1f}%  within 1={(x.abs() <= 1).mean() * 100:.1f}%"


def plot_error_histograms(matched, path):
    if matched.empty:
        return
    window = 20
    bins = np.arange(-window - 0.5, window + 1)
    ticks = np.arange(-window, window + 1, 5)
    labels = [f"≤{t}" if t == -window else f"≥{t}" if t == window else str(t) for t in ticks]
    specs = [
        (matched["delta_tca_sec"], "TCA Error (SIMPLETON - SOCRATES)", "ΔTCA (ms)"),
        (matched["delta_miss_km"], "Miss-Distance Error (SIMPLETON - SOCRATES)", "ΔMiss Distance (m)"),
    ]
    fig, axes = plt.subplots(2, 1, figsize=(9, 9))
    for ax, (values, title, xlabel) in zip(axes, specs):
        ax.hist((values * 1000).round().clip(-window, window), bins=bins,
                color="#2ca02c", edgecolor="black", linewidth=0.3)
        ax.axvline(0, color="black", linewidth=0.8, linestyle="--")
        ax.set_yscale("log")
        ax.set_ylim(bottom=1)
        ax.set_xticks(ticks, labels)
        ax.set_xlabel(xlabel, fontsize=12)
        ax.set_ylabel("Matched Events", fontsize=12)
        ax.set_title(title, fontsize=14, fontweight="bold")
        ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=300, bbox_inches="tight")
    plt.close(fig)


def load_active_norads(path):
    ids = set()
    for line in path.read_text().splitlines():
        if line.startswith("1 ") and len(line) >= 7:
            try:
                ids.add(int(line[2:7]))
            except ValueError:
                continue
    return ids


def load_constellation_map(path):
    names = pd.read_csv(path)
    names["object_name"] = names["object_name"].astype(str).str.strip().str.upper()
    def label(name):
        for k, pat in CONSTELLATION_PATTERNS:
            if pat.search(name):
                return k
        return "OTHER"
    return dict(zip(names["norad_cat_id"], names["object_name"].map(label)))


def apply_socrates_filters(df, primary_ids, con_map):
    """SOCRATES Plus: primary-vs-all minus intra-fleet conjunctions among fully
    operational satellites (proxy: both NORADs in active.txt). Also drops formation-flight
    pairs (relative velocity < 10 m/s) where the two pipelines count events differently."""
    df = df.loc[df["relative_speed_km_s"] >= 0.01].copy()
    is_primary = df["norad_lo"].isin(primary_ids) | df["norad_hi"].isin(primary_ids)
    df = df.loc[is_primary].copy()
    df["c_lo"] = df["norad_lo"].map(con_map).fillna("OTHER")
    df["c_hi"] = df["norad_hi"].map(con_map).fillna("OTHER")
    same_fleet = (df["c_lo"] == df["c_hi"]) & (df["c_lo"] != "OTHER")
    both_active = df["norad_lo"].isin(primary_ids) & df["norad_hi"].isin(primary_ids)
    return df.loc[~(same_fleet & both_active)].reset_index(drop=True)


def run_match(soc, ours):
    matched, soc_un, ours_un = match_events(soc, ours, TCA_TOL_SEC)
    n_match, n_soc, n_ours = len(matched), len(soc), len(ours)
    print(f"  SIMPLETON events                   : {n_ours:>8,}")
    print(f"  SOCRATES events                    : {n_soc:>8,}")
    print(f"  Matched                            : {n_match:>8,}")
    print(f"  SIMPLETON only                     : {len(ours_un):>8,}")
    print(f"  SOCRATES only                      : {len(soc_un):>8,}")
    print(f"  % of SIMPLETON SOCRATES also flags : {n_match/n_ours*100:.1f}%")
    print(f"  % of SOCRATES SIMPLETON also flags : {n_match/n_soc*100:.1f}%")
    return matched, soc_un, ours_un


def time_bucket_overlap(soc, ours, window_start, bucket_hours, n_buckets):
    rows = []
    for i in range(n_buckets):
        lo = window_start + pd.Timedelta(hours=i * bucket_hours)
        hi = window_start + pd.Timedelta(hours=(i + 1) * bucket_hours)
        soc_b = soc[(soc["tca"] >= lo) & (soc["tca"] < hi)]
        ours_b = ours[(ours["tca"] >= lo) & (ours["tca"] < hi)]
        if soc_b.empty and ours_b.empty:
            continue
        matched, _, _ = match_events(soc_b, ours_b, TCA_TOL_SEC)
        rows.append({
            "day": i + 1,
            "soc": len(soc_b),
            "ours": len(ours_b),
            "matched": len(matched),
            "ours_in_soc": len(matched) / len(ours_b) if len(ours_b) else float("nan"),
            "soc_in_ours": len(matched) / len(soc_b) if len(soc_b) else float("nan"),
        })
    df = pd.DataFrame(rows)
    print(f"  {'day':<6} {'SOCRATES':>10} {'SIMPLETON':>10} {'matched':>10} {'% of SIMPLETON':>15} {'% of SOC':>10}")
    for r in df.itertuples(index=False):
        print(f"  {r.day:<6} {r.soc:>10,} {r.ours:>10,} {r.matched:>10,} "
              f"{r.ours_in_soc*100:>14.1f}% {r.soc_in_ours*100:>9.1f}%")
    return df


def plot_missed_events(missed, matched, path):
    if missed.empty:
        return
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.scatter(matched["rel_speed_socrates_kms"], matched["miss_socrates_km"],
               s=1.5, color="black", alpha=0.25, linewidths=0, rasterized=True,
               label=f"Matched (n={len(matched):,})")
    ax.scatter(missed["relative_speed_km_s"], missed["miss_distance_km"],
               s=22, color="#d4a34a", edgecolor="black", linewidth=0.4, zorder=3,
               label=f"SOCRATES-only (n={len(missed)})")
    ax.axhline(5, color="black", linewidth=0.8, linestyle="--")
    ax.set_xlabel("SOCRATES Relative Velocity (km/s)", fontsize=12)
    ax.set_ylabel("SOCRATES Miss Distance (km)", fontsize=12)
    ax.set_title("SOCRATES-Only Events Against the Matched Population", fontsize=14, fontweight="bold")
    ax.set_ylim(0, 5.1)
    legend = ax.legend(loc="lower left", bbox_to_anchor=(0.06, 0.01))
    legend.legend_handles[0].set_alpha(1)
    legend.legend_handles[0].set_sizes([10])
    fig.tight_layout()
    fig.savefig(path, dpi=300, bbox_inches="tight")
    plt.close(fig)


def main():
    primary_ids = load_active_norads(HERE / "active.txt")
    con_map = load_constellation_map(HERE / "satellite_names.csv")

    soc = apply_socrates_filters(load_socrates(HERE / "socrates.csv"), primary_ids, con_map)
    ours = apply_socrates_filters(load_ours(HERE / "ours.csv"), primary_ids, con_map)

    print(f"  SOCRATES events: {len(soc):>8,}")
    print(f"  SIMPLETON      : {len(ours):>8,}")

    print(f"\nEvent-level matching (|dTCA| <= {TCA_TOL_SEC:.0f} s)")
    matched, soc_un, _ = run_match(soc, ours)

    print(f"\nPer-day overlap")
    time_bucket_overlap(soc, ours, WINDOW_START, 24, WINDOW_HOURS // 24)

    plot_missed_events(soc_un, matched, HERE / "2_missed_events.png")

    if not matched.empty:
        print(f"\nErrors on matched events")
        print(f"  {stats('  dTCA (ms)        ', (matched['delta_tca_sec'] * 1000).round())}")
        print(f"  {stats('  d miss-dist (m)  ', (matched['delta_miss_km'] * 1000).round())}")
        print(f"  {stats('  d rel-speed (m/s)', (matched['delta_rel_speed_kms'] * 1000).round())}")
        plot_error_histograms(matched, HERE / "1_errors.png")


if __name__ == "__main__":
    sys.exit(main())
