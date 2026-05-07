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

if not os.path.exists(CSV):
    print('results.csv not found. Run experiments first.')
    raise SystemExit(1)

# Parse CSVs
rows = parse_results(CSV)
weak_rows = parse_results(WEAK_CSV)

# Group by candidate size
by_size = collections.defaultdict(list)
for r in rows:
    by_size[r['c']].append(r)

# Group by worker count
by_workers = collections.defaultdict(list)
for r in rows:
    by_workers[r['w']].append(r)

os.makedirs(PLOTS_DIR, exist_ok=True)

# --- Strong Scaling: Speedup & Efficiency vs Worker Count (per candidate size) ---
strong_scaling_curves = []
for c, items in sorted(by_size.items()):
    items_sorted = sorted(items, key=lambda x: x['w'])
    ws = [x['w'] for x in items_sorted]
    Ss = [x['s'] for x in items_sorted]
    Es = [x['e'] for x in items_sorted]

    strong_scaling_curves.append((c, ws, Ss))

    # Plot Speedup
    plt.figure(figsize=(8, 5))
    plt.plot(ws, Ss, marker='o', linewidth=2, markersize=8, label='Measured S(p)')
    plt.axhline(y=1.0, color='r', linestyle='--', label='S=1 (Sequential baseline)', linewidth=1)
    plt.title(f'Strong Scaling: Speedup (Candidates={c})', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Speedup S(p) = T_seq / T_par', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.xticks(ws)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, f'speedup_{c}.png'), dpi=150)
    plt.close()

    # Plot Efficiency
    plt.figure(figsize=(8, 5))
    plt.plot(ws, Es, marker='o', linewidth=2, markersize=8, label='Measured E(p)')
    plt.axhline(y=1.0, color='r', linestyle='--', label='E=1 (Ideal)', linewidth=1)
    plt.title(f'Strong Scaling: Efficiency (Candidates={c})', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Efficiency E(p) = S(p) / p', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.xticks(ws)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, f'efficiency_{c}.png'), dpi=150)
    plt.close()

    # Compute Amdahl f for each p (skip p=1)
    f_vals = []
    for x in items_sorted:
        p = x['w']; S = x['s']
        if p <= 1 or S <= 0:
            f_vals.append(None)
            continue
        f = p * (S - 1) / (S * (p - 1))
        f_vals.append(f)

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

if strong_scaling_curves:
    plt.figure(figsize=(9, 5))
    for c, ws, Ss in strong_scaling_curves:
        plt.plot(ws, Ss, marker='o', linewidth=2, markersize=7, label=f'Candidates={c}')

    plt.axhline(y=1.0, color='gray', linestyle=':', linewidth=1, label='S=1 baseline')
    plt.title('Strong Scaling: Speedup Comparison', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Speedup S(p) = T_seq / T_par', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, 'strong_scaling_overview.png'), dpi=150)
    plt.close()

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

# --- Amdahl's Law Comparison (per candidate size) ---
for c, items in sorted(by_size.items()):
    items_sorted = sorted(items, key=lambda x: x['w'])
    ws = [x['w'] for x in items_sorted]
    Ss = [x['s'] for x in items_sorted]
    
    # Estimate parallel fraction f from all measurements
    f_estimates = []
    for x in items_sorted:
        p = x['w']; S = x['s']
        if p > 1 and S > 0:
            f = p * (S - 1) / (S * (p - 1))
            if 0 <= f <= 1:  # only keep valid estimates
                f_estimates.append(f)
    
    if not f_estimates:
        f_est = 0.5  # fallback
    else:
        f_est = sum(f_estimates) / len(f_estimates)
    
    # Compute theoretical S_max for this f
    S_theory = [1.0 / ((1 - f_est) + f_est / p) for p in ws]
    
    plt.figure(figsize=(8, 5))
    plt.plot(ws, Ss, marker='o', linewidth=2, markersize=8, label='Measured S(p)')
    plt.plot(ws, S_theory, marker='s', linewidth=2, markersize=8, linestyle='--', 
             label=f'Amdahl Theoretical (f={f_est:.3f})')
    plt.axhline(y=1.0, color='gray', linestyle=':', linewidth=1)
    plt.title(f"Amdahl's Law Comparison (Candidates={c})", fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Speedup S(p)', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.xticks(ws)
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(PLOTS_DIR, f'amdahl_{c}.png'), dpi=150)
    plt.close()

print(f'All plots and summaries written to: plots/')
print('Generated:')
print('  - speedup_*.png, efficiency_*.png (strong scaling)')
print('  - strong_scaling_overview.png (combined strong scaling, if multiple points exist)')
print('  - weak_scaling.png, weak_scaling_normalized.png (weak scaling, if weak_results.csv exists)')
print('  - amdahl_*.png (Amdahl\'s Law comparison)')
print('  - summary_*.txt (detailed metrics)')
