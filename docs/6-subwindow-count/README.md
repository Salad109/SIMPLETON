# Subwindow Count

The PositionCache is `numSats * totalSteps * 3 floats * 4 bytes`. At 30k satellites and step-seconds=12, a 24h window
is 2.6 GB. A 7-day window is 18.1 GB. This is too large for most systems.

`subwindow-count` splits the lookahead window into N sequential chunks. Each chunk runs the cache-dependent stages
(propagate, interpolate, coarse scan, group, refine) and the cache goes out of scope before the next chunk starts.
Collision probability and persistence run once after all chunks finish. Peak cache memory is roughly `1/N` of the
single-window case.

`subwindow-count=1` effectively disables subwindowing.

## Cache size estimates

Rough PositionCache size per subwindow for 30k satellites at step-seconds=12:

| Window   | Count | Samples/Sub | Cache/Sub |
|----------|-------|-------------|-----------|
| 24 hours | 1     | 7,201       | 2.6 GB    |
| 24 hours | 4     | 1,801       | 0.6 GB    |
| 24 hours | 8     | 901         | 0.3 GB    |
| 7 days   | 7     | 7,201       | 2.6 GB    |
| 7 days   | 14    | 3,601       | 1.3 GB    |
| 7 days   | 28    | 1,801       | 0.6 GB    |

These are float array sizes only. Actual heap is higher: the KnotCache stays reachable while the PositionCache is built,
adding another 10% at 21 steps per knot, on top of intermediate collections, Spring Boot and the JVM. At very high
counts the constant overhead dominates and cache savings become negligible in
practice.

## Boundary handling

Subwindow endpoints are inclusive, so consecutive subwindows share their boundary time step. Without it, the interval
between them would belong to neither subwindow, and a conjunction there would be missed. On a 24h/4 config, that is 3
intervals in 7200, or 0.04%.

## Duplicates

Two things put a redundant row on a pair: an approach landing on a shared boundary step gets stored by both subwindows,
and a pair within tolerance for the whole window yields one conjunction in every subwindow. Screening one window as a
single pass and again as four subwindows gives 264 redundant rows, or 0.45%. Formation flight accounts for almost all
of them; 90 of the 94 pairs that gain a row close at under 10 m/s, and exactly 1 row comes from a shared boundary step.
Multiple conjunctions per pair are allowed, so these are harmless.

## Step alignment

`subwindow-count` must divide the window into a whole number of steps, and `ConjunctionService.validate` rejects
anything else at startup. A 24h window at step-seconds=12 is 7200 steps, so every divisor of 7200 is legal; up to 50
that is 1 to 6, 8, 9, 10, 12, 15, 16, 18, 20, 24, 25, 30, 32, 36, 40, 45, 48 and 50. Knot times round to the nearest
whole step, so an illegal count puts the boundary on the wrong side of one: at 7 the subwindows would overlap by 5.1s,
at 14 they would leave a 3.4s gap.

## Recommended values

For 24h lookahead window, use 4. For 7 days, use 28 (same cache size per subwindow as 24h/4). Runtime across subwindow
counts has not been measured.
