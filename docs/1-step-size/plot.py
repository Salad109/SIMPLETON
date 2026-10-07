import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

df = pd.read_csv('conjunction_benchmark.csv')
param = 'step_s'
param_label = 'Step Size (s)'
avg = df.groupby(param).mean(numeric_only=True).reset_index()
sd = df.groupby(param)['total_s'].std()

print("| Step (s) | Tolerance | Knot Gap | Conjunctions | Jaccard | Missed | Extra | Miss err p99 | Total Time |")
print("|---|---|---|---|---|---|---|---|---|")
for _, row in avg.iterrows():
    print(f"| {row[param]:.3f} | {row['tolerance_km']:.1f} km | {row['knot_gap_s']:.1f} s | "
          f"{int(round(row['conj'])):,} | {row['jaccard']:.5f} | {int(round(row['safe_only']))} | "
          f"{int(round(row['ours_only']))} | "
          f"{row['miss_err_p99_m']:.3f} m | {row['total_s']:.1f}s |")

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
ax.set_title('Total Processing Time vs Step Size', fontsize=14, fontweight='bold')
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
ax.set_title('Time Breakdown by Step Size', fontsize=14, fontweight='bold')
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
ax.set_title('Time Breakdown Stacked by Step Size', fontsize=14, fontweight='bold')
ax.legend(fontsize=8, loc='upper left', ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('3_time_breakdown_stacked.png', dpi=300, bbox_inches='tight')
plt.close()

# 4 - accuracy
fig, ax = plt.subplots(figsize=(10, 6))
ax.plot(avg[param], avg['jaccard'], 'o-', color='#2E86AB', linewidth=2, markersize=7)
pad = max(2e-5, (1 - avg['jaccard'].min()) * 0.1)
ax.set_ylim(avg['jaccard'].min() - pad, 1 + pad)
ax.ticklabel_format(axis='y', useOffset=False)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Jaccard vs no-interpolation reference', fontsize=12)
ax.set_title('Accuracy vs Step Size', fontsize=14, fontweight='bold')
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('4_accuracy.png', dpi=300, bbox_inches='tight')
plt.close()
