package io.salad109.conjunctiondetector.ui.internal;

import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

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
