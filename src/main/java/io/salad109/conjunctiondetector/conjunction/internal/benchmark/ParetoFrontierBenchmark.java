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

import java.nio.file.Path;
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

    private static final double TOLERANCE_KM = 72.0;
    private static final double MIN_JACCARD = 0.98;
    private static final int ITERATIONS = 3;
    private static final Duration TCA_TOLERANCE = Duration.ofSeconds(60);

    // Starting values (safest)
    private static final int START_STEP_RATIO = 9;
    private static final int START_STRIDE = 20;
    private static final double START_CELL_RATIO = 0.8;

    // Step sizes
    private static final int STEP_RATIO_DELTA = 1;
    private static final int STRIDE_DELTA = 5;
    private static final double CELL_RATIO_DELTA = 0.1;

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

        log.info("Ground truth ({} runs, also serves as JVM warmup)...", ITERATIONS);
        ScanParams baseline = new ScanParams(TOLERANCE_KM, START_STEP_RATIO, START_STRIDE, START_CELL_RATIO);
        List<BenchmarkResult> groundTruthRuns = runIterations(satellites, baseline, ITERATIONS);
        BenchmarkResult groundTruthResult = medianRun(groundTruthRuns);
        List<EventKey> safeEvents = groundTruthRuns.getFirst().refinedEvents();
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

        List<Row> rows = new ArrayList<>();
        int evaluated = 0;

        for (int stepRatio = START_STEP_RATIO; ; stepRatio -= STEP_RATIO_DELTA) {
            boolean anyValidAtThisStep = false;
            double stepSeconds = TOLERANCE_KM / stepRatio;

            for (int stride = START_STRIDE; ; stride += STRIDE_DELTA) {
                boolean anyValidAtThisStride = false;

                // Cache propagation once per (stepRatio, stride)
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
                log.info("Cached propagation for stepRatio={} stride={}: prop={}ms sgp4={}ms interp={}ms",
                        stepRatio, stride, propMs, sgp4Ms, interpMs);

                for (double cellRatio = START_CELL_RATIO; ; cellRatio += CELL_RATIO_DELTA) {
                    ScanParams p = new ScanParams(TOLERANCE_KM, stepRatio, stride, cellRatio);
                    boolean isBaseline = p.stepRatio() == START_STEP_RATIO
                            && p.stride() == START_STRIDE
                            && Math.abs(p.cellRatio() - START_CELL_RATIO) < 0.001;

                    BenchmarkResult result;
                    EventMatcher.MatchStats stats;
                    if (isBaseline) {
                        result = groundTruthResult;
                        stats = selfCheck;
                    } else {
                        List<BenchmarkResult> runs = new ArrayList<>();
                        runs.add(runScan(satellites, p, propagators, positionCache, propMs, sgp4Ms, interpMs));
                        stats = EventMatcher.match(safeEvents, runs.getFirst().refinedEvents(), TCA_TOLERANCE);
                        if (stats.jaccard() >= MIN_JACCARD) {
                            for (int i = 1; i < ITERATIONS; i++) {
                                runs.add(runScan(satellites, p, propagators, positionCache, propMs, sgp4Ms, interpMs));
                            }
                        }
                        result = medianRun(runs);
                    }
                    evaluated++;

                    log.info(String.format(Locale.ROOT,
                            "[%d] stepRatio=%d stride=%d cellRatio=%.1f | %d conj | matched=%d oursOnly=%d safeOnly=%d | jaccard=%.4f | %.1fs",
                            evaluated, p.stepRatio(), p.stride(), p.cellRatio(),
                            result.conjunctions(),
                            stats.matched(), stats.oursOnly(), stats.safeOnly(),
                            stats.jaccard(),
                            result.totalTime() / 1000.0));

                    rows.add(new Row(result, stats));

                    if (stats.jaccard() >= MIN_JACCARD) {
                        anyValidAtThisStride = true;
                        anyValidAtThisStep = true;
                    } else {
                        log.info("  Jaccard < {}, pruning remaining cellRatio values", MIN_JACCARD);
                        break;
                    }
                }

                if (!anyValidAtThisStride) {
                    log.info("  No valid cellRatio at stride={}, pruning remaining stride values", stride);
                    break;
                }
            }

            if (!anyValidAtThisStep) {
                log.info("  No valid combo at stepRatio={}, pruning remaining stepRatio values", stepRatio);
                break;
            }
        }

        log.info("");
        log.info("Grid search complete. {} points evaluated.", evaluated);

        writeParetoCsv(rows, Paths.get("docs", "5-pareto-frontier", "pareto_benchmark.csv"));

        log.info("Pareto frontier benchmark complete.");
        System.exit(0);
    }

    private void writeParetoCsv(List<Row> rows, Path outputPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("tolerance_km,step_ratio,cell_ratio,interp_stride,detections,events,conj,")
                .append("matched,ours_only,safe_only,jaccard,")
                .append("propagator_s,sgp4_s,interp_s,check_s,grouping_s,refine_s,probability_s,total_s\n");
        for (Row row : rows) {
            BenchmarkResult r = row.result();
            ScanParams p = r.params();
            EventMatcher.MatchStats s = row.stats();
            sb.append(String.format(Locale.ROOT,
                    "%.0f,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    p.toleranceKm(), p.stepRatio(), p.cellRatio(), p.stride(),
                    r.detections(), r.events(), r.conjunctions(),
                    s.matched(), s.oursOnly(), s.safeOnly(), s.jaccard(),
                    r.propagatorTime() / 1000.0, r.sgp4Time() / 1000.0,
                    r.interpTime() / 1000.0, r.checkTime() / 1000.0,
                    r.groupingTime() / 1000.0, r.refineTime() / 1000.0,
                    r.probabilityTime() / 1000.0, r.totalTime() / 1000.0));
        }
        writeString(outputPath, sb.toString());
    }

    private record Row(BenchmarkResult result, EventMatcher.MatchStats stats) {
    }
}
