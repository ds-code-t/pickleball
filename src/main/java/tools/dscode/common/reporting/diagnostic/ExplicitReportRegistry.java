package tools.dscode.common.reporting.diagnostic;

import tools.dscode.common.reporting.WorkBook;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ExplicitReportRegistry {
    private static final Set<String> paths = ConcurrentHashMap.newKeySet();
    private static volatile boolean defaultReportExplicit;

    private ExplicitReportRegistry() {
    }

    public static void mark(String reportPath) {
        if (reportPath == null || reportPath.isBlank()) {
            defaultReportExplicit = true;
            return;
        }
        paths.add(normalize(Path.of(reportPath)));
        String processed = reportPath.endsWith(".xlsx") ? reportPath : reportPath + ".xlsx";
        if (!processed.contains("/") && !processed.contains("\\")) processed = "reports/" + processed;
        paths.add(normalize(Path.of(processed)));
    }

    public static boolean isExplicit(WorkBook report) {
        if (report == null) return false;
        String normalized = normalize(report.outputFile);
        if (paths.contains(normalized)) return true;
        return defaultReportExplicit && normalized.endsWith(normalize(Path.of("reports/report.xlsx")));
    }

    public static void writeExplicit(Collection<WorkBook> reports) {
        if (reports == null) return;
        for (WorkBook report : reports) {
            if (!isExplicit(report)) continue;
            try {
                report.write();
            } catch (Exception e) {
                System.err.println("[Pickleball] Explicit report '" + report.outputFile + "' failed: " + e.getMessage());
            }
        }
    }

    public static void reset() {
        paths.clear();
        defaultReportExplicit = false;
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
