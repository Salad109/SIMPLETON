package io.salad109.conjunctiondetector.conjunction;

import io.salad109.conjunctiondetector.DataChangedEvent;
import io.salad109.conjunctiondetector.conjunction.internal.*;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.time.StopWatch;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ConjunctionService {

    private static final Logger log = LoggerFactory.getLogger(ConjunctionService.class);

    private final SatelliteService satelliteService;
    private final ConjunctionRepository conjunctionRepository;
    private final PropagationService propagationService;
    private final ScanService scanService;
    private final CollisionProbabilityService collisionProbabilityService;
    private final ScanLogService scanLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Value("${conjunction.tolerance-km:72.0}")
    private double toleranceKm;

    @Value("${conjunction.cell-size-km:48.0}")
    private double cellSizeKm;

    @Value("${conjunction.collision-threshold-km:5.0}")
    private double thresholdKm;

    @Value("${conjunction.lookahead-hours:24}")
    private int lookaheadHours;

    @Value("${conjunction.step-seconds:9.0}")
    private double stepSeconds;

    @Value("${conjunction.interpolation-stride:50}")
    private int interpolationStride;

    @Value("${conjunction.subwindow-count:1}")
    private int subwindowCount;

    public ConjunctionService(SatelliteService satelliteService,
                              ConjunctionRepository conjunctionRepository,
                              PropagationService propagationService,
                              ScanService scanService,
                              CollisionProbabilityService collisionProbabilityService,
                              ScanLogService scanLogService,
                              ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.satelliteService = satelliteService;
        this.conjunctionRepository = conjunctionRepository;
        this.propagationService = propagationService;
        this.scanService = scanService;
        this.collisionProbabilityService = collisionProbabilityService;
        this.scanLogService = scanLogService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @PostConstruct
    void validateProperties() {
        if (toleranceKm <= 0) throw new IllegalStateException("conjunction.tolerance-km must be positive");
        if (cellSizeKm <= 0) throw new IllegalStateException("conjunction.cell-size-km must be positive");
        if (thresholdKm <= 0) throw new IllegalStateException("conjunction.collision-threshold-km must be positive");
        if (lookaheadHours <= 0) throw new IllegalStateException("conjunction.lookahead-hours must be positive");
        if (stepSeconds <= 0) throw new IllegalStateException("conjunction.step-seconds must be positive");
        if (interpolationStride <= 0)
            throw new IllegalStateException("conjunction.interpolation-stride must be positive");
        if (subwindowCount <= 0) throw new IllegalStateException("conjunction.subwindow-count must be positive");
    }

    @Transactional(readOnly = true)
    public Page<ConjunctionInfo> getConjunctions(Pageable pageable, boolean includeFormations) {
        if (includeFormations) {
            return conjunctionRepository.getConjunctionInfosWithFormations(pageable);
        } else {
            return conjunctionRepository.getConjunctionInfos(pageable);
        }
    }

    @Transactional(readOnly = true)
    public VisualizationData getVisualizationData(Long id) {
        return conjunctionRepository.getVisualizationData(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Conjunction with ID " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<ConjunctionInfo> getConjunctionInfosByNoradId(int id) {
        return conjunctionRepository.getConjunctionInfosByNoradId(id);
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return conjunctionRepository.countActive();
    }

    @Transactional(readOnly = true)
    public long countHighRisk() {
        return conjunctionRepository.countHighRisk();
    }

    @Transactional
    public void findConjunctions() {
        StopWatch stopWatch = StopWatch.createStarted();
        log.info("Starting conjunction screening...");

        OffsetDateTime startedAt = OffsetDateTime.now(clock);

        // Load satellites
        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.debug("Loaded {} satellites", satellites.size());

        // Build propagators
        Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);

        // Split the lookahead window into subwindows to cap PositionCache memory
        OffsetDateTime windowEnd = startedAt.plusHours(lookaheadHours);
        long subwindowNanos = Duration.between(startedAt, windowEnd).toNanos() / subwindowCount;

        List<ScanService.RefinedEvent> allRefined = new ArrayList<>();

        for (int w = 0; w < subwindowCount; w++) {
            OffsetDateTime subStart = startedAt.plusNanos(w * subwindowNanos);
            OffsetDateTime subEnd = (w == subwindowCount - 1) ? windowEnd : startedAt.plusNanos((w + 1) * subwindowNanos);

            // SGP4 at stride points
            PropagationService.KnotCache knots = propagationService.computeKnots(
                    propagators, subStart, subEnd, stepSeconds, interpolationStride);

            // Interpolate to full position cache
            PropagationService.PositionCache cache = propagationService.interpolate(knots);

            // Coarse sweep
            List<ScanService.CoarseDetection> detections = scanService.checkPairs(
                    satellites, cache, toleranceKm, cellSizeKm);

            // Sort, cluster, reduce to best-per-event
            List<ScanService.CoarseDetection> events = scanService.groupAndReduce(detections);

            // Refine
            List<ScanService.RefinedEvent> refined = scanService.refine(
                    events, cache, propagators, stepSeconds, thresholdKm);
            allRefined.addAll(refined);

            log.debug("Subwindow {}/{}: {} detections, {} events, {} refined",
                    w + 1, subwindowCount, detections.size(), events.size(), refined.size());
        }

        // Collision probability
        List<Conjunction> conjunctions = allRefined.parallelStream()
                .map(collisionProbabilityService::computeProbabilityAndBuild)
                .toList();

        // Persist
        conjunctionRepository.truncate();
        conjunctionRepository.saveAll(conjunctions);
        satelliteService.updateConjunctionCounts();

        stopWatch.stop();
        log.info("Conjunction screening completed in {}ms, found {} conjunctions",
                stopWatch.getTime(), conjunctions.size());

        scanLogService.saveScanLog(startedAt, stopWatch.getTime(), satellites.size(), conjunctions.size());
        eventPublisher.publishEvent(new DataChangedEvent());
    }
}
