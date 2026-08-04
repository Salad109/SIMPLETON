package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.CollisionProbabilityService;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.apache.commons.lang3.time.StopWatch;
import org.jspecify.annotations.NonNull;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-pareto -Dspring-boot.run.jvmArguments="-Xmx12g -Xms12g -XX:+AlwaysPreTouch"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-pareto" "-Dspring-boot.run.jvmArguments=-Xmx12g -Xms12g -XX:+AlwaysPreTouch"
 */
@Component
@Profile("benchmark-pareto")
public class ParetoFrontierBenchmark extends BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ParetoFrontierBenchmark.class);

    private static final double TOLERANCE_KM = 84.0;
    private static final double MIN_JACCARD = 0.99;
    private static final int ITERATIONS = 3;
    private static final Duration TCA_TOLERANCE = Duration.ofSeconds(60);

    // Scored against a stride=1 baseline run
    private static final int BASELINE_STRIDE = 1;
    private static final double BASELINE_CELL_KM = 105.0;

    private static final double[] STEP_SECONDS_VALUES = {9.375, 10.0, 10.8, 12.0};

    private static final double START_KNOT_GAP_SECONDS = 150.0;
    private static final double KNOT_GAP_DELTA = 50.0;
    private static final double MAX_KNOT_GAP_SECONDS = 700.0;

    private static final double START_CELL_KM = 84.0;
    private static final double CELL_KM_DELTA = 2.5;
    private static final double MIN_CELL_KM = 44.0;

    public ParetoFrontierBenchmark(SatelliteService satelliteService, PropagationService propagationService,
                                   ScanService scanService, CollisionProbabilityService collisionProbabilityService) {
        super(satelliteService, propagationService, scanService, collisionProbabilityService);
    }

    private static BenchmarkResult medianRun(List<BenchmarkResult> results) {
        return results.stream()
                .sorted(Comparator.comparingLong(BenchmarkResult::totalTime))
                .toList()
                .get(results.size() / 2);
    }

    @Override
    public void run(String @NonNull ... args) {
        log.info("");
        log.info("Starting Pareto frontier benchmark (bounded grid search, uncapped parameters)");
        log.info("Lookahead: {}h, accuracy: Jaccard vs safe baseline (TCA tolerance {}s)",
                LOOKAHEAD_HOURS, TCA_TOLERANCE.toSeconds());
        log.info("Minimum Jaccard threshold: {}", MIN_JACCARD);
        log.info("");

        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.info("Loaded {} satellites", satellites.size());
        log.info("Using fixed start time: {}", FIXED_START_TIME);

        log.info("Ground truth (stride={}, also serves as JVM warmup)...", BASELINE_STRIDE);
        ScanParams baseline = new ScanParams(TOLERANCE_KM, STEP_SECONDS_VALUES[0], BASELINE_STRIDE, BASELINE_CELL_KM);
        BenchmarkResult groundTruthResult = runBenchmark(satellites, baseline);
        List<EventKey> safeEvents = groundTruthResult.refinedEvents();
        log.info("Ground truth: {} conjunctions, {} refined events ({}s)",
                groundTruthResult.conjunctions(), safeEvents.size(), groundTruthResult.totalTime() / 1000.0);

        EventMatcher.MatchStats selfCheck = EventMatcher.match(safeEvents, safeEvents, TCA_TOLERANCE);
        log.info("Self-match sanity check: matched={}, oursOnly={}, safeOnly={}, jaccard={}",
                selfCheck.matched(), selfCheck.oursOnly(), selfCheck.safeOnly(),
                String.format(Locale.ROOT, "%.4f", selfCheck.jaccard()));
        if (selfCheck.jaccard() != 1.0) {
            log.error("Self-match did not produce jaccard=1.0; matcher is broken. Aborting.");
            System.exit(1);
        }

        BenchmarkCsv csv = new BenchmarkCsv(BenchmarkCsv.Group.PARAMS, BenchmarkCsv.Group.COUNTS,
                BenchmarkCsv.Group.TIMINGS, BenchmarkCsv.Group.MATCH);
        int evaluated = 0;

        for (double stepSeconds : STEP_SECONDS_VALUES) {
            boolean anyValidAtThisStep = false;

            for (int gapStep = 0; ; gapStep++) {
                double knotGap = START_KNOT_GAP_SECONDS + gapStep * KNOT_GAP_DELTA;
                if (knotGap > MAX_KNOT_GAP_SECONDS) break;
                boolean anyValidAtThisStride = false;
                int stride = ScanParams.ofKnotGap(TOLERANCE_KM, stepSeconds, knotGap, START_CELL_KM).stride();

                // Cache propagation once per (step, stride)
                StopWatch propTimer = StopWatch.createStarted();
                Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);
                propTimer.stop();
                StopWatch sgp4Timer = StopWatch.createStarted();
                PropagationService.KnotCache knots = propagationService.computeKnots(
                        propagators, FIXED_START_TIME, FIXED_START_TIME.plusHours(LOOKAHEAD_HOURS),
                        stepSeconds, stride);
                sgp4Timer.stop();
                StopWatch interpTimer = StopWatch.createStarted();
                PropagationService.PositionCache positionCache = propagationService.interpolate(knots);
                interpTimer.stop();
                long propMs = propTimer.getTime();
                long sgp4Ms = sgp4Timer.getTime();
                long interpMs = interpTimer.getTime();
                log.info("Cached propagation for step={}s knotGap={}s (stride {}): prop={}ms sgp4={}ms interp={}ms",
                        String.format(Locale.ROOT, "%.4f", stepSeconds),
                        String.format(Locale.ROOT, "%.0f", knotGap), stride, propMs, sgp4Ms, interpMs);

                for (int cellStep = 0; ; cellStep++) {
                    double cellKm = START_CELL_KM - cellStep * CELL_KM_DELTA;
                    if (cellKm < MIN_CELL_KM) break;
                    ScanParams p = new ScanParams(TOLERANCE_KM, stepSeconds, stride, cellKm);

                    List<BenchmarkResult> runs = new ArrayList<>();
                    runs.add(runScan(satellites, p, propagators, positionCache, propMs, sgp4Ms, interpMs));
                    EventMatcher.MatchStats stats =
                            EventMatcher.match(safeEvents, runs.getFirst().refinedEvents(), TCA_TOLERANCE);
                    if (stats.jaccard() >= MIN_JACCARD) {
                        for (int i = 1; i < ITERATIONS; i++) {
                            runs.add(runScan(satellites, p, propagators, positionCache, propMs, sgp4Ms, interpMs));
                        }
                    }
                    BenchmarkResult result = medianRun(runs);
                    evaluated++;

                    log.info(String.format(Locale.ROOT,
                            "[%d] step=%.4fs knotGap=%.0fs cell=%.1fkm | %d conj | matched=%d oursOnly=%d safeOnly=%d | jaccard=%.4f | %.1fs",
                            evaluated, p.stepSeconds(), p.knotGapSeconds(), p.cellSizeKm(),
                            result.conjunctions(),
                            stats.matched(), stats.oursOnly(), stats.safeOnly(),
                            stats.jaccard(),
                            result.totalTime() / 1000.0));

                    csv.addRow(result, stats);

                    if (stats.jaccard() >= MIN_JACCARD) {
                        anyValidAtThisStride = true;
                        anyValidAtThisStep = true;
                    } else {
                        log.info("  Jaccard < {}, pruning remaining cell sizes", MIN_JACCARD);
                        break;
                    }
                }

                if (!anyValidAtThisStride) {
                    log.info("  No valid cell size at knotGap={}s, pruning remaining knot gaps",
                            String.format(Locale.ROOT, "%.0f", knotGap));
                    break;
                }
            }

            if (!anyValidAtThisStep) {
                log.info("  No valid combo at step={}s, pruning remaining step sizes",
                        String.format(Locale.ROOT, "%.4f", stepSeconds));
                break;
            }
        }

        log.info("");
        log.info("Grid search complete. {} points evaluated.", evaluated);

        writeString(Paths.get("docs", "5-pareto-frontier", "pareto_benchmark.csv"), csv.build());

        log.info("Pareto frontier benchmark complete.");
        System.exit(0);
    }
}
