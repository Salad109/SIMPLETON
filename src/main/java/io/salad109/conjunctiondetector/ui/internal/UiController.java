package io.salad109.conjunctiondetector.ui.internal;

import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.conjunction.ScanLogService;
import io.salad109.conjunctiondetector.conjunction.ScanResult;
import io.salad109.conjunctiondetector.ingestion.IngestionLogService;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;

@Controller
public class UiController {

    private static final DateTimeFormatter CHART_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final ConjunctionService conjunctionService;
    private final IngestionLogService ingestionLogService;
    private final ScanLogService scanLogService;
    private final SatelliteService satelliteService;
    private final StatsService statsService;

    public UiController(ConjunctionService conjunctionService, IngestionLogService ingestionLogService,
                        ScanLogService scanLogService, SatelliteService satelliteService,
                        StatsService statsService) {
        this.conjunctionService = conjunctionService;
        this.ingestionLogService = ingestionLogService;
        this.scanLogService = scanLogService;
        this.satelliteService = satelliteService;
        this.statsService = statsService;
    }

    // Points span a normalized 0..100 box; the template's nested <svg> maps it onto the plot area.
    private static String svgPoints(List<ScanResult> logs, ToLongFunction<ScanResult> fn, long min, long max) {
        int n = logs.size();
        StringBuilder sb = new StringBuilder();
        // Emit points left-to-right (oldest first). Logs are newest-first, so iterate in reverse.
        for (int i = 0; i < n; i++) {
            double x = (double) i / (n - 1) * 100.0;
            double y = (max == min)
                    ? 50.0
                    : 100.0 - (double) (fn.applyAsLong(logs.get(n - 1 - i)) - min) / (max - min) * 100.0;
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%.2f,%.2f", x, y));
        }
        return sb.toString();
    }

    @GetMapping("/conjunctions")
    public String conjunctions() {
        return "conjunctions";
    }

    @GetMapping("/conjunctions/{id}")
    public String conjunction(@PathVariable Long id, Model model) {
        model.addAttribute("visualization", conjunctionService.getVisualizationData(id));
        return "visualization";
    }

    @GetMapping("/satellites/{noradId}")
    public String satellite(@PathVariable int noradId, Model model) {
        model.addAttribute("satellite", satelliteService.getDetailsByCatalogId(noradId));
        model.addAttribute("conjunctions", conjunctionService.getConjunctionInfosByNoradId(noradId));
        return "satellite";
    }

    @GetMapping("/catalog")
    public String catalog() {
        return "catalog";
    }

    @GetMapping("/playground")
    public String playground() {
        return "playground";
    }

    @GetMapping("/hx/satellites")
    public String satellitesFragment(@PageableDefault(sort = "noradCatId", direction = Sort.Direction.ASC) Pageable pageable, Model model) {
        model.addAttribute("page", satelliteService.getBriefInfos(pageable));

        Sort.Order order = pageable.getSort().stream().findFirst().orElse(null);
        model.addAttribute("sortField", order != null ? order.getProperty() : "noradCatId");
        model.addAttribute("sortDir", order != null ? order.getDirection().name().toLowerCase(Locale.ROOT) : "asc");

        return "fragments/satellite-table";
    }

    @GetMapping("/hx/conjunctions")
    public String conjunctionsFragment(@PageableDefault(sort = "tca", direction = Sort.Direction.DESC) Pageable pageable,
                                       @RequestParam(defaultValue = "false") boolean includeFormations,
                                       Model model) {
        model.addAttribute("page", conjunctionService.getConjunctions(pageable, includeFormations));
        model.addAttribute("includeFormations", includeFormations);

        Sort.Order order = pageable.getSort().stream().findFirst().orElse(null);
        model.addAttribute("sortField", order != null ? order.getProperty() : "tca");
        model.addAttribute("sortDir", order != null ? order.getDirection().name().toLowerCase(Locale.ROOT) : "desc");

        return "fragments/conjunction-table";
    }

    @GetMapping("/hx/nav-status/sync")
    public String navStatusSyncFragment(Model model) {
        model.addAttribute("log", ingestionLogService.getRecent(1).stream().findFirst().orElse(null));
        return "fragments/nav-status-sync";
    }

    @GetMapping("/hx/nav-status/scan")
    public String navStatusScanFragment(Model model) {
        model.addAttribute("scan", scanLogService.getRecent(1).stream().findFirst().orElse(null));
        return "fragments/nav-status-scan";
    }

    @GetMapping("/")
    public String stats(Model model) {
        StatsService.StatsSnapshot snap = statsService.getSnapshot();
        model.addAttribute("snap", snap);
        addChartAttributes(model, snap.chartLogs());
        return "stats";
    }

    private void addChartAttributes(Model model, List<ScanResult> chartLogs) {
        if (chartLogs.size() < 2) return;

        long conjMin = chartLogs.stream().mapToLong(ScanResult::conjunctionsDetected).min().orElse(0);
        long conjMax = chartLogs.stream().mapToLong(ScanResult::conjunctionsDetected).max().orElse(1);
        long satMin = chartLogs.stream().mapToLong(ScanResult::satellitesScanned).min().orElse(0);
        long satMax = chartLogs.stream().mapToLong(ScanResult::satellitesScanned).max().orElse(1);

        model.addAttribute("conjChartPoints", svgPoints(chartLogs, ScanResult::conjunctionsDetected, conjMin, conjMax));
        model.addAttribute("satChartPoints", svgPoints(chartLogs, ScanResult::satellitesScanned, satMin, satMax));
        model.addAttribute("conjChartMin", conjMin);
        model.addAttribute("conjChartMax", conjMax);
        model.addAttribute("satChartMin", satMin);
        model.addAttribute("satChartMax", satMax);
        model.addAttribute("chartStartLabel", chartLogs.getLast().startedAt().format(CHART_LABEL_FMT));
        model.addAttribute("chartEndLabel", chartLogs.getFirst().startedAt().format(CHART_LABEL_FMT));
    }
}
