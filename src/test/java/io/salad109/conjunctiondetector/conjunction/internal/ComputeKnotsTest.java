package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.KnotCache;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.PositionCache;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.propagation.analytical.tle.TLEPropagator;

import java.io.File;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComputeKnotsTest {

    // Iridium 33 (NORAD ID 24946), but any TLE would work
    private static final String IRIDIUM_TLE1 = "1 24946U 97051C   09040.78448243 +.00000153 +00000-0 +47668-4 0  9994";
    private static final String IRIDIUM_TLE2 = "2 24946 086.3994 121.7028 0002288 085.1644 274.9812 14.34219863597336";

    private static final OffsetDateTime EPOCH =
            OffsetDateTime.of(2009, 2, 9, 18, 49, 39, 0, ZoneOffset.UTC);

    private final PropagationService propagationService = new PropagationService();

    @BeforeAll
    static void initOrekit() {
        File orekitData = new File("src/main/resources/orekit-data");
        if (orekitData.exists()) {
            DataContext.getDefault().getDataProvidersManager()
                    .addProvider(new DirectoryCrawler(orekitData));
        }
    }

    @ParameterizedTest(name = "{0} s window, {1} s step, stride {2}")
    @CsvSource({
            // window shapes, stride fixed at 50
            "0, 9, 50",       // 1 step, no interval to interpolate
            "9, 9, 50",       // 2 steps, shorter than one stride
            "90, 9, 50",      // 11 steps, shorter than one stride
            "117, 9, 50",     // 14 steps, short final interval
            "3690, 9, 50",    // 411 steps, the window from IridiumCosmosBackTest
            "21600, 9, 50",   // 2401 steps, one subwindow of the tuned 24h/4 config
            // strides against a fixed 401-step window
            "3600, 9, 1",     // no interpolation
            "3600, 9, 7",     // never lands on the last step
            "3600, 9, 399",   // last knot one step short
            "3600, 9, 400",   // last knot exactly on the end
            "1000, 7, 3",     // 144 steps, window not a whole multiple of the step
    })
    void everyStepCarriesAPosition(int windowSeconds, double stepSeconds, int stride) {
        SatelliteScanInfo iridium = new SatelliteScanInfo(24946, IRIDIUM_TLE1, IRIDIUM_TLE2,
                EPOCH, 780.0, "PAYLOAD");
        Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(List.of(iridium));

        KnotCache knots = propagationService.computeKnots(
                propagators, EPOCH, EPOCH.plusSeconds(windowSeconds), stepSeconds, stride);
        PositionCache cache = propagationService.interpolate(knots);

        int totalSteps = cache.times().length;
        for (int step = 0; step < totalSteps; step++) {
            assertThat(cache.x()[0][step]).as("x at step %d of %d", step, totalSteps).isNotNaN();
        }
    }
}
