package io.salad109.conjunctiondetector.ingestion;

import io.salad109.conjunctiondetector.DataChangedEvent;
import io.salad109.conjunctiondetector.satellite.Satellite;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import io.salad109.conjunctiondetector.spacetrack.OmmRecord;
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
            List<OmmRecord> records = spaceTrackClient.fetchCatalog();
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
     * Process OMM records - upsert satellites with their current TLE data.
     */
    private ProcessingResult processRecords(List<OmmRecord> records) {
        log.debug("Processing {} records...", records.size());

        // Filter to valid records only
        LocalDateTime epochCutoff = LocalDateTime.now(clock).minusDays(30);
        List<OmmRecord> validRecords = records.stream()
                .filter(r -> r.isValid(epochCutoff))
                .toList();

        int skipped = records.size() - validRecords.size();
        log.debug("Filtered {} invalid records", skipped);

        // Extract catalog IDs
        List<Integer> catalogIds = validRecords.stream()
                .map(OmmRecord::noradCatId)
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

        for (OmmRecord omm : validRecords) {
            Satellite existing = existingById.get(omm.noradCatId());

            if (existing == null) {
                // New satellite
                toCreate.add(createSatellite(omm));
            } else if (hasChanged(existing, omm)) {
                // Existing satellite with changes
                updateSatellite(existing, omm);
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

    private void updateSatellite(Satellite sat, OmmRecord omm) {
        sat.setObjectName(omm.objectName());
        sat.setObjectId(omm.objectId());
        sat.setObjectType(omm.objectType());
        sat.setClassificationType(omm.classificationType());
        sat.setCountryCode(omm.countryCode());
        sat.setLaunchDate(omm.launchDate());
        sat.setSite(omm.site());
        sat.setDecayDate(omm.decayDate());
        sat.setEpoch(omm.getEpochUtc());
        sat.setCreationDate(omm.creationDate());
        sat.setTleLine0(omm.tleLine0());
        sat.setTleLine1(omm.tleLine1());
        sat.setTleLine2(omm.tleLine2());
        sat.setMeanMotion(omm.meanMotion());
        sat.setMeanMotionDot(omm.meanMotionDot());
        sat.setMeanMotionDdot(omm.meanMotionDdot());
        sat.setEccentricity(omm.eccentricity());
        sat.setInclination(omm.inclination());
        sat.setRaan(omm.raan());
        sat.setArgPerigee(omm.argPerigee());
        sat.setMeanAnomaly(omm.meanAnomaly());
        sat.setEphemerisType(omm.ephemerisType());
        sat.setBstar(omm.bstar());
        sat.setRcsSize(omm.rcsSize());
        sat.setElementSetNo(omm.elementSetNo());
        sat.setRevAtEpoch(omm.revAtEpoch());
        sat.setSemiMajorAxisKm(omm.semiMajorAxis());
        sat.setPeriod(omm.period());
        sat.setPerigeeKm(omm.periapsis());
        sat.setApogeeKm(omm.apoapsis());
        sat.setFileNumber(omm.file());
        sat.setGpId(omm.gpId());
    }

    private Satellite createSatellite(OmmRecord ommRecord) {
        Satellite satellite = new Satellite(ommRecord.noradCatId());
        updateSatellite(satellite, ommRecord);
        return satellite;
    }

    private boolean hasChanged(Satellite satellite, OmmRecord ommRecord) {
        return satellite.getEpoch() == null || !satellite.getEpoch().equals(ommRecord.getEpochUtc());
    }

    private record ProcessingResult(int created, int updated, int unchanged, int skipped, int deleted) {
    }
}
