import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

JACCARD_THRESHOLD = 0.99

df = pd.read_csv('pareto_benchmark.csv')

# Pareto frontier: cheapest config at each accuracy level.
sorted_df = df.sort_values(['jaccard', 'total_s'], ascending=[False, True]).reset_index(drop=True)
mask, best_time = [], float('inf')
for _, row in sorted_df.iterrows():
    mask.append(row['total_s'] < best_time)
    best_time = min(best_time, row['total_s'])
frontier = sorted_df[mask].sort_values('jaccard', ascending=False).reset_index(drop=True)

print(f"Evaluated {len(df)} points, {(df['jaccard'] >= JACCARD_THRESHOLD).sum()} at or above "
      f"{JACCARD_THRESHOLD} Jaccard. Time {df['total_s'].min():.1f}-{df['total_s'].max():.1f}s.")

print(f"\nPareto frontier ({len(frontier)} points):")
print("| Step (s) | Knot Gap | Cell (km) | Stride | Conj | Missed | Extra | Jaccard | Time |")
print("|---|---|---|---|---|---|---|---|---|")
for _, r in frontier.iterrows():
    print(f"| {r['step_s']:.4g} | {r['knot_gap_s']:.0f}s | {r['cell_km']:.1f} "
          f"| {int(r['interp_stride'])} | {int(r['conj']):,} | {int(r['safe_only'])} "
          f"| {int(r['ours_only'])} | {r['jaccard']:.5f} | {r['total_s']:.1f}s |")

# What each accuracy step costs, which is the argument for where to sit on the frontier.
asc = frontier.sort_values('total_s').reset_index(drop=True)
print("\nMarginal cost along the frontier:")
print(f"{'time':>14}{'d_time':>9}{'missed':>14}{'recovered':>11}{'ms/event':>10}")
for i in range(1, len(asc)):
    a, b = asc.iloc[i - 1], asc.iloc[i]
    dt, saved = b['total_s'] - a['total_s'], a['safe_only'] - b['safe_only']
    per = f'{dt * 1000 / saved:10.1f}' if saved > 0 else f"{'-':>10}"
    print(f"{a['total_s']:6.1f} ->{b['total_s']:6.1f}{dt:9.2f}"
          f"{a['safe_only']:7.0f} ->{b['safe_only']:5.0f}{saved:11.0f}{per}")
span = asc['total_s'].max() - asc['total_s'].min()
print(f"\nFull frontier spans {span:.1f}s ({span / asc['total_s'].max() * 100:.0f}% of the slowest) "
      f"and {asc['safe_only'].max():.0f} to {asc['safe_only'].min():.0f} missed events.")

timing_columns = ['propagator_s', 'sgp4_s', 'interp_s', 'check_s', 'grouping_s', 'refine_s', 'probability_s']
colors = ['#2ca02c', '#06A77D', '#e377c2', '#17becf', '#9467bd', '#D62839', '#8c564b']
labels = ['Propagator Build', 'SGP4', 'Interpolation', 'Check Pairs', 'Grouping', 'Refine', 'Probability']
markers = ['^', 'd', 'D', 'x', 'v', 'p', '*']

# 1 - the frontier, twice. Jaccard is the metric of record but packs most of the frontier into the
# top 1% of the axis, so the right panel replots it as missed events on a log scale.
fig, (ax, ax2) = plt.subplots(1, 2, figsize=(16, 7))

ax.scatter(df['total_s'], df['jaccard'], c='#AAAAAA', s=55, alpha=0.55,
           label=f'Evaluated ({len(df)} points)', zorder=2)
ax.plot(frontier['total_s'], frontier['jaccard'], 'o-', color='#D62839',
        linewidth=2, markersize=9, label='Pareto frontier', zorder=3)
ax.axhline(y=JACCARD_THRESHOLD, color='#d4a34a', linestyle='--', linewidth=1.5,
           label=f'{JACCARD_THRESHOLD} Jaccard floor (grid pruned below)')
ax.set_ylim(JACCARD_THRESHOLD - 0.004, 1.0005)
ax.set_xlim(right=df['total_s'].max() + 2)
ax.set_xlabel('Total Time (s)', fontsize=12)
ax.set_ylabel('Jaccard Index (vs stride=1 ground truth)', fontsize=12)
ax.set_title(f'All {len(df)} Points', fontsize=12, fontweight='bold')
ax.legend(fontsize=9, loc='lower right')
ax.grid(True, alpha=0.3)

ax2.plot(frontier['total_s'], frontier['safe_only'], 'o-', color='#D62839',
         linewidth=2, markersize=9, zorder=3)
for _, r in frontier.iterrows():
    ax2.annotate(f"s{r['step_s']:.4g} g{r['knot_gap_s']:.0f} c{r['cell_km']:.0f}",
                 (r['total_s'], r['safe_only']), textcoords='offset points',
                 xytext=(7, 3), fontsize=8, color='#D62839')
ax2.set_yscale('log')
ax2.set_xlim(right=frontier['total_s'].max() + 2.5)
ax2.set_xlabel('Total Time (s)', fontsize=12)
ax2.set_ylabel('Missed events (log scale)', fontsize=12)
ax2.set_title('Frontier Only, Missed Events', fontsize=12, fontweight='bold')
ax2.grid(True, alpha=0.3, which='both')

fig.suptitle('Pareto Frontier: Speed vs Accuracy', fontsize=14, fontweight='bold')
plt.tight_layout()
plt.savefig('1_pareto_frontier.png', dpi=300, bbox_inches='tight')
plt.close()

# 2 - which knob the frontier trades as it gives up accuracy
fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(12, 10), sharex=True)
for ax, col, colour, ylabel in ((ax1, 'step_s', '#2ca02c', 'Step Size (s)'),
                                (ax2, 'knot_gap_s', '#e377c2', 'Knot Gap (s)'),
                                (ax3, 'cell_km', '#17becf', 'Cell Size (km)')):
    ax.plot(frontier['jaccard'], frontier[col], 'o-', color=colour, markersize=8, linewidth=2)
    ax.set_ylabel(ylabel, fontsize=12)
    ax.grid(True, alpha=0.3)
    ax.invert_xaxis()
ax1.set_title('Pareto Frontier: Parameter Evolution as Jaccard Decreases', fontsize=14, fontweight='bold')
ax3.set_xlabel('Jaccard Index', fontsize=12)
plt.tight_layout()
plt.savefig('2_frontier_parameters.png', dpi=300, bbox_inches='tight')
plt.close()

# 3 - line per component along the frontier
fig, ax = plt.subplots(figsize=(12, 7))
for col, colour, marker, label in zip(timing_columns, colors, markers, labels):
    ax.plot(frontier['jaccard'], frontier[col], marker=marker, linestyle='-',
            label=label, color=colour, linewidth=2, markersize=8)
ax.invert_xaxis()
ax.set_xlabel('Jaccard Index', fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Pareto Frontier: Time Breakdown', fontsize=14, fontweight='bold')
ax.legend(fontsize=10, ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('3_time_breakdown.png', dpi=300, bbox_inches='tight')
plt.close()

# 4 - stacked area along the frontier
fig, ax = plt.subplots(figsize=(12, 7))
ax.stackplot(frontier['jaccard'], np.vstack([frontier[c].values for c in timing_columns]),
             labels=labels, colors=colors, alpha=0.8)
ax.invert_xaxis()
ax.set_xlabel('Jaccard Index', fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Pareto Frontier: Time Breakdown Stacked', fontsize=14, fontweight='bold')
ax.legend(fontsize=8, loc='upper left', ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('4_time_breakdown_stacked.png', dpi=300, bbox_inches='tight')
plt.close()
