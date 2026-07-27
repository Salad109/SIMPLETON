# Benchmark Experiments

Each subdirectory is a benchmark experiment with a writeup, CSV results, and plot scripts. Experiments 1-4 sweep one
parameter at a time. Experiment 5 sweeps all three together. Experiments 6-7 cover runtime configuration. Experiment 8
validates the pipeline against CelesTrak's SOCRATES Plus catalog.

| # | Experiment                                       | What it covers                                     |
|---|--------------------------------------------------|----------------------------------------------------|
| 1 | [Step Ratio](1-step-ratio)                       | Time step size                                     |
| 2 | [Interpolation Stride](2-interpolation-stride)   | SGP4 calls per time step via interpolation spacing |
| 3 | [Cell Ratio](3-cell-ratio)                       | Spatial grid cell size                             |
| 4 | [Conjunction Tolerance](4-conjunction-tolerance) | Coarse scan distance threshold in km               |
| 5 | [Pareto Frontier](5-pareto-frontier)             | First 3 parameters simultaneously                  |
| 6 | [Garbage Collector](6-gc)                        | GC impact on conjunction pipeline throughput       |
| 7 | [Subwindow Count](7-subwindow-count)             | Memory partitioning for peak heap reduction        |
| 8 | [SOCRATES Comparison](8-socrates-comparison)     | Event-level agreement against the SOCRATES catalog |
