# Subwindow Count

The PositionCache is `numSats * totalSteps * 3 floats * 4 bytes`. At 30k satellites and step-seconds=9, a 24h window
is 3.5 GB. A 7-day window is 24.2 GB. This is too large for most systems.

`subwindow-count` splits the lookahead window into N sequential chunks. Each chunk runs the cache-dependent stages
(propagate, interpolate, coarse scan, group, refine) and the cache goes out of scope before the next chunk starts.
Collision probability and persistence run once after all chunks finish. Peak cache memory is roughly `1/N` of the
single-window case.

`subwindow-count=1` effectively disables subwindowing.

## Cache size estimates

Rough PositionCache size per subwindow for 30k satellites at step-seconds=9:

| Window   | Count | Steps/Sub | Cache/Sub |
|----------|-------|-----------|-----------|
| 24 hours | 1     | 9,601     | 3.5 GB    |
| 24 hours | 4     | 2,401     | 0.9 GB    |
| 24 hours | 8     | 1,201     | 0.4 GB    |
| 7 days   | 7     | 9,601     | 3.5 GB    |
| 7 days   | 14    | 4,801     | 1.7 GB    |
| 7 days   | 28    | 2,401     | 0.9 GB    |

These are float array sizes only. Actual heap is slightly higher (intermediate collections, Spring Boot and JVM). At
very high counts the constant overhead dominates and cache savings become negligible in practice.

## Boundary handling

Subwindow endpoints are inclusive, so consecutive subwindows share their boundary time step. Without it, the interval
between them would belong to neither subwindow, and a conjunction there would be missed. On a 24h/4 config, that is 3
intervals in 9600, or 0.03%.

## Duplicates

The shared step lets an approach at a boundary be stored twice, and a pair within tolerance for the whole window yields
one conjunction per subwindow. A scan of 31,633 objects storing 60,178 conjunction had 233 duplicates, or 0.4%. Almost
all are the second case: 232 conjunctions at 0 km and 0 m/s flying in formation the entire time. Multiple conjunctions
per pair are allowed, so these are harmless.

## Step alignment

`subwindow-count` should divide the window into a whole number of steps, otherwise a boundary falls between two steps. A
24h window at step-seconds=9 is 9600 steps, so values that divide 9600 (1, 2, 3, 4, 5, 6, 8, 10, 12, ...) are safe. At 7
the subwindow is 12342.857s while its last step lands at 12339s, leaving 3.857s between the two subwindows. At 28 the
subwindows overlap by 1.286s instead. A 7-day window is 67200 steps, which 28 divides.

## Recommended values

For 24h lookahead window, use 4. For 7 days, use 28 (same cache size per subwindow as 24h/4).

Higher counts cause no meaningful speed penalty, and may arguably improve performance in memory-constrained environments
by reducing GC pressure.
