# Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

"""Reproduce sampling-grid limits and exact scalar RR moments; not a GPU benchmark.

Reads the shipped STBN bank. Writes build/path-sampling-analysis/results.json.
The RR model has n identical Lambert scatters and one terminal Bernoulli light event.
It excludes visibility traversal cost, BSDF lobes, temporal correlation and denoising.
"""
from pathlib import Path
import json
import math

import numpy as np


def terminal_moments(albedo, scatters, start=1, exponent=0.5, floor=0.0, hit=0.01):
    beta = 1.0
    survival = 1.0
    traces = 1.0  # Camera trace, followed by surviving continuations.
    for depth in range(1, scatters + 1):
        beta *= albedo
        p = min(max(beta ** exponent, floor), 1.0) if depth >= start else 1.0
        beta /= p
        survival *= p
        traces += survival
    expected = hit * albedo ** scatters
    second = hit * survival * beta * beta
    variance = second - expected * expected
    assert math.isclose(hit * survival * beta, expected, rel_tol=1e-12)
    return dict(survival=survival, surviving_weight=beta,
                expected_traces=traces, mean=expected, variance=variance,
                relative_variance=variance / expected ** 2,
                relative_variance_times_traces=variance * traces / expected ** 2)


def main():
    root = Path(__file__).resolve().parents[1]
    table = np.fromfile(root / 'src/client/resources/prime/stbn/realtime_128x128x64x3.rg16ui',
                        dtype='<u2').reshape(3, 64, 128, 128, 2)
    x = (table[0, ..., 0].astype(np.float32).ravel() + np.float32(0.5)) / np.float32(65536)
    # Equal binary branches make a 2^17-leaf tree; all arithmetic is exact in f32.
    leaf_count = 1 << 17
    leaves = (x * leaf_count).astype(np.uint32)
    counts = np.bincount(leaves, minlength=leaf_count)
    interval = [0.0, 1e-6]
    centre = float(x[0])
    rare_counts = [int(np.count_nonzero((x >= lo) & (x < hi)))
                   for lo, hi in [interval, [centre - 5e-7, centre + 5e-7]]]
    expected_count = len(x) * 1e-6
    grid = dict(samples=len(x), unique_codes=int(np.unique(table[0, ..., 0]).size),
                minimum_sample=float(x.min()), maximum_sample=float(x.max()),
                balanced_tree_leaves=leaf_count,
                balanced_tree_reachable=int(np.count_nonzero(counts)),
                small_interval_counts=rare_counts, small_interval_expected=expected_count,
                relative_mass=[n / expected_count for n in rare_counts])
    # Uniform low eight bits inside each existing high-16 cell: an exact 24-bit grid.
    # Counting intervals analytically avoids allocating another full-sized random table.
    grid['refined_interval_relative_mass'] = []
    for lo, hi in [interval, [centre - 5e-7, centre + 5e-7]]:
        count = math.ceil(hi * (1 << 24)) - math.ceil(lo * (1 << 24))
        grid['refined_interval_relative_mass'].append(count / ((1 << 24) * 1e-6))
    grid['refined_balanced_tree_reachable'] = leaf_count
    assert grid['balanced_tree_reachable'] == leaf_count // 2
    assert rare_counts[0] == 0

    demodulated = []
    for albedo in [0.001, 0.01, 0.04, 0.25, 1.0]:
        p = math.sqrt(albedo)
        demodulated.append(dict(albedo=albedo, first_survival=p,
            zero_fraction=1-p, surviving_demodulated_weight=1/p,
            demodulated_variance=1/p-1))

    rr = []
    for a in [0.05, 0.2, 0.6]:
        for n in [4, 8]:
            for name, params in [('sqrt_start1', {}), ('sqrt_start2', dict(start=2)),
                    ('sqrt_floor025', dict(floor=0.25)), ('linear_start1', dict(exponent=1)),
                    ('no_rr', dict(start=n+1))]:
                rr.append(dict(albedo=a, scatters=n, policy=name, **terminal_moments(a, n, **params)))
    result = dict(stbn=grid, primary_rr_after_demodulation=demodulated, terminal_rr=rr)
    output = root / 'build/path-sampling-analysis'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'results.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('STBN', json.dumps(grid))
    print('DEMODULATION', json.dumps(demodulated))
    for r in rr:
        if r['albedo'] == 0.2 and r['scatters'] == 8:
            print('RR', json.dumps(r))


if __name__ == '__main__':
    main()
