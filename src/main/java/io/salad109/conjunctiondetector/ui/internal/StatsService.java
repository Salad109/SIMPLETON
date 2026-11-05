package io.salad109.conjunctiondetector.ui.internal;

import io.salad109.conjunctiondetector.DataChangedEvent;
import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.conjunction.ScanLogService;
import io.salad109.conjunctiondetector.conjunction.ScanResult;
import io.salad109.conjunctiondetector.ingestion.IngestionLogService;
import io.salad109.conjunctiondetector.ingestion.SyncResult;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import io.salad109.conjunctiondetector.satellite.SatelliteService.CatalogBreakdown;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Slf4j
@Service
public class StatsService {

    private final SatelliteService satelliteService;
    private final ConjunctionService conjunctionService;
    private final ScanLogService scanLogService;
    private final IngestionLogService ingestionLogService;

    public StatsService(SatelliteService satelliteService,
                        ConjunctionService conjunctionService,
                        ScanLogService scanLogService,
                        IngestionLogService ingestionLogService) {
        this.satelliteService = satelliteService;
        this.conjunctionService = conjunctionService;
        this.scanLogService = scanLogService;
        this.ingestionLogService = ingestionLogService;
    }

    @Cacheable("stats")
    public StatsSnapshot getSnapshot() {
        log.debug("Computing fresh stats snapshot");
        return new StatsSnapshot(
                satelliteService.count(),
                conjunctionService.countActive(),
                conjunctionService.countHighRisk(),
                satelliteService.getCatalogBreakdown(11),
                ingestionLogService.getRecent(8),
                scanLogService.getRecent(100)
        );
    }

    @CacheEvict(value = "stats", allEntries = true)
    @TransactionalEventListener(DataChangedEvent.class)
    public void evict() {
        log.debug("Stats cache evicted");
    }

    public record StatsSnapshot(
            long satelliteCount,
            long activeConjunctionCount,
            long highRiskCount,
            CatalogBreakdown breakdown,
            List<SyncResult> recentSyncs,
            List<ScanResult> chartLogs
    ) {
        public StatsSnapshot {
            recentSyncs = List.copyOf(recentSyncs);
            chartLogs = List.copyOf(chartLogs);
        }

        public List<ScanResult> recentScans() {
            return chartLogs.subList(0, Math.min(8, chartLogs.size()));
        }
    }
}
