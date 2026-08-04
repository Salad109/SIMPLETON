package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.PositionCache;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService.CoarseDetection;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService.RefinedEvent;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.propagation.analytical.tle.TLEPropagator;

import java.io.File;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefineTest {

    private final ScanService scanService = new ScanService(new PropagationService());

    @BeforeAll
    static void initOrekit() {
        File orekitData = new File("src/main/resources/orekit-data");
        if (orekitData.exists()) {
            DataContext.getDefault().getDataProvidersManager()
                    .addProvider(new DirectoryCrawler(orekitData));
        }
    }

    private static SatelliteScanInfoPair pair() {
        OffsetDateTime epoch = OffsetDateTime.now(ZoneOffset.UTC);
        SatelliteScanInfo a = new SatelliteScanInfo(100, "", "", epoch, 400.0, "PAYLOAD");
        SatelliteScanInfo b = new SatelliteScanInfo(200, "", "", epoch, 400.0, "PAYLOAD");
        return new SatelliteScanInfoPair(a, b);
    }

    private static PositionCache cacheWithCloseApproachAtStep1() {
        // A sits at the origin while B passes straight through it at step 1
        IntIntHashMap idMap = new IntIntHashMap();
        idMap.put(100, 0);
        idMap.put(200, 1);
        OffsetDateTime t0 = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime[] times = {t0, t0.plusSeconds(10), t0.plusSeconds(20)};
        return new PositionCache(idMap, new int[]{100, 200}, times,
                new float[][]{{0f, 0f, 0f}, {10f, 0f, -10f}},
                new float[][]{{0f, 0f, 0f}, {0f, 0f, 0f}},
                new float[][]{{0f, 0f, 0f}, {0f, 0f, 0f}});
    }

    @Test
    void propagationFailureDropsTheEventInsteadOfFailingTheScan() {
        TLEPropagator failing = mock(TLEPropagator.class);
        when(failing.getFrame()).thenThrow(new IllegalStateException("SGP4 failure"));
        Map<Integer, TLEPropagator> propagators = Map.of(100, failing, 200, failing);

        CoarseDetection detection = new CoarseDetection(pair(), 0.0, 1);

        List<RefinedEvent> refined = scanService.refine(
                List.of(detection), cacheWithCloseApproachAtStep1(), propagators, 10.0, 5.0);

        assertThat(refined).as("the failed event is dropped, not propagated as an error").isEmpty();
    }
}
