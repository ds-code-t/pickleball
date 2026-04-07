package tools.dscode.common.reporting.logging.reportportal;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import io.reactivex.Maybe;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class ReportPortalHierarchy {

    public static volatile Launch launch;

    private static volatile ReportPortal reportPortal;
    private static volatile ListenerParameters parameters;
    private static volatile String launchName = "Launch";
    private static volatile String defaultSuiteName;

    private static final Object LAUNCH_LOCK = new Object();

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

                StartLaunchRQ rq = new StartLaunchRQ();
                rq.setName(launchName);
                rq.setStartTime(new Date());

                launch = reportPortal.newLaunch(rq);
            }
            return launch;
        }
    }

    public static Maybe<String> getOrCreateSuite(String suiteName) {
        return SUITES.computeIfAbsent(suiteName, name -> {
            StartTestItemRQ rq = new StartTestItemRQ();
            rq.setName(name);
            rq.setType("SUITE");
            rq.setStartTime(new Date());

            Maybe<String> suite = getLaunch().startTestItem(rq);
            LAST_CREATED_SUITE.set(suite);
            return suite;
        });
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
        rq.setType("TEST");
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

    public static void finishTest(Maybe<String> testId, String status) {
        FinishTestItemRQ rq = new FinishTestItemRQ();
        rq.setStatus(status);
        rq.setEndTime(new Date());
        getLaunch().finishTestItem(testId, rq);
        CURRENT_TEST.remove();
    }

    public static void finishCurrentTest(String status) {
        Maybe<String> testId = CURRENT_TEST.get();
        if (testId != null) {
            finishTest(testId, status);
        }
    }

    public static void finishSuite(Maybe<String> suiteId, String status) {
        FinishTestItemRQ rq = new FinishTestItemRQ();
        rq.setStatus(status);
        rq.setEndTime(new Date());
        getLaunch().finishTestItem(suiteId, rq);
    }

    public static void finishLaunch() {
        if (launch == null) {
            return;
        }
        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setEndTime(new Date());
        launch.finish(rq);
    }
}