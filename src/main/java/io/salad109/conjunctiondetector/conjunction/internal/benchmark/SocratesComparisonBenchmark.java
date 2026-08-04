package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.CollisionProbabilityService;
import io.salad109.conjunctiondetector.conjunction.internal.Conjunction;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.apache.commons.lang3.time.StopWatch;
import org.jspecify.annotations.NonNull;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-socrates -Dspring-boot.run.jvmArguments="-Xmx16g -Xms16g -XX:+AlwaysPreTouch"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-socrates" "-Dspring-boot.run.jvmArguments=-Xmx16g -Xms16g -XX:+AlwaysPreTouch"
 */
@Component
@Profile("benchmark-socrates")
public class SocratesComparisonBenchmark implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SocratesComparisonBenchmark.class);

    private static final OffsetDateTime START_TIME = OffsetDateTime
            .of(2026, 5, 9, 19, 0, 0, 0, ZoneOffset.UTC);
    private static final int LOOKAHEAD_HOURS = 168;
    private static final int SUBWINDOW_COUNT = 8;
    private static final Path OUTPUT_DIR = Paths.get("docs", "8-socrates-comparison");
    private static final double THRESHOLD_KM = 5.0;
    private static final String OUTPUT_NAME = "ours.csv";

    private final SatelliteService satelliteService;
    private final PropagationService propagationService;
    private final ScanService scanService;
    private final CollisionProbabilityService collisionProbabilityService;

    @Value("${conjunction.tolerance-km}")
    private double toleranceKm;

    @Value("${conjunction.cell-size-km}")
    private double cellSizeKm;

    @Value("${conjunction.step-seconds}")
    private double stepSeconds;

    @Value("${conjunction.interpolation-stride}")
    private int interpolationStride;

    public SocratesComparisonBenchmark(SatelliteService satelliteService,
                                       PropagationService propagationService,
                                       ScanService scanService,
                                       CollisionProbabilityService collisionProbabilityService) {
        this.satelliteService = satelliteService;
        this.propagationService = propagationService;
        this.scanService = scanService;
        this.collisionProbabilityService = collisionProbabilityService;
    }

    @Override
    public void run(String @NonNull ... args) {
        OffsetDateTime windowEnd = START_TIME.plusHours(LOOKAHEAD_HOURS);

        log.info("");
        log.info("SOCRATES comparison run");
        log.info("Window: {} -> {} ({} h, {} subwindows)", START_TIME, windowEnd, LOOKAHEAD_HOURS, SUBWINDOW_COUNT);
        log.info("Tolerance: {} km, cell: {} km, threshold: {} km, step: {} s, stride: {}",
                toleranceKm, cellSizeKm, THRESHOLD_KM, stepSeconds, interpolationStride);
        log.info("");

        StopWatch total = StopWatch.createStarted();

        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.info("Loaded {} satellites", satellites.size());

        Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);
        log.info("Built {} propagators", propagators.size());

        long subwindowNanos = Duration.between(START_TIME, windowEnd).toNanos() / SUBWINDOW_COUNT;
        List<ScanService.RefinedEvent> allRefined = new ArrayList<>();

        for (int w = 0; w < SUBWINDOW_COUNT; w++) {
            OffsetDateTime subStart = START_TIME.plusNanos(w * subwindowNanos);
            OffsetDateTime subEnd = (w == SUBWINDOW_COUNT - 1) ? windowEnd : START_TIME.plusNanos((w + 1) * subwindowNanos);

            StopWatch sub = StopWatch.createStarted();
            PropagationService.KnotCache knots = propagationService.computeKnots(
                    propagators, subStart, subEnd, stepSeconds, interpolationStride);
            PropagationService.PositionCache cache = propagationService.interpolate(knots);
            List<ScanService.CoarseDetection> detections = scanService.checkPairs(
                    satellites, cache, toleranceKm, cellSizeKm);
            List<ScanService.CoarseDetection> events = scanService.groupAndReduce(detections);
            List<ScanService.RefinedEvent> refined = scanService.refine(
                    events, cache, propagators, stepSeconds, THRESHOLD_KM);
            allRefined.addAll(refined);
            sub.stop();

            log.info("Subwindow {}/{} [{} -> {}]: {} detections, {} events, {} refined ({}ms)",
                    w + 1, SUBWINDOW_COUNT, subStart, subEnd,
                    detections.size(), events.size(), refined.size(), sub.getTime());
        }

        log.info("Computing collision probabilities for {} refined events", allRefined.size());
        List<Conjunction> conjunctions = allRefined.parallelStream()
                .map(collisionProbabilityService::computeProbabilityAndBuild)
                .toList();
        log.info("Threshold {} km -> {} conjunctions", THRESHOLD_KM, conjunctions.size());
        writeConjunctionCsv(conjunctions, OUTPUT_DIR.resolve(OUTPUT_NAME));

        total.stop();
        log.info("SOCRATES comparison run complete in {}ms", total.getTime());
        System.exit(0);
    }

    private void writeConjunctionCsv(List<Conjunction> conjunctions, Path outputPath) {
        DateTimeFormatter tcaFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        StringBuilder sb = new StringBuilder();
        sb.append("norad1,norad2,tca,miss_distance_km,relative_speed_km_s,collision_probability\n");
        for (Conjunction c : conjunctions) {
            sb.append(c.getObject1NoradId()).append(',')
                    .append(c.getObject2NoradId()).append(',')
                    .append(c.getTca().withOffsetSameInstant(ZoneOffset.UTC).format(tcaFormat)).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", c.getMissDistanceKm())).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", c.getRelativeVelocityMS() / 1000.0)).append(',')
                    .append(String.format(Locale.ROOT, "%.6e", c.getCollisionProbability()))
                    .append('\n');
        }
        try {
            Path parent = outputPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
            log.info("CSV written to {}", outputPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write CSV to {}: {}", outputPath, e.getMessage());
        }
    }
}
