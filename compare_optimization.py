#!/usr/bin/env python3
"""
compare_optimization.py
Compares baseline (fixed granularity) vs optimized (adaptive granularity) results.
Generates side-by-side speedup and efficiency plots.

Usage: python compare_optimization.py
"""

import csv
import collections
import os
import matplotlib.pyplot as plt

ROOT = os.path.dirname(__file__)
BASELINE_CSV = os.path.join(ROOT, 'results_baseline.csv')
OPTIMIZED_CSV = os.path.join(ROOT, 'results_optimized.csv')


def parse_results(path):
    """Parse CSV file and return list of measurement dicts."""
    rows = []
    if not os.path.exists(path):
        print(f'Warning: {path} not found.')
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
                print(f'Skipping row due to parse error: {ex}')
    return rows


# Parse both CSVs
baseline_rows = parse_results(BASELINE_CSV)
optimized_rows = parse_results(OPTIMIZED_CSV)

if not baseline_rows:
    print('Error: No baseline results found. Run run_baseline_experiments.ps1 first.')
    exit(1)

if not optimized_rows:
    print('Error: No optimized results found. Run run_optimized_experiments.ps1 first.')
    exit(1)

# Group by candidate size
def group_by_size(rows):
    by_size = collections.defaultdict(list)
    for r in rows:
        by_size[r['c']].append(r)
    return by_size


baseline_by_size = group_by_size(baseline_rows)
optimized_by_size = group_by_size(optimized_rows)

os.makedirs(ROOT, exist_ok=True)

# Generate overlay comparison plots for each candidate size
for c in sorted(set(baseline_by_size.keys()) & set(optimized_by_size.keys())):
    baseline_items = sorted(baseline_by_size[c], key=lambda x: x['w'])
    optimized_items = sorted(optimized_by_size[c], key=lambda x: x['w'])

    ws_base = [x['w'] for x in baseline_items]
    Ss_base = [x['s'] for x in baseline_items]
    Es_base = [x['e'] for x in baseline_items]

    ws_opt = [x['w'] for x in optimized_items]
    Ss_opt = [x['s'] for x in optimized_items]
    Es_opt = [x['e'] for x in optimized_items]

    # Overlaid Speedup comparison
    plt.figure(figsize=(9, 5))
    plt.plot(ws_base, Ss_base, marker='o', linewidth=2, markersize=8, color='red', label='Baseline (Fixed)')
    plt.plot(ws_opt, Ss_opt, marker='s', linewidth=2, markersize=8, color='green', label='Optimized (Adaptive)')
    plt.axhline(y=1.0, color='gray', linestyle='--', linewidth=1, alpha=0.5)
    plt.title(f'Speedup Comparison (Candidates={c})', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Speedup S(p)', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.legend(fontsize=10)
    plt.tight_layout()
    plt.savefig(os.path.join(ROOT, f'comparison_speedup_overlay_{c}.png'), dpi=150)
    plt.close()

    # Overlaid Efficiency comparison
    plt.figure(figsize=(9, 5))
    plt.plot(ws_base, Es_base, marker='o', linewidth=2, markersize=8, color='red', label='Baseline (Fixed)')
    plt.plot(ws_opt, Es_opt, marker='s', linewidth=2, markersize=8, color='green', label='Optimized (Adaptive)')
    plt.axhline(y=1.0, color='gray', linestyle='--', linewidth=1, alpha=0.5)
    plt.title(f'Efficiency Comparison (Candidates={c})', fontsize=12, fontweight='bold')
    plt.xlabel('Workers (p)', fontsize=11)
    plt.ylabel('Efficiency E(p) = S(p) / p', fontsize=11)
    plt.grid(True, alpha=0.3)
    plt.legend(fontsize=10)
    plt.tight_layout()
    plt.savefig(os.path.join(ROOT, f'comparison_efficiency_overlay_{c}.png'), dpi=150)
    plt.close()

# Summary text
summary_path = os.path.join(ROOT, 'optimization_summary.txt')
with open(summary_path, 'w') as f:
    f.write("=" * 70 + "\n")
    f.write("Optimization Comparison: Baseline vs Adaptive Task Granularity\n")
    f.write("=" * 70 + "\n\n")

    f.write("OPTIMIZATION DETAILS\n")
    f.write("-" * 70 + "\n")
    f.write("Name: Adaptive Task Granularity\n")
    f.write("Baseline: Fixed chunk size (all chunks same size)\n")
    f.write("Optimized: Dynamic chunk size (larger early, smaller late)\n")
    f.write("  Formula: dynamicChunkSize = max(10, remaining / (workers * 2))\n")
    f.write("  Benefit: Better load balance, reduces idle time\n\n")

    f.write("RESULTS SUMMARY\n")
    f.write("-" * 70 + "\n")
    for c in sorted(set(baseline_by_size.keys()) & set(optimized_by_size.keys())):
        f.write(f"\nCandidate Size: {c}\n")
        baseline_items = sorted(baseline_by_size[c], key=lambda x: x['w'])
        optimized_items = sorted(optimized_by_size[c], key=lambda x: x['w'])

        f.write("Workers | Baseline Speedup | Optimized Speedup | Improvement\n")
        for base_item, opt_item in zip(baseline_items, optimized_items):
            base_s = base_item['s']
            opt_s = opt_item['s']
            improvement = ((opt_s - base_s) / base_s * 100) if base_s > 0 else 0
            f.write(f"   {base_item['w']:2d}   |      {base_s:6.3f}      |      {opt_s:6.3f}      | {improvement:+6.1f}%\n")

print("Comparison plots generated:")
print("  - comparison_speedup_overlay_*.png (speedup overlay for each candidate size)")
print("  - comparison_efficiency_overlay_*.png (efficiency overlay for each candidate size)")
print("  - optimization_summary.txt (detailed metrics)")
print("\nDone!")
