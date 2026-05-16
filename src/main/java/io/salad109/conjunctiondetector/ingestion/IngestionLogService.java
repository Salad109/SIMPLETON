package io.salad109.conjunctiondetector.ingestion;

import io.salad109.conjunctiondetector.ingestion.internal.IngestionLog;
import io.salad109.conjunctiondetector.ingestion.internal.IngestionLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class IngestionLogService {

    private final IngestionLogRepository ingestionLogRepository;
    private final Clock clock;

    public IngestionLogService(IngestionLogRepository ingestionLogRepository, Clock clock) {
        this.ingestionLogRepository = ingestionLogRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SyncResult> getRecent(int n) {
        return ingestionLogRepository.findRecent(PageRequest.of(0, n));
    }

    /**
     * Save an ingestion log entry in a new transaction. REQUIRES_NEW ensures log isn't rolled back if sync fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void saveIngestionLog(SyncResult syncResult, String errorMessage) {
        ingestionLogRepository.save(new IngestionLog(
                null,
                syncResult.startedAt(),
                OffsetDateTime.now(clock),
                syncResult.objectsInserted(),
                syncResult.objectsUpdated(),
                syncResult.objectsUnchanged(),
                syncResult.objectsSkipped(),
                syncResult.objectsDeleted(),
                syncResult.successful(),
                errorMessage
        ));
    }
}
