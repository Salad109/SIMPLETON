package io.salad109.conjunctiondetector.ui.internal;

import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.ingestion.IngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ScheduleService {

    private final ConjunctionService conjunctionService;
    private final IngestionService ingestionService;

    public ScheduleService(ConjunctionService conjunctionService, IngestionService ingestionService) {
        this.conjunctionService = conjunctionService;
        this.ingestionService = ingestionService;
    }

    @Scheduled(cron = "${conjunction.schedule.cron:0 21 */6 * * *}")
    public void syncAndScan() {
        try {
            ingestionService.sync();
        } catch (RuntimeException e) {
            log.warn("Sync failed. Running scan on existing catalog data: {}",
                    NestedExceptionUtils.getMostSpecificCause(e).getMessage());
        }
        conjunctionService.findConjunctions();
    }
}
