package io.salad109.conjunctiondetector.ui.internal;

import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.ingestion.IngestionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@Profile("dev")
public class DevController {

    private final IngestionService ingestionService;
    private final ConjunctionService conjunctionService;

    public DevController(IngestionService ingestionService, ConjunctionService conjunctionService) {
        this.ingestionService = ingestionService;
        this.conjunctionService = conjunctionService;
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
        ingestionService.sync();
        conjunctionService.findConjunctions();
        return ResponseEntity.ok().build();
    }
}
