#!/usr/bin/env python3
"""
plot_results.py
Read `results.csv` and produce speedup/efficiency plots, Amdahl's Law comparison,
weak scaling graphs, and bottleneck analysis.

Usage: python plot_results.py
"""
import csv
import collections
import math
import os

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT      = os.path.dirname(os.path.abspath(__file__))
CSV       = os.path.join(ROOT, 'results.csv')
WEAK_CSV  = os.path.join(ROOT, 'weak_results.csv')
PLOTS_DIR = os.path.join(ROOT, 'plots')
os.makedirs(PLOTS_DIR, exist_ok=True)

def parse_results(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, newline='') as f:
        reader = csv.DictReader(f)
        for r in reader:
            try:
                w = int(r['Workers'])
                c = int(r['Candidates'])
                t_seq = float(r['T_seq(ms)'])
                t_par = float(r['T_par(ms)'])
                s = float(r.get('Speedup') or (t_seq / t_par if t_par > 0 else 0))
                e = float(r.get('Efficiency') or (s / w if w > 0 else 0))
                rows.append({'w': w, 'c': c, 't_seq': t_seq, 't_par': t_par, 's': s, 'e': e})
            except Exception as ex:
                print('Skipping row due to parse error:', ex, r)
    return rows

# Parse CSVs (both are optional, but at least one must exist)
rows = parse_results(CSV)
weak_rows = parse_results(WEAK_CSV)

if not rows and not weak_rows:
    print('Error: Neither results.csv nor weak_results.csv found. Run experiments first.')
    raise SystemExit(1)

if not rows:
    print('Warning: results.csv not found. Only weak-scaling plots will be generated.')

# Group by candidate size
by_size = collections.defaultdict(list)
for r in rows:
    by_size[r['c']].append(r)

# Group by worker count
by_workers = collections.defaultdict(list)
for r in rows:
    by_workers[r['w']].append(r)

os.makedirs(PLOTS_DIR, exist_ok=True)

# --- Strong Scaling: combined comparison chart (all candidate sizes) ---
if rows:
    strong_scaling_curves = []
    efficiency_curves = []
    amdahl_curves = []

    for c, items in sorted(by_size.items()):
        items_sorted = sorted(items, key=lambda x: x['w'])
        ws = [x['w'] for x in items_sorted]
        Ss = [x['s'] for x in items_sorted]
        Es = [x['e'] for x in items_sorted]

        strong_scaling_curves.append((c, ws, Ss))
        efficiency_curves.append((c, ws, Es))

        # Compute Amdahl f for each p (skip p=1)
        f_vals = []
        for x in items_sorted:
            p = x['w']; S = x['s']
            if p <= 1 or S <= 0:
                f_vals.append(None)
                continue
            f = p * (S - 1) / (S * (p - 1))
            f_vals.append(f)

        valid_f = [f for f in f_vals if f is not None]
        amdahl_f = sum(valid_f) / len(valid_f) if valid_f else 0.5
        amdahl_theory = [1.0 / ((1 - amdahl_f) + amdahl_f / p) for p in ws]
        amdahl_curves.append((c, ws, Ss, amdahl_theory, amdahl_f))

        # Save summary text
        with open(os.path.join(PLOTS_DIR, f'summary_{c}.txt'), 'w') as fo:
            fo.write(f'Candidates: {c}\n')
            fo.write('p,S(p),E(p),f_est\n')
            for i, x in enumerate(items_sorted):
                fv = f_vals[i]
                if fv is None:
                    fo.write(f"{x['w']},{x['s']:.4f},{x['e']:.4f},\n")
                else:
                    fo.write(f"{x['w']},{x['s']:.4f},{x['e']:.4f},{fv:.4f}\n")

    # --- NEW: Create separate ALL CANDIDATES speedup plot ---
    plt.figure(figsize=(10, 6))
    for c, ws, Ss in strong_scaling_curves:
        plt.plot(ws, Ss, marker='o', linewidth=2, markersize=8, label=f'{c:,} candidates')
    plt.axhline(y=1.0, color='gray', linestyle='--', linewidth=1.5, label='S=1 baseline')
    plt.title('Strong Scaling: Speedup Comparison (All Candidate Sizes)', fontsize=14, fontweight='bold')
    plt.xlabel('Number of Workers (p)', fontsize=12)
    plt.ylabel('Speedup S(p) = T_seq / T_par', fontsize=12)
    plt.grid(True, alpha=0.3)
    plt.legend(loc='best', fontsize=10)
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'all_candidates_speedup.png'), dpi=150)
    plt.close()
    print('[OK] Created: all_candidates_speedup.png')

    # --- NEW: Create separate ALL CANDIDATES efficiency plot ---
    plt.figure(figsize=(10, 6))
    for c, ws, Es in efficiency_curves:
        plt.plot(ws, Es, marker='o', linewidth=2, markersize=8, label=f'{c:,} candidates')
    plt.axhline(y=1.0, color='gray', linestyle='--', linewidth=1.5, label='E=1 ideal')
    plt.title('Strong Scaling: Efficiency Comparison (All Candidate Sizes)', fontsize=14, fontweight='bold')
    plt.xlabel('Number of Workers (p)', fontsize=12)
    plt.ylabel('Efficiency E(p) = S(p) / p', fontsize=12)
    plt.grid(True, alpha=0.3)
    plt.legend(loc='best', fontsize=10)
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'all_candidates_efficiency.png'), dpi=150)
    plt.close()
    print('[OK] Created: all_candidates_efficiency.png')

    # Original combined 2-in-1 plot
    fig, axes = plt.subplots(1, 2, figsize=(14, 5))
    ax_speedup, ax_efficiency = axes

    for c, ws, Ss in strong_scaling_curves:
        ax_speedup.plot(ws, Ss, marker='o', linewidth=2, markersize=7, label=f'Candidates={c}')
    ax_speedup.axhline(y=1.0, color='gray', linestyle=':', linewidth=1, label='S=1 baseline')
    ax_speedup.set_title('Strong Scaling: Speedup', fontsize=12, fontweight='bold')
    ax_speedup.set_xlabel('Workers (p)', fontsize=11)
    ax_speedup.set_ylabel('Speedup S(p) = T_seq / T_par', fontsize=11)
    ax_speedup.grid(True, alpha=0.3)
    ax_speedup.legend()

    for c, ws, Es in efficiency_curves:
        ax_efficiency.plot(ws, Es, marker='o', linewidth=2, markersize=7, label=f'Candidates={c}')
    ax_efficiency.axhline(y=1.0, color='gray', linestyle=':', linewidth=1, label='E=1 ideal')
    ax_efficiency.set_title('Strong Scaling: Efficiency', fontsize=12, fontweight='bold')
    ax_efficiency.set_xlabel('Workers (p)', fontsize=11)
    ax_efficiency.set_ylabel('Efficiency E(p) = S(p) / p', fontsize=11)
    ax_efficiency.grid(True, alpha=0.3)
    ax_efficiency.legend()

    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'strong_scaling_overview.png'), dpi=150)
    plt.close()
else:
    strong_scaling_curves = []
    efficiency_curves = []
    amdahl_curves = []

if weak_rows:
    weak_rows = sorted(weak_rows, key=lambda x: x['w'])
    ws = [x['w'] for x in weak_rows]
    tpars = [x['t_par'] for x in weak_rows]
    norm = [t / tpars[0] for t in tpars] if tpars else []

    if tpars and max(tpars) > 1.1 * min(tpars):
        print('Weak-scaling warning: parallel runtime increases noticeably with workers.')
        print('  Ideal weak scaling should stay approximately flat as workers and problem size grow together.')

    plt.figure(figsize=(8, 5))
    plt.plot(ws, tpars, marker='o', linewidth=2, markersize=8, label='Measured T_par')
    plt.axhline(y=tpars[0], color='r', linestyle='--', label='Ideal weak scaling', linewidth=1)
    plt.title('Weak Scaling: Parallel Runtime vs Workers', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Parallel Runtime T_par (ms)', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.xticks(ws)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'weak_scaling.png'), dpi=150)
    plt.close()

    plt.figure(figsize=(8, 5))
    plt.plot(ws, norm, marker='o', linewidth=2, markersize=8, label='Normalized T_par / T_par(1)')
    plt.axhline(y=1.0, color='r', linestyle='--', label='Ideal normalized time', linewidth=1)
    plt.title('Weak Scaling: Normalized Runtime', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Normalized Runtime', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.xticks(ws)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'weak_scaling_normalized.png'), dpi=150)
    plt.close()

if amdahl_curves:
    cols = 2
    rows_needed = math.ceil(len(amdahl_curves) / cols)
    fig, axes = plt.subplots(rows_needed, cols, figsize=(14, 5 * rows_needed), squeeze=False)

    for idx, (c, ws, Ss, S_theory, f_est) in enumerate(amdahl_curves):
        ax = axes[idx // cols][idx % cols]
        ax.plot(ws, Ss, marker='o', linewidth=2, markersize=7, label='Measured S(p)')
        ax.plot(ws, S_theory, marker='s', linewidth=2, markersize=7, linestyle='--', label=f'Amdahl theory (f={f_est:.3f})')
        ax.axhline(y=1.0, color='gray', linestyle=':', linewidth=1)
        ax.set_title(f"Amdahl's Law (Candidates={c})", fontsize=12, fontweight='bold')
        ax.set_xlabel('Workers (p)', fontsize=11)
        ax.set_ylabel('Speedup S(p)', fontsize=11)
        ax.grid(True, alpha=0.3)
        ax.legend()

    for idx in range(len(amdahl_curves), rows_needed * cols):
        fig.delaxes(axes[idx // cols][idx % cols])

    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'amdahl_comparison.png'), dpi=150)
    plt.close()

print(f'\nAll plots and summaries written to: {PLOTS_DIR}/')
print('Generated:')
print('  ✓ all_candidates_speedup.png (NEW - all sizes combined)')
print('  ✓ all_candidates_efficiency.png (NEW - all sizes combined)')
print('  ✓ strong_scaling_overview.png (combined speedup and efficiency side-by-side)')
print('  ✓ weak_scaling.png, weak_scaling_normalized.png (weak scaling)')
print('  ✓ amdahl_comparison.png (Amdahl\'s Law comparison)')
print('  ✓ summary_*.txt (detailed metrics)')