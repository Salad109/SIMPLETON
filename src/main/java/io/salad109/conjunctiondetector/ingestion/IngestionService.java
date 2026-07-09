package io.salad109.conjunctiondetector.ingestion;

import io.salad109.conjunctiondetector.DataChangedEvent;
import io.salad109.conjunctiondetector.satellite.Satellite;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import io.salad109.conjunctiondetector.spacetrack.GpRecord;
import io.salad109.conjunctiondetector.spacetrack.SpaceTrackClient;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final SpaceTrackClient spaceTrackClient;
    private final SatelliteService satelliteService;
    private final IngestionLogService ingestionLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public IngestionService(SpaceTrackClient spaceTrackClient,
                            SatelliteService satelliteService,
                            IngestionLogService ingestionLogService,
                            ApplicationEventPublisher eventPublisher,
                            Clock clock) {
        this.spaceTrackClient = spaceTrackClient;
        this.satelliteService = satelliteService;
        this.ingestionLogService = ingestionLogService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Perform a full catalog sync from Space-Track.
     */
    @Transactional
    public void sync() {
        log.info("Starting catalog sync...");
        StopWatch stopWatch = StopWatch.createStarted();
        OffsetDateTime startedAt = OffsetDateTime.now(clock);

        try {
            List<GpRecord> records = spaceTrackClient.fetchCatalog();
            ProcessingResult processingResult = processRecords(records);
            SyncResult syncResult = new SyncResult(startedAt,
                    processingResult.created(),
                    processingResult.updated(),
                    processingResult.unchanged(),
                    processingResult.skipped(),
                    processingResult.deleted(),
                    true);
            ingestionLogService.saveIngestionLog(syncResult, null);

            stopWatch.stop();
            log.info("Sync completed in {}ms. {} created, {} updated, {} unchanged, {} skipped, {} deleted",
                    stopWatch.getTime(),
                    processingResult.created(),
                    processingResult.updated(),
                    processingResult.unchanged(),
                    processingResult.skipped(),
                    processingResult.deleted());

            eventPublisher.publishEvent(new DataChangedEvent());
        } catch (IOException e) {
            SyncResult failedSyncResult = new SyncResult(startedAt, 0, 0, 0, 0, 0, false);
            ingestionLogService.saveIngestionLog(failedSyncResult, e.getMessage());

            log.error("Failed synchronizing with Space-Track API", e);
        }
    }

    /**
     * Process GP records - upsert satellites with their current TLE data.
     */
    private ProcessingResult processRecords(List<GpRecord> records) {
        log.debug("Processing {} records...", records.size());

        // Filter to valid records only
        // The 10-day epoch bound mirrors the query's EPOCH/>now-10
        LocalDateTime epochCutoff = LocalDateTime.now(clock).minusDays(10);
        List<GpRecord> validRecords = records.stream()
                .filter(r -> r.isValid(epochCutoff))
                .toList();

        int skipped = records.size() - validRecords.size();
        log.debug("Filtered {} invalid records", skipped);

        // Extract catalog IDs
        List<Integer> catalogIds = validRecords.stream()
                .map(GpRecord::noradCatId)
                .distinct()
                .toList();

        // Clean up removed satellites
        int deleted = satelliteService.deleteByCatalogIdsNotIn(catalogIds);
        log.debug("Deleted {} satellites no longer in catalog", deleted);

        // Load existing satellites for comparison
        Map<Integer, Satellite> existingById = satelliteService.getByCatalogIds(catalogIds);

        // Categorize records
        List<Satellite> toCreate = new ArrayList<>();
        List<Satellite> toUpdate = new ArrayList<>();
        int unchanged = 0;

        for (GpRecord gp : validRecords) {
            Satellite existing = existingById.get(gp.noradCatId());

            if (existing == null) {
                // New satellite
                toCreate.add(createSatellite(gp));
            } else if (hasChanged(existing, gp)) {
                // Existing satellite with changes
                updateSatellite(existing, gp);
                toUpdate.add(existing);
            } else {
                // Existing satellite without changes
                unchanged++;
            }
        }

        // Persist changes
        int created = satelliteService.save(toCreate);
        log.debug("Created {} new satellites", created);
        int updated = satelliteService.save(toUpdate);
        log.debug("Updated {} existing satellites", updated);

        log.debug("Processing complete: {} created, {} updated, {} unchanged, {} skipped, {} deleted",
                created, updated, unchanged, skipped, deleted);

        return new ProcessingResult(created, updated, unchanged, skipped, deleted);
    }

    private void updateSatellite(Satellite sat, GpRecord gp) {
        sat.setObjectName(gp.objectName());
        sat.setObjectId(gp.objectId());
        sat.setObjectType(gp.objectType());
        sat.setClassificationType(gp.classificationType());
        sat.setCountryCode(gp.countryCode());
        sat.setLaunchDate(gp.launchDate());
        sat.setSite(gp.site());
        sat.setDecayDate(gp.decayDate());
        sat.setEpoch(gp.getEpochUtc());
        sat.setCreationDate(gp.creationDate());
        sat.setTleLine0(gp.tleLine0());
        sat.setTleLine1(gp.tleLine1());
        sat.setTleLine2(gp.tleLine2());
        sat.setMeanMotion(gp.meanMotion());
        sat.setMeanMotionDot(gp.meanMotionDot());
        sat.setMeanMotionDdot(gp.meanMotionDdot());
        sat.setEccentricity(gp.eccentricity());
        sat.setInclination(gp.inclination());
        sat.setRaan(gp.raan());
        sat.setArgPerigee(gp.argPerigee());
        sat.setMeanAnomaly(gp.meanAnomaly());
        sat.setEphemerisType(gp.ephemerisType());
        sat.setBstar(gp.bstar());
        sat.setRcsSize(gp.rcsSize());
        sat.setElementSetNo(gp.elementSetNo());
        sat.setRevAtEpoch(gp.revAtEpoch());
        sat.setSemiMajorAxisKm(gp.semiMajorAxis());
        sat.setPeriod(gp.period());
        sat.setPerigeeKm(gp.periapsis());
        sat.setApogeeKm(gp.apoapsis());
        sat.setFileNumber(gp.file());
        sat.setGpId(gp.gpId());
    }

    private Satellite createSatellite(GpRecord gpRecord) {
        Satellite satellite = new Satellite(gpRecord.noradCatId());
        updateSatellite(satellite, gpRecord);
        return satellite;
    }

    private boolean hasChanged(Satellite satellite, GpRecord gpRecord) {
        return satellite.getEpoch() == null || !satellite.getEpoch().equals(gpRecord.getEpochUtc());
    }

    private record ProcessingResult(int created, int updated, int unchanged, int skipped, int deleted) {
    }
}
