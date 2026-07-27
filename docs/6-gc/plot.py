import pandas as pd
import matplotlib.pyplot as plt
import glob

csv_files = sorted(glob.glob('gc_benchmark_*.csv'))
dfs = {}
for f in csv_files:
    name = f.replace('gc_benchmark_', '').replace('.csv', '')
    dfs[name] = pd.read_csv(f)

gc_names = list(dfs.keys())
gc_colors = ['#2E86AB', '#D62839', '#06A77D', '#F77F00'][:len(gc_names)]

# Print table
print(f"| {'GC':<12} | Mean Time | Std Dev | Min    | Max    | Conjunctions |")
print(f"|{'-'*14}|-----------|---------|--------|--------|--------------|")
for name in gc_names:
    df = dfs[name]
    print(f"| {name:<12} | {df['total_s'].mean():>8.2f}s | {df['total_s'].std():>6.2f}s "
          f"| {df['total_s'].min():>5.2f}s | {df['total_s'].max():>5.2f}s | {int(df['conj'].mean()):>12} |")

timing_columns = ['propagator_s', 'sgp4_s', 'interp_s', 'check_s', 'grouping_s', 'refine_s', 'probability_s']
colors = ['#2ca02c', '#06A77D', '#e377c2', '#17becf', '#9467bd', '#D62839', '#8c564b']
labels = ['Propagator Build', 'SGP4', 'Interpolation', 'Check Pairs', 'Grouping', 'Refine', 'Probability']

# Plot 1 - Total time: box plot (left) + mean bar (right)
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

box_data = [dfs[name]['total_s'].values for name in gc_names]
bp = ax1.boxplot(box_data, tick_labels=gc_names, patch_artist=True)
for patch, color in zip(bp['boxes'], gc_colors):
    patch.set_facecolor(color)
    patch.set_alpha(0.7)
ax1.set_ylabel('Total Time (s)', fontsize=12)
ax1.set_title('Total Time Distribution by GC', fontsize=14, fontweight='bold')
ax1.grid(True, alpha=0.3, axis='y')

means = [dfs[name]['total_s'].mean() for name in gc_names]
bars = ax2.bar(range(len(gc_names)), means, color=gc_colors, alpha=0.8)
ax2.set_xticks(range(len(gc_names)))
ax2.set_xticklabels(gc_names)
ax2.set_ylabel('Mean Total Time (s)', fontsize=12)
ax2.set_title('Mean Total Processing Time by GC', fontsize=14, fontweight='bold')
ax2.grid(True, alpha=0.3, axis='y')
for bar, val in zip(bars, means):
    ax2.text(bar.get_x() + bar.get_width()/2., bar.get_height(),
             f'{val:.2f}s', ha='center', va='bottom', fontsize=10)

plt.tight_layout()
plt.savefig('1_total_time.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 2 - Grouped bar breakdown
fig, ax = plt.subplots(figsize=(14, 6))
n_gc = len(gc_names)
n_cols = len(timing_columns)
width = 0.8 / n_cols
x = range(n_gc)
for i, (col, color, label) in enumerate(zip(timing_columns, colors, labels)):
    vals = [dfs[name][col].mean() for name in gc_names]
    offset = (i - n_cols / 2 + 0.5) * width
    ax.bar([xi + offset for xi in x], vals, width, color=color, label=label, alpha=0.8)
ax.set_xticks(x)
ax.set_xticklabels(gc_names)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Mean Time Breakdown by GC', fontsize=14, fontweight='bold')
ax.legend(fontsize=10, loc='upper left', ncol=2)
ax.grid(True, alpha=0.3, axis='y')
plt.tight_layout()
plt.savefig('2_time_breakdown.png', dpi=300, bbox_inches='tight')
plt.close()
