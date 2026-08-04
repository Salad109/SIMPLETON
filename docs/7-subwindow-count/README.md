# Subwindow Count

The PositionCache is `numSats * totalSteps * 3 floats * 4 bytes`. At 30k satellites and step-seconds=10.8, a 24h window
is 2.9 GB. A 7-day window is 20.2 GB. This is too large for most systems.

`subwindow-count` splits the lookahead window into N sequential chunks. Each chunk runs the cache-dependent stages
(propagate, interpolate, coarse scan, group, refine) and the cache goes out of scope before the next chunk starts.
Collision probability and persistence run once after all chunks finish. Peak cache memory is roughly `1/N` of the
single-window case.

`subwindow-count=1` effectively disables subwindowing.

## Cache size estimates

Rough PositionCache size per subwindow for 30k satellites at step-seconds=10.8:

| Window   | Count | Steps/Sub | Cache/Sub |
|----------|-------|-----------|-----------|
| 24 hours | 1     | 8,001     | 2.9 GB    |
| 24 hours | 4     | 2,001     | 0.7 GB    |
| 24 hours | 8     | 1,001     | 0.4 GB    |
| 7 days   | 7     | 8,001     | 2.9 GB    |
| 7 days   | 14    | 4,001     | 1.4 GB    |
| 7 days   | 28    | 2,001     | 0.7 GB    |

These are float array sizes only. Actual heap is higher: the KnotCache stays reachable while the PositionCache is built,
adding another 6% at stride 32, on top of intermediate collections, Spring Boot and the JVM. At very high counts the
constant overhead dominates and cache savings become negligible in practice.

## Boundary handling

Subwindow endpoints are inclusive, so consecutive subwindows share their boundary time step. Without it, the interval
between them would belong to neither subwindow, and a conjunction there would be missed. On a 24h/4 config, that is 3
intervals in 8000, or 0.04%.

## Duplicates

Two things put a redundant row on a pair: an approach landing on a shared boundary step gets stored by both subwindows,
and a pair within tolerance for the whole window yields one conjunction in every subwindow. A scan of 31,665 objects
storing 67,684 conjunctions had 224 redundant rows, or 0.33%. Formation flight accounts for almost all of them. 73 pairs
stayed within 0.49 km of each other at under 1.1 m/s relative for the entire window, so every subwindow found them and
each produced exactly 4 conjunctions. Only 5 rows came from a shared boundary, pairs recorded twice with TCAs under a
minute apart. Multiple conjunctions per pair are allowed, so these are harmless.

## Step alignment

`subwindow-count` must divide the window into a whole number of steps, and `ConjunctionService.validate` rejects
anything else at startup. A 24h window at step-seconds=10.8 is 8000 steps, so 1, 2, 4, 5, 8, 10, 16, 20, 25, 32, 40 and
50 are legal. Knot times round to the nearest whole step, so an illegal count puts the boundary on the wrong side of
one: at 3 the subwindows would overlap by 3.6s, at 6 they would leave a 3.6s gap.

## Recommended values

For 24h lookahead window, use 4. For 7 days, use 28 (same cache size per subwindow as 24h/4).

Higher counts cause no meaningful speed penalty, and may arguably improve performance in memory-constrained environments
by reducing GC pressure.
