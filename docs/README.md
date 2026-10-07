# Benchmark Experiments

Each subdirectory is a benchmark experiment with a writeup, CSV results, and plot scripts. Experiments 1 and 2 sweep one
parameter at a time, and experiments 3 and 4 check the derived cell and tolerance across steps. Experiment 5 sweeps step
and knot gap together. Experiment 6 covers subwindowing. Experiment 7 validates the pipeline against CelesTrak's
SOCRATES Plus catalog.

All data was generated on an AMD Ryzen 9 5950X, 32 GB DDR4 3600MHz CL18, OpenJDK 25, Linux machine.

| # | Experiment                                       | What it covers                                     |
|---|--------------------------------------------------|----------------------------------------------------|
| 1 | [Step Size](1-step-size)                         | Coarse scan time step in seconds                   |
| 2 | [Knot Gap](2-knot-gap)                           | Seconds between real SGP4 calls                    |
| 3 | [Cell Size](3-cell-size)                         | Spatial grid cell size, derived from the tolerance |
| 4 | [Conjunction Tolerance](4-conjunction-tolerance) | Coarse scan radius, derived from the step          |
| 5 | [Step x Knot Gap](5-step-knot-sweep)             | Step and knot gap together                         |
| 6 | [Subwindow Count](6-subwindow-count)             | Memory partitioning for peak heap reduction        |
| 7 | [SOCRATES Comparison](7-socrates-comparison)     | Event-level agreement against the SOCRATES catalog |
