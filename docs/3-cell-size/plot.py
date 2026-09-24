import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

df = pd.read_csv('conjunction_benchmark.csv')
param = 'cell_km'
param_label = 'Cell Size (km)'
avg = df.groupby(param).mean(numeric_only=True).reset_index()
sd = df.groupby(param)['total_s'].std()

THRESHOLD_KM = 5.0


def v_guar(row):
    radius = min(row['cell_km'], row['tolerance_km'])
    return 2 * np.sqrt(radius ** 2 - THRESHOLD_KM ** 2) / row['step_s']

print("| Cell (km) | Conjunctions | Jaccard | Missed | v_guar | Total Time |")
print("|---|---|---|---|---|---|")
for _, row in avg.sort_values(param, ascending=False).iterrows():
    print(f"| {row[param]:.0f} | {int(round(row['conj'])):,} | {row['jaccard']:.5f} | "
          f"{int(round(row['safe_only']))} | {v_guar(row):.1f} km/s | {row['total_s']:.1f}s |")

timing_columns = ['propagator_s', 'sgp4_s', 'interp_s', 'check_s', 'grouping_s', 'refine_s', 'probability_s']
colors = ['#2ca02c', '#06A77D', '#e377c2', '#17becf', '#9467bd', '#D62839', '#8c564b']
labels = ['Propagator Build', 'SGP4', 'Interpolation', 'Check Pairs', 'Grouping', 'Refine', 'Probability']
markers = ['^', 'd', 'D', 'x', 'v', 'p', '*']

# 1 - total time
fig, ax = plt.subplots(figsize=(10, 6))
ax.errorbar(avg[param], avg['total_s'], yerr=sd.values, fmt='o-', color='#2E86AB',
            markersize=7, capsize=3, linewidth=2)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Total Time (s)', fontsize=12)
ax.set_title('Total Processing Time vs Cell Size', fontsize=14, fontweight='bold')
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('1_total_time.png', dpi=300, bbox_inches='tight')
plt.close()

# 2 - line per component
fig, ax = plt.subplots(figsize=(12, 7))
for col, color, marker, label in zip(timing_columns, colors, markers, labels):
    ax.plot(avg[param], avg[col], marker=marker, linestyle='-', label=label,
            color=color, linewidth=2, markersize=8)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Time Breakdown by Cell Size', fontsize=14, fontweight='bold')
ax.legend(fontsize=10, ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('2_time_breakdown.png', dpi=300, bbox_inches='tight')
plt.close()

# 3 - stacked area
fig, ax = plt.subplots(figsize=(12, 7))
ax.stackplot(avg[param], np.vstack([avg[c].values for c in timing_columns]),
             labels=labels, colors=colors, alpha=0.8)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Time Breakdown Stacked by Cell Size', fontsize=14, fontweight='bold')
ax.legend(fontsize=8, loc='upper left', ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('3_time_breakdown_stacked.png', dpi=300, bbox_inches='tight')
plt.close()

# 4 - accuracy
fig, ax = plt.subplots(figsize=(10, 6))
ax.plot(avg[param], avg['jaccard'], 'o-', color='#2E86AB', linewidth=2, markersize=7)
ax.set_ylim(min(avg['jaccard'].min() - 0.005, 0.98), 1.001)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Jaccard vs stride=1 baseline', fontsize=12)
ax.set_title('Accuracy vs Cell Size', fontsize=14, fontweight='bold')
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('4_accuracy.png', dpi=300, bbox_inches='tight')
plt.close()
