#!/usr/bin/env python3
"""
compare_optimization.py
Comparative analysis: Baseline (fixed chunk) vs Optimized (adaptive chunk).

Outputs go to  plots/  subdirectory:
  - speedup_comparison.png      all candidate sizes on one speedup chart
  - efficiency_comparison.png   all candidate sizes on one efficiency chart
  - improvement_bar.png         % improvement bar chart per (workers, candidates)
  - optimization_summary.txt    text report

Usage: python compare_optimization.py
"""

import csv, collections, os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.cm as cm
import numpy as np

ROOT          = os.path.dirname(os.path.abspath(__file__))
BASELINE_CSV  = os.path.join(ROOT, 'results_baseline.csv')
OPTIMIZED_CSV = os.path.join(ROOT, 'results_optimized.csv')
PLOTS_DIR     = os.path.join(ROOT, 'plots')
os.makedirs(PLOTS_DIR, exist_ok=True)

# ── 1. Parse ───────────────────────────────────────────────────────────────────

def parse(path):
    rows = []
    if not os.path.exists(path):
        print(f'  [WARN] {os.path.basename(path)} not found, skipping.')
        return rows
    with open(path, newline='', encoding='utf-8-sig') as f:
        for r in csv.DictReader(f):
            try:
                w, c   = int(r['Workers']), int(r['Candidates'])
                ts, tp = float(r['T_seq(ms)']), float(r['T_par(ms)'])
                s  = float(r.get('Speedup')    or (ts / tp  if tp > 0 else 0))
                e  = float(r.get('Efficiency') or (s  / w   if w  > 0 else 0))
                rows.append(dict(w=w, c=c, ts=ts, tp=tp, s=s, e=e))
            except Exception as ex:
                print(f'  [SKIP] {ex}')
    return rows

base_rows = parse(BASELINE_CSV)
opt_rows  = parse(OPTIMIZED_CSV)

if not base_rows and not opt_rows:
    print('No data in either CSV. Run the experiment scripts first.')
    exit(1)

def group(rows):
    d = collections.defaultdict(dict)
    for r in rows:
        d[r['c']][r['w']] = r
    return d   # d[candidates][workers] = row

base_by = group(base_rows)
opt_by  = group(opt_rows)

all_sizes   = sorted(set(base_by) | set(opt_by))
all_workers = sorted(set(r['w'] for r in base_rows + opt_rows))

print(f'Baseline  sizes : {sorted(base_by)}')
print(f'Optimized sizes : {sorted(opt_by)}')
print(f'Combined  sizes : {all_sizes}')
print(f'Worker counts   : {all_workers}')
print()

# ── 2. Colour palette (one colour per candidate size) ─────────────────────────
colours = plt.cm.tab10(np.linspace(0, 0.9, len(all_sizes)))
colour_map = {c: colours[i] for i, c in enumerate(all_sizes)}

# ── 3. Combined Speedup chart ─────────────────────────────────────────────────
fig, ax = plt.subplots(figsize=(11, 6))

for c in all_sizes:
    col   = colour_map[c]
    label = f'{c:,} candidates'

    if c in base_by:
        ww = sorted(base_by[c])
        ss = [base_by[c][w]['s'] for w in ww]
        ax.plot(ww, ss, marker='o', linestyle='--', linewidth=1.8,
                markersize=7, color=col, alpha=0.7,
                label=f'{label}  Baseline')

    if c in opt_by:
        ww = sorted(opt_by[c])
        ss = [opt_by[c][w]['s'] for w in ww]
        ax.plot(ww, ss, marker='s', linestyle='-', linewidth=2.2,
                markersize=8, color=col,
                label=f'{label}  Optimized')

ax.axhline(1.0, color='#888', linestyle=':', linewidth=1.2, label='Speedup = 1')
ax.set_title('Speedup: Baseline (--) vs Optimized (-)  |  All Candidate Sizes',
             fontsize=13, fontweight='bold', pad=12)
ax.set_xlabel('Workers', fontsize=11)
ax.set_ylabel('Speedup  S = T_seq / T_par', fontsize=11)
ax.set_xticks(all_workers)
ax.grid(True, alpha=0.3)
ax.legend(fontsize=8, ncol=2, loc='upper left', framealpha=0.9)
fig.tight_layout()
fig.savefig(os.path.join(PLOTS_DIR, 'speedup_comparison.png'), dpi=150)
plt.close(fig)
print('  [OK] speedup_comparison.png')

# ── 4. Combined Efficiency chart ───────────────────────────────────────────────
fig, ax = plt.subplots(figsize=(11, 6))

for c in all_sizes:
    col   = colour_map[c]
    label = f'{c:,} candidates'

    if c in base_by:
        ww = sorted(base_by[c])
        ee = [base_by[c][w]['e'] for w in ww]
        ax.plot(ww, ee, marker='o', linestyle='--', linewidth=1.8,
                markersize=7, color=col, alpha=0.7,
                label=f'{label}  Baseline')

    if c in opt_by:
        ww = sorted(opt_by[c])
        ee = [opt_by[c][w]['e'] for w in ww]
        ax.plot(ww, ee, marker='s', linestyle='-', linewidth=2.2,
                markersize=8, color=col,
                label=f'{label}  Optimized')

ax.axhline(1.0, color='#888', linestyle=':', linewidth=1.2, label='Ideal (E=1)')
ax.set_title('Efficiency: Baseline (--) vs Optimized (-)  |  All Candidate Sizes',
             fontsize=13, fontweight='bold', pad=12)
ax.set_xlabel('Workers', fontsize=11)
ax.set_ylabel('Efficiency  E = S / p', fontsize=11)
ax.set_xticks(all_workers)
ax.grid(True, alpha=0.3)
ax.legend(fontsize=8, ncol=2, loc='upper right', framealpha=0.9)
fig.tight_layout()
fig.savefig(os.path.join(PLOTS_DIR, 'efficiency_comparison.png'), dpi=150)
plt.close(fig)
print('  [OK] efficiency_comparison.png')

# ── 5. Improvement bar chart ───────────────────────────────────────────────────
# Only for (c, w) pairs that exist in BOTH files
common = []
for c in all_sizes:
    for w in all_workers:
        if c in base_by and w in base_by[c] and c in opt_by and w in opt_by[c]:
            bs = base_by[c][w]['s']
            os_ = opt_by[c][w]['s']
            if bs > 0:
                common.append((c, w, bs, os_, (os_ - bs) / bs * 100))

if common:
    labels  = [f'{c//1000}K\nw={w}' for c, w, *_ in common]
    improve = [imp for *_, imp in common]
    bar_colours = ['#27AE60' if v >= 0 else '#E74C3C' for v in improve]

    fig, ax = plt.subplots(figsize=(max(8, len(labels) * 0.9 + 2), 5))
    bars = ax.bar(labels, improve, color=bar_colours, edgecolor='white', linewidth=0.5)
    ax.axhline(0, color='black', linewidth=0.8)

    for bar, val in zip(bars, improve):
        ax.text(bar.get_x() + bar.get_width() / 2,
                bar.get_height() + (0.5 if val >= 0 else -1.5),
                f'{val:+.1f}%', ha='center', va='bottom', fontsize=8, fontweight='bold')

    ax.set_title('Speedup Improvement: Optimized vs Baseline  (+ = adaptive is faster)',
                 fontsize=12, fontweight='bold', pad=10)
    ax.set_xlabel('Candidates (K) / Workers', fontsize=10)
    ax.set_ylabel('Speedup Improvement (%)', fontsize=10)
    ax.grid(True, axis='y', alpha=0.3)
    fig.tight_layout()
    fig.savefig(os.path.join(PLOTS_DIR, 'improvement_bar.png'), dpi=150)
    plt.close(fig)
    print('  [OK] improvement_bar.png')
else:
    print('  [SKIP] improvement_bar — no overlapping (candidates, workers) between baseline and optimized.')

# ── 6. Summary text ────────────────────────────────────────────────────────────
summary_path = os.path.join(PLOTS_DIR, 'optimization_summary.txt')
with open(summary_path, 'w', encoding='utf-8') as f:
    sep = '=' * 72
    f.write(sep + '\n')
    f.write('Optimization Comparison: Baseline vs Adaptive Task Granularity\n')
    f.write(sep + '\n\n')

    f.write('OPTIMIZATION DETAILS\n')
    f.write('-' * 72 + '\n')
    f.write('Baseline : Fixed chunk size (every chunk = chunkSize candidates)\n')
    f.write('Optimized: Dynamic chunk size  Formula: max(10, remaining / (workers*2))\n')
    f.write('Benefit  : Larger chunks early (fewer round-trips) -> smaller chunks\n')
    f.write('           late (finer load balance, fewer idle worker-cycles)\n\n')

    f.write('RESULTS SUMMARY\n')
    f.write('-' * 72 + '\n')

    for c in all_sizes:
        f.write(f'\nCandidate Size: {c:,}\n')
        f.write(f"  {'Workers':>7} | {'Base Speedup':>13} | {'Opt Speedup':>12} | {'Improvement':>12}\n")
        bw = base_by.get(c, {})
        ow = opt_by.get(c,  {})
        for w in sorted(set(bw) | set(ow)):
            bs   = f"{bw[w]['s']:.3f}" if w in bw else 'N/A'
            os_  = f"{ow[w]['s']:.3f}" if w in ow else 'N/A'
            if w in bw and w in ow and bw[w]['s'] > 0:
                imp = f"{(ow[w]['s'] - bw[w]['s']) / bw[w]['s'] * 100:+.1f}%"
            else:
                imp = 'N/A'
            f.write(f"  {w:>7} | {bs:>13} | {os_:>12} | {imp:>12}\n")

print()
print(f'All output written to:  plots/')
print(f'  speedup_comparison.png')
print(f'  efficiency_comparison.png')
if common:
    print(f'  improvement_bar.png')
print(f'  optimization_summary.txt')
print('\nDone!')
