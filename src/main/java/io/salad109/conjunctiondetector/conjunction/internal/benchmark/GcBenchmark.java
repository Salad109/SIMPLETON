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

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.List;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-gc -Dspring-boot.run.jvmArguments="-Xmx12g -Xms12g -XX:+AlwaysPreTouch"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-gc" "-Dspring-boot.run.jvmArguments=-Xmx12g -Xms12g -XX:+AlwaysPreTouch"
 */
@Component
@Profile("benchmark-gc")
public class GcBenchmark extends BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GcBenchmark.class);

    private static final int ITERATIONS = 10;
    private static final double TOLERANCE_KM = 72.0;
    private static final int STEP_RATIO = 8;
    private static final int INTERPOLATION_STRIDE = 50;
    private static final double CELL_RATIO = 1.50;

    public GcBenchmark(SatelliteService satelliteService, PropagationService propagationService,
                       ScanService scanService, CollisionProbabilityService collisionProbabilityService) {
        super(satelliteService, propagationService, scanService, collisionProbabilityService);
    }

    @Override
    public void run(String @NonNull ... args) {
        log.info("");
        log.info("Starting GC benchmark ({} iterations)", ITERATIONS);
        log.info("");

        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.info("Loaded {} satellites", satellites.size());

        log.info("Using fixed start time: {}", FIXED_START_TIME);
        log.info("Locked: tolerance={} km, stepRatio={}, stride={}, cellRatio={}",
                TOLERANCE_KM, STEP_RATIO, INTERPOLATION_STRIDE, CELL_RATIO);

        List<BenchmarkResult> results = runIterations(satellites,
                new ScanParams(TOLERANCE_KM, STEP_RATIO, INTERPOLATION_STRIDE, CELL_RATIO), ITERATIONS);

        String gcName = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-XX:+Use") && arg.endsWith("GC"))
                .map(arg -> arg.substring("-XX:+Use".length(), arg.length() - "GC".length()))
                .findFirst()
                .orElse("Unknown");
        writeCsv(results, Paths.get("docs", "6-gc", "gc_benchmark_" + gcName + ".csv"));

        log.info("GC benchmark complete");
        System.exit(0);
    }
}
