package io.salad109.conjunctiondetector.ui.internal;

import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@Profile("dev")
public class DevController {

    private static final Logger log = LoggerFactory.getLogger(DevController.class);

    private final IngestionService ingestionService;
    private final ConjunctionService conjunctionService;
    private final ScheduleService scheduleService;

    public DevController(IngestionService ingestionService, ConjunctionService conjunctionService,
                         ScheduleService scheduleService) {
        this.ingestionService = ingestionService;
        this.conjunctionService = conjunctionService;
        this.scheduleService = scheduleService;
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> sync() {
        ingestionService.sync();
        return ResponseEntity.ok().build();
    }


    @PostMapping("/scan")
    public ResponseEntity<Void> scanForConjunctions() {
        conjunctionService.findConjunctions();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sync-and-scan")
    public ResponseEntity<Void> syncAndScan() {
        scheduleService.syncAndScan();
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleException(RuntimeException e) {
        String cause = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        log.error("Trigger failed: {}", cause);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(cause);
    }
}
