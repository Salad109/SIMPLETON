package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.CollisionProbabilityService;
import io.salad109.conjunctiondetector.conjunction.internal.Conjunction;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.apache.commons.lang3.time.StopWatch;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

abstract class BenchmarkRunner {

    protected static final int LOOKAHEAD_HOURS = 24;
    protected static final double THRESHOLD_KM = 5.0;
    // Re-check youngest epochs stored in catalog to not back-propagate.
    protected static final OffsetDateTime FIXED_START_TIME = OffsetDateTime
            .of(2026, 8, 3, 18, 0, 0, 0, ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);
    protected final SatelliteService satelliteService;
    protected final PropagationService propagationService;
    protected final ScanService scanService;
    protected final CollisionProbabilityService collisionProbabilityService;

    protected BenchmarkRunner(SatelliteService satelliteService, PropagationService propagationService,
                              ScanService scanService, CollisionProbabilityService collisionProbabilityService) {
        this.satelliteService = satelliteService;
        this.propagationService = propagationService;
        this.scanService = scanService;
        this.collisionProbabilityService = collisionProbabilityService;
    }

    protected BenchmarkResult runBenchmark(List<SatelliteScanInfo> satellites, ScanParams p) {
        StopWatch propagator = StopWatch.createStarted();
        Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);
        propagator.stop();

        StopWatch propagateSweep = StopWatch.createStarted();
        OffsetDateTime endTime = FIXED_START_TIME.plusHours(LOOKAHEAD_HOURS);
        PropagationService.KnotCache knots = propagationService.computeKnots(
                propagators, FIXED_START_TIME, endTime, p.stepSeconds(), p.stride());
        propagateSweep.stop();

        StopWatch interpolation = StopWatch.createStarted();
        PropagationService.PositionCache positionCache = propagationService.interpolate(knots);
        interpolation.stop();

        return runScan(satellites, p, propagators, positionCache,
                propagator.getTime(), propagateSweep.getTime(), interpolation.getTime());
    }

    /**
     * Runs scan stages (checkPairs onward) against a pre-built positionCache
     */
    protected BenchmarkResult runScan(List<SatelliteScanInfo> satellites, ScanParams p,
                                      Map<Integer, TLEPropagator> propagators,
                                      PropagationService.PositionCache positionCache,
                                      long propagatorTime, long sgp4Time, long interpTime) {
        StopWatch checkPairs = StopWatch.createStarted();
        List<ScanService.CoarseDetection> detections = scanService.checkPairs(satellites, positionCache, p.toleranceKm(), p.cellSizeKm());
        checkPairs.stop();

        StopWatch grouping = StopWatch.createStarted();
        List<ScanService.CoarseDetection> events = scanService.groupAndReduce(detections);
        grouping.stop();

        StopWatch refine = StopWatch.createStarted();
        List<ScanService.RefinedEvent> refined = scanService.refine(
                events, positionCache, propagators, p.stepSeconds(), THRESHOLD_KM);
        refine.stop();

        List<EventKey> eventKeys = refined.stream().map(EventKey::from).toList();

        StopWatch probability = StopWatch.createStarted();
        List<Conjunction> conjunctions = refined.parallelStream()
                .map(collisionProbabilityService::computeProbabilityAndBuild)
                .toList();
        probability.stop();

        long totalMs = propagatorTime + sgp4Time + interpTime
                + checkPairs.getTime() + grouping.getTime() + refine.getTime() + probability.getTime();

        log.info("tol={}km step={}s stride={} cell={}km | {}ms | prop={}ms sgp4={}ms interp={}ms check={}ms group={}ms refine={}ms pc={}ms | {} conj",
                (int) p.toleranceKm(), String.format(Locale.ROOT, "%.4f", p.stepSeconds()), p.stride(),
                String.format(Locale.ROOT, "%.1f", p.cellSizeKm()), totalMs,
                propagatorTime, sgp4Time, interpTime,
                checkPairs.getTime(), grouping.getTime(), refine.getTime(),
                probability.getTime(), conjunctions.size());

        return new BenchmarkResult(p,
                detections.size(), events.size(), conjunctions.size(),
                propagatorTime, sgp4Time, interpTime,
                checkPairs.getTime(), grouping.getTime(), refine.getTime(),
                probability.getTime(), totalMs, eventKeys);
    }

    protected List<BenchmarkResult> runIterations(List<SatelliteScanInfo> satellites,
                                                  ScanParams p, int iterations) {
        List<BenchmarkResult> results = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            results.add(runBenchmark(satellites, p));
        }
        return results;
    }

    protected void writeString(Path outputPath, String content) {
        try {
            Path parent = outputPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(outputPath, content, StandardCharsets.UTF_8);
            log.info("CSV written to: {}", outputPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write CSV to {}: {}", outputPath, e.getMessage());
        }
    }

    record BenchmarkResult(ScanParams params,
                           long detections, int events, int conjunctions,
                           long propagatorTime, long sgp4Time, long interpTime, long checkTime,
                           long groupingTime, long refineTime, long probabilityTime, long totalTime,
                           List<EventKey> refinedEvents) {
        BenchmarkResult {
            refinedEvents = List.copyOf(refinedEvents);
        }
    }
}
