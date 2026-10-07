import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.colors import LogNorm

df = pd.read_csv('conjunction_benchmark.csv')

THRESHOLD_KM = 5.0
CLOSING_SPEED_KM_S = 15.6  # ScanService.MAX_CLOSING_SPEED_KM_S


def derived_tolerance(step):
    return np.sqrt(THRESHOLD_KM ** 2 + (CLOSING_SPEED_KM_S * step / 2) ** 2)


steps = sorted(df['step_s'].unique())
tols = sorted(df['tolerance_km'].unique())
missed = df.pivot_table(index='tolerance_km', columns='step_s', values='safe_only').loc[tols, steps]
extra = df.pivot_table(index='tolerance_km', columns='step_s', values='ours_only').loc[tols, steps]
scan_s = (df.assign(scan_s=df['check_s'] + df['grouping_s'] + df['refine_s'])
          .pivot_table(index='tolerance_km', columns='step_s', values='scan_s').loc[tols, steps])

# Missed events in the swept rows either side of the derived tolerance
print("| Step (s) | Derived tolerance | Row below | Missed | Row above | Missed |")
print("|---|---|---|---|---|---|")
for s in steps:
    col, d = missed[s], derived_tolerance(s)
    below, above = max(t for t in tols if t < d), min(t for t in tols if t >= d)
    print(f"| {s:g} | {d:.1f} km | {below:.0f} km | {int(col[below])} | {above:.0f} km | {int(col[above])} |")

x = np.arange(len(steps))
y = np.arange(len(tols))


def tol_to_row(t):
    # Tolerance rows are evenly spaced, so a linear map puts the curve between cells correctly.
    return (t - tols[0]) / (tols[1] - tols[0])


def heatmap(values, norm, cmap, label, fname, title, annotate):
    fig, ax = plt.subplots(figsize=(11, 8))
    im = ax.imshow(values.values, origin='lower', aspect='auto', cmap=cmap, norm=norm)
    for i in range(len(tols)):
        for j in range(len(steps)):
            v = values.values[i, j]
            dark = norm(v) > 0.6 if not np.ma.is_masked(norm(v)) else False
            ax.text(j, i, annotate(i, j), ha='center', va='center', fontsize=7,
                    color='white' if dark else '#333333')
    fine = np.linspace(steps[0], steps[-1], 200)
    # Steps are not evenly spaced, so interpolate the curve's column position from the step values.
    ax.plot(np.interp(fine, steps, x), tol_to_row(derived_tolerance(fine)), color='#d4a34a', lw=2.5,
            label='derived tolerance sqrt(5^2 + (15.6 * step / 2)^2)')
    ax.set_xticks(x, [f'{s:g}' for s in steps])
    ax.set_yticks(y, [f'{t:.0f}' for t in tols])
    ax.set_xlabel('Step (s)', fontsize=12)
    ax.set_ylabel('Tolerance (km)', fontsize=12)
    ax.set_title(title, fontsize=14, fontweight='bold')
    ax.legend(loc='upper center', bbox_to_anchor=(0.5, -0.08), fontsize=10, frameon=False)
    fig.colorbar(im, ax=ax, label=label)
    plt.tight_layout()
    plt.savefig(fname, dpi=300, bbox_inches='tight')
    plt.close()


# 1 - accuracy; log colour on missed with 0 shown as the lightest cell, extras annotated alongside
heatmap(missed.clip(lower=0.5), LogNorm(vmin=0.5, vmax=missed.values.max()), 'Blues',
        'Missed events vs no-interpolation reference (log, 0 shown as 0.5)', '1_accuracy_heatmap.png',
        'Missed / Extra Events by Step and Tolerance',
        lambda i, j: f'{missed.values[i, j]:.0f} / {extra.values[i, j]:.0f}')

# 2 - scan time; propagation is shared per step and does not depend on the tolerance
heatmap(scan_s, plt.Normalize(scan_s.values.min(), scan_s.values.max()), 'Blues',
        'Check + grouping + refine (s)', '2_scan_time_heatmap.png',
        'Scan Time by Step and Tolerance', lambda i, j: f'{scan_s.values[i, j]:.1f}')
