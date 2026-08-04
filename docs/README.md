# Benchmark Experiments

Each subdirectory is a benchmark experiment with a writeup, CSV results, and plot scripts. Experiments 1-4 sweep one
parameter at a time. Experiment 5 sweeps all three together. Experiments 6-7 cover runtime configuration. Experiment 8
validates the pipeline against CelesTrak's SOCRATES Plus catalog.

| # | Experiment                                       | What it covers                                     |
|---|--------------------------------------------------|----------------------------------------------------|
| 1 | [Step Size](1-step-size)                         | Coarse scan time step in seconds                   |
| 2 | [Knot Gap](2-knot-gap)                           | Seconds between real SGP4 calls, stride derived    |
| 3 | [Cell Size](3-cell-size)                         | Spatial grid cell edge in km                       |
| 4 | [Conjunction Tolerance](4-conjunction-tolerance) | Coarse scan distance threshold in km               |
| 5 | [Pareto Frontier](5-pareto-frontier)             | First 3 parameters simultaneously                  |
| 6 | [Garbage Collector](6-gc)                        | GC impact on conjunction pipeline throughput       |
| 7 | [Subwindow Count](7-subwindow-count)             | Memory partitioning for peak heap reduction        |
| 8 | [SOCRATES Comparison](8-socrates-comparison)     | Event-level agreement against the SOCRATES catalog |
