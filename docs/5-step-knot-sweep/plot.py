import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.colors import LogNorm

df = pd.read_csv('conjunction_benchmark.csv')

# The knot gap is rounded to whole steps, so each step lands near, not on, the swept value.
df['nominal_gap'] = (df['knot_gap_s'] / 50).round() * 50
steps = sorted(df['step_s'].unique())
gaps = sorted(df['nominal_gap'].unique())


def grid(col):
    return df.pivot_table(index='step_s', columns='nominal_gap', values=col).loc[steps, gaps]


missed, extra, total, actual = grid('safe_only'), grid('ours_only'), grid('total_s'), grid('knot_gap_s')

print(f"Evaluated {len(df)} points. Time {df['total_s'].min():.2f}-{df['total_s'].max():.2f}s.")
print("\nMissed / extra, total time (actual knot gap):")
print("| Step (s) | " + " | ".join(f"{g:.0f} s" for g in gaps) + " |")
print("|---|" + "---|" * len(gaps))
for s in steps:
    cells = [f"{missed.loc[s, g]:.0f} / {extra.loc[s, g]:.0f}, {total.loc[s, g]:.2f}s ({actual.loc[s, g]:.0f})"
             for g in gaps]
    print(f"| {s:g} | " + " | ".join(cells) + " |")

exact = df[(df['safe_only'] == 0) & (df['ours_only'] == 0)].sort_values('total_s')
print("\nExact replicas of the reference:")
for _, r in exact.iterrows():
    print(f"  {r['step_s']:g} s / {r['knot_gap_s']:.0f} s: {r['total_s']:.2f}s")

# Cheapest config at each accuracy level, counting missed and extra events alike
asc, best = df.sort_values('total_s'), float('inf')
print("\nFrontier (time, missed, extra, step, knot gap):")
for _, r in asc.iterrows():
    wrong = r['safe_only'] + r['ours_only']
    if wrong < best:
        best = wrong
        print(f"  {r['total_s']:.2f}s  {r['safe_only']:.0f} / {r['ours_only']:.0f}  "
              f"{r['step_s']:g} s / {r['knot_gap_s']:.0f} s")

x, y = np.arange(len(gaps)), np.arange(len(steps))


def heatmap(values, norm, label, fname, title, annotate):
    fig, ax = plt.subplots(figsize=(11, 6))
    im = ax.imshow(values.values, origin='lower', aspect='auto', cmap='Blues', norm=norm)
    for i, s in enumerate(steps):
        for j, g in enumerate(gaps):
            v = values.values[i, j]
            ax.text(j, i, annotate(s, g), ha='center', va='center', fontsize=9,
                    color='white' if norm(v) > 0.6 else '#333333')
    ax.set_xticks(x, [f'{g:.0f}' for g in gaps])
    ax.set_yticks(y, [f'{s:g}' for s in steps])
    ax.set_xlabel('Knot gap (s, rounded to whole steps)', fontsize=12)
    ax.set_ylabel('Step (s)', fontsize=12)
    ax.set_title(title, fontsize=14, fontweight='bold')
    fig.colorbar(im, ax=ax, label=label)
    plt.tight_layout()
    plt.savefig(fname, dpi=300, bbox_inches='tight')
    plt.close()


# 1 - accuracy; log colour on missed with 0 shown as the lightest cell, extras annotated alongside
heatmap(missed.clip(lower=0.5), LogNorm(vmin=0.5, vmax=missed.values.max()),
        'Missed events vs no-interpolation reference (log, 0 shown as 0.5)', '1_accuracy_heatmap.png',
        'Missed / Extra Events by Step and Knot Gap',
        lambda s, g: f"{missed.loc[s, g]:.0f} / {extra.loc[s, g]:.0f}")

# 2 - total time, median of the runs at each point
heatmap(total, plt.Normalize(total.values.min(), total.values.max()), 'Total time (s)',
        '2_time_heatmap.png', 'Total Time by Step and Knot Gap',
        lambda s, g: f"{total.loc[s, g]:.2f}")
