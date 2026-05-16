package io.salad109.conjunctiondetector.conjunction;

import io.salad109.conjunctiondetector.conjunction.internal.ScanLog;
import io.salad109.conjunctiondetector.conjunction.internal.ScanLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ScanLogService {

    private final ScanLogRepository scanLogRepository;
    private final Clock clock;

    public ScanLogService(ScanLogRepository scanLogRepository, Clock clock) {
        this.scanLogRepository = scanLogRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ScanResult> getRecent(int n) {
        return scanLogRepository.findRecent(PageRequest.of(0, n));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void saveScanLog(OffsetDateTime startedAt, long durationMs, int satellitesScanned, int conjunctionsDetected) {
        scanLogRepository.save(new ScanLog(
                null,
                startedAt,
                OffsetDateTime.now(clock),
                durationMs,
                satellitesScanned,
                conjunctionsDetected
        ));
    }
}
