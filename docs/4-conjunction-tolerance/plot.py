import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from scipy.optimize import curve_fit, minimize_scalar

df = pd.read_csv('conjunction_benchmark.csv')
param = 'tolerance_km'
param_label = 'Tolerance (km)'
avg = df.groupby(param).mean(numeric_only=True).reset_index()

# Print table
baseline = avg['conj'].max()
print(f"| Tolerance (km) | Conjunctions | Accuracy | Loss  | Total Time |")
print(f"|----------------|--------------|----------|-------|------------|")
for _, row in avg.iterrows():
    tol = int(row[param])
    conj = int(round(row['conj']))
    accuracy = row['conj'] / baseline * 100
    loss = 100 - accuracy
    time = row['total_s']
    print(f"| {tol:<14} | {conj:>12,} | {accuracy:>7.2f}% | {loss:>5.2f}% | {time:.1f}s{'':<5} |")

timing_columns = ['propagator_s', 'sgp4_s', 'interp_s', 'check_s', 'grouping_s', 'refine_s', 'probability_s']
colors = ['#2ca02c', '#06A77D', '#e377c2', '#17becf', '#9467bd', '#D62839', '#8c564b']
labels = ['Propagator Build', 'SGP4', 'Interpolation', 'Check Pairs', 'Grouping', 'Refine', 'Probability']

def model(x, a, b, c, d):
    return a / x + b * x + c * x**2 + d

x = avg[param].values
y = avg['total_s'].values
params, _ = curve_fit(model, x, y)
a, b, c, d = params

y_pred = model(x, *params)
r2 = 1 - np.sum((y - y_pred)**2) / np.sum((y - np.mean(y))**2)

result = minimize_scalar(lambda x: model(x, *params), bounds=(x.min(), x.max()), method='bounded')
x_min, y_min = result.x, result.fun

# Plot 1 - Total time
fig, ax = plt.subplots(figsize=(10, 6))
x_smooth = np.linspace(x.min(), x.max(), 500)
ax.plot(x, y, 'o', color='#2E86AB', markersize=8, label='Data')
ax.plot(x_smooth, model(x_smooth, *params), '-', color='red', linewidth=2, label=f'${a:.0f}/x + {b:.3f}x + {c:.2e}x^2 + {d:.1f}$')
ax.axvline(x=x_min, color='red', linestyle='--', linewidth=2, alpha=0.5, label=f'Optimal: {x_min:.1f} km')
ax.text(0.02, 0.98, f'$R^2 = {r2:.4f}$', transform=ax.transAxes, fontsize=11, verticalalignment='top', bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Total Time (s)', fontsize=12)
ax.set_title('Total Processing Time vs Tolerance', fontsize=14, fontweight='bold')
ax.legend(fontsize=11)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('1_total_time.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 2 - Line per component
markers = ['^', 'd', 'D', 'x', 'v', 'p', '*']
fig, ax = plt.subplots(figsize=(12, 7))
for col, color, marker, label in zip(timing_columns, colors, markers, labels):
    ax.plot(avg[param], avg[col], marker=marker, linestyle='-', label=label,
            color=color, linewidth=2, markersize=8)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Time Breakdown by Tolerance', fontsize=14, fontweight='bold')
ax.legend(fontsize=10, ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('2_time_breakdown.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 3 - Stacked area
fig, ax = plt.subplots(figsize=(12, 7))
y_stack = np.vstack([avg[col].values for col in timing_columns])
ax.stackplot(avg[param], y_stack, labels=labels, colors=colors, alpha=0.8)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Time (s)', fontsize=12)
ax.set_title('Time Breakdown Stacked by Tolerance', fontsize=14, fontweight='bold')
ax.legend(fontsize=8, loc='upper left', ncol=2)
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('3_time_breakdown_stacked.png', dpi=300, bbox_inches='tight')
plt.close()

# Plot 4 - Conjunctions
fig, ax = plt.subplots(figsize=(10, 6))
ax.plot(avg[param], avg['conj'], 'o-', linewidth=2, markersize=8, color='#2E86AB')
for _, row in avg.iterrows():
    ax.text(row[param], row['conj'], f'  {row["conj"]:.0f}', va='bottom', fontsize=9)
ax.set_xlabel(param_label, fontsize=12)
ax.set_ylabel('Conjunctions', fontsize=12)
ax.set_title('Conjunctions Detected vs Tolerance', fontsize=14, fontweight='bold')
ax.grid(True, alpha=0.3)
plt.tight_layout()
plt.savefig('4_conjunctions.png', dpi=300, bbox_inches='tight')
plt.close()
