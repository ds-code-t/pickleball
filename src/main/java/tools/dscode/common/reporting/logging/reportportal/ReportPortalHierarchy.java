package tools.dscode.common.reporting.logging.reportportal;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import io.reactivex.Maybe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ReportPortalHierarchy {

    public static volatile Launch launch;

    private static volatile ReportPortal reportPortal;
    private static volatile ListenerParameters parameters;
    private static volatile String launchName = "Launch";
    private static volatile String defaultSuiteName;

    private static final Object LAUNCH_LOCK = new Object();

    private static final String SUITE_PATH_SEPARATOR = "\u001F";

    private static final Map<String, Maybe<String>> SUITES = new ConcurrentHashMap<>();
    private static final AtomicReference<Maybe<String>> LAST_CREATED_SUITE = new AtomicReference<>();
    private static final ThreadLocal<Maybe<String>> CURRENT_TEST = new ThreadLocal<>();

    private ReportPortalHierarchy() {
    }

    public static void setParameters(ListenerParameters listenerParameters) {
        parameters = listenerParameters;
    }

    public static void setLaunchName(String name) {
        launchName = name;
    }

    public static void setDefaultSuiteName(String name) {
        defaultSuiteName = name;
    }

    public static Launch getLaunch() {
        Launch existing = launch;
        if (existing != null) {
            return existing;
        }

        synchronized (LAUNCH_LOCK) {
            if (launch == null) {
                reportPortal = ReportPortal.builder()
                        .withParameters(parameters)
                        .build();

                launch = reportPortal.newLaunch(buildStartLaunchRequest(reportPortal.getParameters()));
            }
            return launch;
        }
    }

    private static StartLaunchRQ buildStartLaunchRequest(ListenerParameters params) {
        StartLaunchRQ rq = new StartLaunchRQ();

        rq.setName(fallback(params.getLaunchName(), launchName));
        rq.setDescription(params.getDescription());
        rq.setStartTime(new Date());
        rq.setMode(params.getLaunchRunningMode());
        rq.setAttributes(params.getAttributes());
        rq.setRerun(params.isRerun());

        String rerunOf = params.getRerunOf();
        if (rerunOf != null && !rerunOf.isBlank()) {
            rq.setRerunOf(rerunOf);
        }

        return rq;
    }

    public static Maybe<String> getOrCreateSuite(String suiteName) {
        if (suiteName == null || suiteName.isBlank()) {
            return getDefaultOrLastSuite();
        }

        return getOrCreateSuitePath(List.of(suiteName.trim()));
    }

    public static Maybe<String> getOrCreateSuitePath(List<String> suitePath) {
        List<String> normalized = normalizePath(suitePath);

        if (normalized.isEmpty()) {
            return getDefaultOrLastSuite();
        }

        Maybe<String> parent = null;
        List<String> runningPath = new ArrayList<>();

        for (String part : normalized) {
            runningPath.add(part);

            String key = suiteKey(runningPath);
            Maybe<String> parentForThisSuite = parent;

            parent = SUITES.computeIfAbsent(key, ignored -> {
                StartTestItemRQ rq = new StartTestItemRQ();
                rq.setName(part);
                rq.setType("SUITE");
                rq.setStartTime(new Date());

                Maybe<String> suite = parentForThisSuite == null
                        ? getLaunch().startTestItem(rq)
                        : getLaunch().startTestItem(parentForThisSuite, rq);

                LAST_CREATED_SUITE.set(suite);
                return suite;
            });
        }

        return parent;
    }

    public static Maybe<String> getDefaultOrLastSuite() {
        if (defaultSuiteName != null) {
            return getOrCreateSuite(defaultSuiteName);
        }
        return LAST_CREATED_SUITE.get();
    }

    public static Maybe<String> startTest(String testName, Maybe<String> suiteId) {
        StartTestItemRQ rq = new StartTestItemRQ();
        rq.setName(testName);
        rq.setType("STEP");
        rq.setStartTime(new Date());

        Maybe<String> testId = getLaunch().startTestItem(suiteId, rq);
        CURRENT_TEST.set(testId);
        return testId;
    }

    public static Maybe<String> startTest(String testName) {
        return startTest(testName, getDefaultOrLastSuite());
    }

    public static void logToTest(Maybe<String> testId, String message, String level) {
        ReportPortal.emitLog(testId, itemUuid -> {
            var rq = new com.epam.ta.reportportal.ws.model.log.SaveLogRQ();
            rq.setItemUuid(itemUuid);
            rq.setLevel(level);
            rq.setLogTime(new Date());
            rq.setMessage(message);
            return rq;
        });
    }

    public static void logToCurrentTest(String message, String level) {
        Maybe<String> testId = CURRENT_TEST.get();
        if (testId != null) {
            logToTest(testId, message, level);
        }
    }

    public static Maybe<?> finishTest(Maybe<String> testId, String status) {
        FinishTestItemRQ rq = new FinishTestItemRQ();
        rq.setStatus(status);
        rq.setEndTime(new Date());

        Maybe<?> result = getLaunch().finishTestItem(testId, rq);
        CURRENT_TEST.remove();
        return result;
    }

    public static void finishTestAndWait(Maybe<String> testId, String status) {
        awaitReportPortal("finishing test", finishTest(testId, status));
    }

    public static void finishCurrentTest(String status) {
        Maybe<String> testId = CURRENT_TEST.get();
        if (testId != null) {
            finishTest(testId, status);
        }
    }

    public static void finishCurrentTestAndWait(String status) {
        Maybe<String> testId = CURRENT_TEST.get();
        if (testId != null) {
            finishTestAndWait(testId, status);
        }
    }

    public static Maybe<?> finishSuite(Maybe<String> suiteId, String status) {
        if (suiteId == null) {
            return Maybe.empty();
        }

        FinishTestItemRQ rq = new FinishTestItemRQ();
        rq.setStatus(status);
        rq.setEndTime(new Date());

        return getLaunch().finishTestItem(suiteId, rq);
    }

    public static void finishAllSuites(String status) {
        List<Map.Entry<String, Maybe<String>>> suites = new ArrayList<>(SUITES.entrySet());

        suites.sort(Comparator
                .comparingInt((Map.Entry<String, Maybe<String>> e) -> suiteDepth(e.getKey()))
                .reversed());

        for (Map.Entry<String, Maybe<String>> entry : suites) {
            awaitReportPortal("finishing suite '" + entry.getKey() + "'", finishSuite(entry.getValue(), status));
        }
    }

    public static void finishLaunch() {
        if (launch == null) {
            return;
        }
        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setEndTime(new Date());
        launch.finish(rq);
    }

    public static void resetLaunch() {
        synchronized (LAUNCH_LOCK) {
            launch = null;
            reportPortal = null;
            SUITES.clear();
            LAST_CREATED_SUITE.set(null);
            CURRENT_TEST.remove();
        }
    }

    private static void awaitReportPortal(String action, Maybe<?> maybe) {
        if (maybe == null) {
            return;
        }

        try {
            maybe.timeout(reportingTimeoutSeconds(), TimeUnit.SECONDS).blockingGet();
        } catch (Throwable t) {
            throw new RuntimeException("ReportPortal failed while " + action, t);
        }
    }

    private static long reportingTimeoutSeconds() {
        String configured = setting("dscode.reportportal.finishTimeoutSeconds");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1L, Long.parseLong(configured.trim()));
            } catch (NumberFormatException ignored) {
                // Fall back to ReportPortal's configured timeout below.
            }
        }

        Long rpTimeout = listenerReportingTimeoutSeconds();
        if (rpTimeout != null && rpTimeout > 0L) {
            return rpTimeout;
        }

        return 120L;
    }

    private static Long listenerReportingTimeoutSeconds() {
        ListenerParameters p = parameters;
        if (p == null) {
            return null;
        }

        try {
            Object value = p.getClass().getMethod("getReportingTimeout").invoke(p);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null) {
                String s = String.valueOf(value).trim();
                if (!s.isEmpty()) {
                    return Long.parseLong(s);
                }
            }
        } catch (Throwable ignored) {
            // Older or customized ListenerParameters may not expose this method.
        }

        return null;
    }

    private static String setting(String property) {
        String raw = System.getProperty(property);
        if (raw != null) return raw;
        String env = property.toUpperCase().replace('.', '_').replace('-', '_');
        return System.getenv(env);
    }

    private static List<String> normalizePath(List<String> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();

        for (String part : path) {
            if (part == null) continue;

            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }

        return List.copyOf(out);
    }

    private static String suiteKey(List<String> path) {
        return String.join(SUITE_PATH_SEPARATOR, path);
    }

    private static int suiteDepth(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }

        int depth = 1;
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) == SUITE_PATH_SEPARATOR.charAt(0)) {
                depth++;
            }
        }
        return depth;
    }

    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}