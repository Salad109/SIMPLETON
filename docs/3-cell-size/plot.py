import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.colors import LogNorm
from matplotlib.patches import Rectangle

df = pd.read_csv('conjunction_benchmark.csv')

THRESHOLD_KM = 5.0

df['ratio'] = df['cell_ratio'].round(2)
steps = sorted(df['step_s'].unique())
ratios = sorted(df['ratio'].unique())


def grid(col):
    return df.pivot_table(index='ratio', columns='step_s', values=col).loc[ratios, steps]


missed, extra, tol, cell = grid('safe_only'), grid('ours_only'), grid('tolerance_km'), grid('cell_km')
scan_s = (df.assign(scan_s=df['check_s'] + df['grouping_s'] + df['refine_s'])
          .pivot_table(index='ratio', columns='step_s', values='scan_s').loc[ratios, steps])


def v_guar(cell_km, step):
    return 2 * np.sqrt(cell_km ** 2 - THRESHOLD_KM ** 2) / step


# The narrowest cell the grid adds no loss at, walking down from the production cell.
print("| Step (s) | Tolerance | Missed at 1.00 | Narrowest without grid loss | v_guar there | Next narrower | Grid loss |")
print("|---|---|---|---|---|---|---|")
below = [r for r in ratios if r <= 1.0][::-1]
for s in steps:
    base, clean = missed.loc[1.0, s], 1.0
    for r in below:
        if missed.loc[r, s] > base:
            break
        clean = r
    nxt = max((r for r in ratios if r < clean), default=None)
    nxt_cell = f"{nxt:.2f} | {int(missed.loc[nxt, s] - base)}" if nxt is not None else "- | -"
    print(f"| {s:g} | {tol.loc[1.0, s]:.1f} km | {int(base)} | {clean:.2f} ({cell.loc[clean, s]:.1f} km) | "
          f"{v_guar(cell.loc[clean, s], s):.1f} km/s | {nxt_cell} |")

# Grid loss by ratio, across steps
print("\n| Cell / tolerance | Grid loss, min to max over steps |")
print("|---|---|")
for r in [r for r in ratios if r < 1.0][::-1]:
    loss = missed.loc[r] - missed.loc[1.0]
    print(f"| {r:.2f} | {int(loss.min())} to {int(loss.max())} |")

# What a wider cell costs: scan time at the production cell and at the widest swept
print(f"\n| Step (s) | Scan time at 1.00 | at {ratios[-1]:.2f} |")
print("|---|---|---|")
for s in steps:
    print(f"| {s:g} | {scan_s.loc[1.0, s]:.1f}s | {scan_s.loc[ratios[-1], s]:.1f}s |")

x, y = np.arange(len(steps)), np.arange(len(ratios))


def heatmap(values, norm, label, fname, title, annotate):
    fig, ax = plt.subplots(figsize=(11, 8))
    im = ax.imshow(values.values, origin='lower', aspect='auto', cmap='Blues', norm=norm)
    for i in range(len(ratios)):
        for j in range(len(steps)):
            v = values.values[i, j]
            ax.text(j, i, annotate(i, j), ha='center', va='center', fontsize=7,
                    color='white' if norm(v) > 0.6 else '#333333')
    # Production: the cell as wide as the derived tolerance at every step
    row = ratios.index(1.0)
    ax.add_patch(Rectangle((-0.5, row - 0.5), len(steps), 1, fill=False, edgecolor='#d4a34a', lw=2.5,
                           label='cell = derived tolerance sqrt(5^2 + (15.6 * step / 2)^2)'))
    ax.set_xticks(x, [f'{s:g}' for s in steps])
    ax.set_yticks(y, [f'{r:.2f}' for r in ratios])
    ax.set_xlabel('Step (s)', fontsize=12)
    ax.set_ylabel('Cell / derived tolerance', fontsize=12)
    ax.set_title(title, fontsize=14, fontweight='bold')
    ax.legend(loc='upper center', bbox_to_anchor=(0.5, -0.08), fontsize=10, frameon=False)
    fig.colorbar(im, ax=ax, label=label)
    plt.tight_layout()
    plt.savefig(fname, dpi=300, bbox_inches='tight')
    plt.close()


# 1 - accuracy; log colour on missed with 0 shown as the lightest cell, extras annotated alongside
heatmap(missed.clip(lower=0.5), LogNorm(vmin=0.5, vmax=max(missed.values.max(), 1)),
        'Missed events vs no-interpolation reference (log, 0 shown as 0.5)', '1_accuracy_heatmap.png',
        'Missed / Extra Events by Step and Cell Size',
        lambda i, j: f'{missed.values[i, j]:.0f} / {extra.values[i, j]:.0f}')

# 2 - scan time; propagation is shared per step and does not depend on the cell
heatmap(scan_s, plt.Normalize(scan_s.values.min(), scan_s.values.max()), 'Check + grouping + refine (s)',
        '2_scan_time_heatmap.png', 'Scan Time by Step and Cell Size',
        lambda i, j: f'{scan_s.values[i, j]:.1f}')
