package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.CollisionProbabilityService;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-accuracy -Dspring-boot.run.jvmArguments="-Xmx12g -Xms12g -XX:+AlwaysPreTouch"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-accuracy" "-Dspring-boot.run.jvmArguments=-Xmx12g -Xms12g -XX:+AlwaysPreTouch"
 */
@Component
@Profile("benchmark-accuracy")
public class AccuracyBenchmark extends BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AccuracyBenchmark.class);

    private static final int ITERATIONS = 5;
    private static final double TOLERANCE_KM = 72.0;

    private static final int DEFAULT_STEP_RATIO = 9;
    private static final int DEFAULT_STRIDE = 25;
    private static final double DEFAULT_CELL_RATIO = 1.20;

    private static final int[] STEP_RATIO_VALUES = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static final int[] STRIDE_VALUES = {1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 120, 125};
    private static final double[] CELL_RATIO_VALUES = {1, 1.1, 1.2, 1.3, 1.4, 1.45, 1.50, 1.55, 1.60, 1.65, 1.70, 1.75, 1.80, 1.85, 1.90, 1.95, 2, 2.05, 2.10, 2.15, 2.20, 2.25, 2.30};
    private static final double[] TOLERANCE_VALUES = {24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120, 128, 136, 144, 152, 160};

    public AccuracyBenchmark(SatelliteService satelliteService, PropagationService propagationService,
                             ScanService scanService, CollisionProbabilityService collisionProbabilityService) {
        super(satelliteService, propagationService, scanService, collisionProbabilityService);
    }

    @Override
    public void run(String @NonNull ... args) {
        log.info("");
        log.info("Starting conjunction accuracy benchmark");
        log.info("");

        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.info("Loaded {} satellites", satellites.size());

        log.info("Using fixed start time: {}", FIXED_START_TIME);
        log.info("Fixed tolerance: {} km, threshold: {} km, lookahead: {} h",
                TOLERANCE_KM, THRESHOLD_KM, LOOKAHEAD_HOURS);

        log.info("");
        log.info("Sweeping step ratio");
        log.info("Locked: stride={}, cellRatio={}", DEFAULT_STRIDE, DEFAULT_CELL_RATIO);
        {
            List<BenchmarkResult> results = new ArrayList<>();
            for (int stepRatio : STEP_RATIO_VALUES) {
                results.addAll(runIterations(satellites,
                        new ScanParams(TOLERANCE_KM, stepRatio, DEFAULT_STRIDE, DEFAULT_CELL_RATIO), ITERATIONS));
            }
            writeCsv(results, Paths.get("docs", "1-step-ratio", "conjunction_benchmark.csv"));
        }

        log.info("");
        log.info("Sweeping interpolation stride");
        log.info("Locked: stepRatio={}, cellRatio={}", DEFAULT_STEP_RATIO, DEFAULT_CELL_RATIO);
        {
            List<BenchmarkResult> results = new ArrayList<>();
            for (int stride : STRIDE_VALUES) {
                results.addAll(runIterations(satellites,
                        new ScanParams(TOLERANCE_KM, DEFAULT_STEP_RATIO, stride, DEFAULT_CELL_RATIO), ITERATIONS));
            }
            writeCsv(results, Paths.get("docs", "2-interpolation-stride", "conjunction_benchmark.csv"));
        }

        log.info("");
        log.info("Sweeping cell ratio");
        log.info("Locked: stepRatio={}, stride={}", DEFAULT_STEP_RATIO, DEFAULT_STRIDE);
        {
            List<BenchmarkResult> results = new ArrayList<>();
            for (double cellRatio : CELL_RATIO_VALUES) {
                results.addAll(runIterations(satellites,
                        new ScanParams(TOLERANCE_KM, DEFAULT_STEP_RATIO, DEFAULT_STRIDE, cellRatio), ITERATIONS));
            }
            writeCsv(results, Paths.get("docs", "3-cell-size-ratio", "conjunction_benchmark.csv"));
        }

        log.info("");
        log.info("Sweeping tolerance");
        log.info("Locked: stepRatio={}, stride={}, cellRatio={}", DEFAULT_STEP_RATIO, DEFAULT_STRIDE, DEFAULT_CELL_RATIO);
        {
            List<BenchmarkResult> results = new ArrayList<>();
            for (double toleranceKm : TOLERANCE_VALUES) {
                results.addAll(runIterations(satellites,
                        new ScanParams(toleranceKm, DEFAULT_STEP_RATIO, DEFAULT_STRIDE, DEFAULT_CELL_RATIO), ITERATIONS));
            }
            writeCsv(results, Paths.get("docs", "4-conjunction-tolerance", "conjunction_benchmark.csv"));
        }

        log.info("Benchmark complete");
        System.exit(0);
    }

}
