# Garbage Collector Comparison

Each GC runs the same fixed-parameter conjunction pipeline 10 times to measure throughput difference.

## Parameters

- **tolerance-km**: 84
- **step-seconds**: 10.8
- **interpolation-stride**: 32 (346 s knot gap)
- **cell-size-km**: 74
- **lookahead-hours**: 24
- **threshold-km**: 5.0
- **subwindowing**: none
- **iterations**: 10 per GC
- **heap**: 12 GB (-Xmx12g -Xms12g -XX:+AlwaysPreTouch)
- **catalog**: 31,665 objects (element sets at most 10 days old, median age 8.7 h), one 24 h pass from 2026-08-03T18:00Z

## Results

| GC         | Mean Time | Std Dev | Min    | Max    | Conjunctions |
|------------|-----------|---------|--------|--------|--------------|
| G1         | 27.41s    | 0.66s   | 26.32s | 28.23s | 58,405       |
| Parallel   | 27.48s    | 0.48s   | 26.39s | 27.98s | 58,405       |
| Shenandoah | 28.06s    | 0.13s   | 27.86s | 28.23s | 58,405       |
| Z          | 31.55s    | 0.50s   | 30.73s | 32.33s | 58,405       |

All four detect identical conjunctions. The difference is pure runtime.

G1, Parallel and Shenandoah are within 2.4% of each other, practically within noise.

ZGC is the outlier, 15.1% slower than G1. Each iteration allocates a 2.9 GB position cache and tens of millions of
short-lived detections, then drops them all, and ZGC is built for pause time rather than throughput. But that's a guess.

**Recommendation: G1**, the default, since nothing beat it decisively enough to justify pinning an alternative.

![Total Processing Time](1_total_time.png)

![Time Breakdown](2_time_breakdown.png)
