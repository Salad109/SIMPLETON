import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

JACCARD_THRESHOLD = 0.98

df = pd.read_csv('pareto_benchmark.csv')

safe_total = df['matched'] + df['safe_only']
df['coverage'] = np.where(safe_total > 0, df['matched'] / safe_total, 1.0)
df['fabrication'] = np.where(safe_total > 0, df['ours_only'] / safe_total, 0.0)

# Pareto frontier: maximize jaccard, minimize total_s
sorted_df = df.sort_values(['jaccard', 'total_s'], ascending=[False, True]).reset_index(drop=True)
frontier_mask = []
best_time = float('inf')
for _, row in sorted_df.iterrows():
    if row['total_s'] < best_time:
        frontier_mask.append(True)
        best_time = row['total_s']
    else:
        frontier_mask.append(False)
sorted_df['is_pareto'] = frontier_mask
frontier = sorted_df[sorted_df['is_pareto']].sort_values('jaccard', ascending=False)

baseline = df.iloc[0]
print(f"\nBaseline (safe config): {int(baseline['conj'])} conj, "
      f"jaccard={baseline['jaccard']:.4f}, {baseline['total_s']:.2f}s")
print(f"Total evaluated: {len(df)}")
print(f"Configs above {JACCARD_THRESHOLD} jaccard: {(df['jaccard'] >= JACCARD_THRESHOLD).sum()}")

print(f"\nPareto frontier ({len(frontier)} points):")
print(f"| Step | Stride | Cell  | Conj  | Matched | Ours-only | Safe-only | Jaccard | Coverage | Fabrication | Time   |")
print(f"|------|--------|-------|-------|---------|-----------|-----------|---------|----------|-------------|--------|")
for _, row in frontier.iterrows():
    print(f"| {int(row['step_ratio']):<4} | {int(row['interp_stride']):<6} | {row['cell_ratio']:<5.2f} "
          f"| {int(row['conj']):>5} | {int(row['matched']):>7} | {int(row['ours_only']):>9} "
          f"| {int(row['safe_only']):>9} | {row['jaccard']:>7.4f} | {row['coverage']:>8.4f} "
          f"| {row['fabrication']:>11.4f} | {row['total_s']:>5.2f}s |")

# Plot 1 - Jaccard vs Time scatter with Pareto frontier
fig, ax = plt.subplots(figsize=(12, 7))

ax.scatter(df['total_s'], df['jaccard'],
           c='#AAAAAA', s=60, alpha=0.6, label='Evaluated points', zorder=2)

ax.plot(frontier['total_s'], frontier['jaccard'],
        'o-', color='#D62839', linewidth=2, markersize=10,
        label='Pareto frontier', zorder=3)

for _, row in frontier.iterrows():
    label = f"s{int(row['step_ratio'])} i{int(row['interp_stride'])} c{row['cell_ratio']:.1f}"
    ax.annotate(label, (row['total_s'], row['jaccard']),
                textcoords='offset points', xytext=(8, -4), fontsize=7,
                color='#D62839')

ax.axhline(y=JACCARD_THRESHOLD, color='#FF9900', linestyle='--', alpha=0.7,
           label=f'{JACCARD_THRESHOLD} Jaccard threshold')

ax.set_ylim(JACCARD_THRESHOLD - 0.005, 1.002)
ax.set_xlim(right=frontier['total_s'].max() + 2)
ax.set_xlabel('Total Time (s)', fontsize=12)
ax.set_ylabel('Jaccard Index (vs safe baseline)', fontsize=12)
ax.set_title('Pareto Frontier: Speed vs Accuracy', fontsize=14, fontweight='bold')
ax.legend(fontsize=10)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('1_pareto_frontier.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 2 - Frontier parameter evolution as Jaccard decreases
f = frontier.sort_values('jaccard', ascending=False).reset_index(drop=True)

fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(12, 10), sharex=True)

ax1.plot(f['jaccard'], f['step_ratio'], 'o-', color='#2ca02c', markersize=8, linewidth=2)
ax1.set_ylabel('Step Ratio', fontsize=12)
ax1.set_title('Pareto Frontier: Parameter Evolution as Jaccard Decreases', fontsize=14, fontweight='bold')
ax1.grid(True, alpha=0.3)
ax1.invert_xaxis()

ax2.plot(f['jaccard'], f['interp_stride'], 'o-', color='#e377c2', markersize=8, linewidth=2)
ax2.set_ylabel('Interp Stride', fontsize=12)
ax2.grid(True, alpha=0.3)
ax2.invert_xaxis()

ax3.plot(f['jaccard'], f['cell_ratio'], 'o-', color='#17becf', markersize=8, linewidth=2)
ax3.set_xlabel('Jaccard Index', fontsize=12)
ax3.set_ylabel('Cell Ratio', fontsize=12)
ax3.grid(True, alpha=0.3)
ax3.invert_xaxis()

for ax in [ax1, ax2, ax3]:
    ax.axvline(x=JACCARD_THRESHOLD, color='#FF9900', linestyle='--', alpha=0.7)

plt.tight_layout()
plt.savefig('2_frontier_parameters.png', dpi=300, bbox_inches='tight')
plt.close()

timing_columns = ['propagator_s', 'sgp4_s', 'interp_s', 'check_s', 'grouping_s', 'refine_s', 'probability_s']
stack_colors = ['#2ca02c', '#06A77D', '#e377c2', '#17becf', '#9467bd', '#D62839', '#8c564b']
stack_labels = ['Propagator Build', 'SGP4', 'Interpolation', 'Check Pairs', 'Grouping', 'Refine', 'Probability']
markers = ['^', 'd', 'D', 'x', 'v', 'p', '*']

x_jac = f['jaccard'].values

# Plot 3 - Line per component along Pareto frontier
fig, ax = plt.subplots(figsize=(12, 7))
for col, color, marker, label in zip(timing_columns, stack_colors, markers, stack_labels):
    ax.plot(x_jac, f[col], marker=marker, linestyle='-', label=label,
            color=color, linewidth=2, markersize=8)
ax.invert_xaxis()
ax.set_xlabel('Jaccard Index', fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Pareto Frontier: Time Breakdown', fontsize=14, fontweight='bold')
ax.legend(fontsize=8, ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('3_time_breakdown.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 4 - Stacked area along Pareto frontier
fig, ax = plt.subplots(figsize=(12, 7))
y_stack = np.vstack([f[col].values for col in timing_columns])
ax.stackplot(x_jac, y_stack, labels=stack_labels, colors=stack_colors, alpha=0.8)
ax.invert_xaxis()
ax.set_xlabel('Jaccard Index', fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Pareto Frontier: Time Breakdown (Stacked)', fontsize=14, fontweight='bold')
ax.legend(fontsize=8, loc='upper left', ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('4_time_breakdown_stacked.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 5 - Jaccard heatmaps over (stride, cell_ratio), faceted by step_ratio.
# Drop step_ratios where pruning fired immediately and zero valid configs were found -
# they're just an empty panel that stretches the layout.
valid_step_ratios = sorted(
    sr for sr in df['step_ratio'].unique()
    if (df[(df['step_ratio'] == sr) & (df['jaccard'] >= JACCARD_THRESHOLD)]).shape[0] > 0
)
strides = sorted(df['interp_stride'].unique())
cell_ratios = sorted(df['cell_ratio'].unique())

n = len(valid_step_ratios)
ncols = min(n, 3)
nrows = int(np.ceil(n / ncols))
fig, axes = plt.subplots(nrows, ncols, figsize=(6 * ncols, 5 * nrows), squeeze=False)

HEATMAP_VMIN = 0.97
norm = matplotlib.colors.TwoSlopeNorm(
    vmin=HEATMAP_VMIN, vcenter=JACCARD_THRESHOLD, vmax=1.0)

for idx, sr in enumerate(valid_step_ratios):
    ax = axes[idx // ncols][idx % ncols]
    sub = df[df['step_ratio'] == sr]
    grid = (sub.pivot(index='cell_ratio', columns='interp_stride', values='jaccard')
                .reindex(index=cell_ratios, columns=strides))
    im = ax.imshow(grid.values, origin='lower', aspect='auto',
                   cmap='RdYlGn', norm=norm,
                   extent=(strides[0] - 2.5, strides[-1] + 2.5,
                           cell_ratios[0] - 0.05, cell_ratios[-1] + 0.05))
    ax.set_xticks(strides[::2])
    ax.set_yticks(cell_ratios[::2])
    ax.set_xlabel('Interp Stride')
    ax.set_ylabel('Cell Ratio')
    ax.set_title(f'step_ratio = {sr}', fontsize=12, fontweight='bold')

for idx in range(n, nrows * ncols):
    axes[idx // ncols][idx % ncols].axis('off')

cbar = fig.colorbar(im, ax=axes, shrink=0.85, pad=0.02, extend='min')
cbar.set_label(f'Jaccard')
fig.suptitle('Parameter-space accuracy: where the algorithm cliffs',
             fontsize=14, fontweight='bold')
plt.savefig('5_parameter_heatmap.png', dpi=300, bbox_inches='tight')
plt.close()

print(f"\nPlots saved: 1_pareto_frontier.png, 2_frontier_parameters.png, "
      f"3_time_breakdown.png, 4_time_breakdown_stacked.png, 5_parameter_heatmap.png")
