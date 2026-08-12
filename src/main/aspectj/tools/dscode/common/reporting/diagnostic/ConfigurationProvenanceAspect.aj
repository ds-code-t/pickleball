package tools.dscode.common.reporting.diagnostic;

import tools.dscode.testengine.PickleballRunner;

public privileged aspect ConfigurationProvenanceAspect {
    before(): execution(tools.dscode.testengine.PickleballRunner.new(..)) {
        ConfigurationProvenance.begin();
        ExplicitReportRegistry.reset();
    }

    after(PickleballRunner runner):
            call(void tools.dscode.testengine.PickleballRunner+.globalTestDefaults())
            && withincode(tools.dscode.testengine.PickleballRunner.new(..))
            && target(runner) {
        ConfigurationProvenance.capture("globalTestDefaults", runner.values());
    }

    after(PickleballRunner runner):
            call(void tools.dscode.testengine.PickleballRunner+.globalTestProperties())
            && withincode(tools.dscode.testengine.PickleballRunner.new(..))
            && target(runner) {
        ConfigurationProvenance.capture("globalTestProperties", runner.values());
    }

    after(PickleballRunner runner, String resource):
            execution(* tools.dscode.testengine.PickleballRunner.mergeResourcePropertiesIfMissing(String))
            && this(runner) && args(resource) {
        ConfigurationProvenance.capture("resource-if-missing:" + resource, runner.values());
    }

    after(PickleballRunner runner, String resource):
            execution(* tools.dscode.testengine.PickleballRunner.mergeResourcePropertiesOverwriting(String))
            && this(runner) && args(resource) {
        ConfigurationProvenance.capture("resource:" + resource, runner.values());
    }

    after(PickleballRunner runner):
            execution(* tools.dscode.testengine.PickleballRunner.mergeAllSystemProperties())
            && this(runner) {
        ConfigurationProvenance.capture("system-properties", runner.values());
    }

    after(PickleballRunner runner):
            execution(tools.dscode.testengine.PickleballRunner.new(..)) && this(runner) {
        ConfigurationProvenance.capture("resolved", runner.values());
        tools.dscode.common.variables.PlatformLogFormatter.validate(runner.values());
        SourceProvenance.validate(runner.values());
        DiagnosticRuntime.configure(runner.values());
        PickleballRunner.DIAGNOSTIC_MODE = DiagnosticRuntime.isDiagnostic();
        PickleballRunner.REPORT_RETENTION = ReportRetentionPolicy.configuredValue();
    }

    public static boolean PickleballRunner.DIAGNOSTIC_MODE = false;
    public static String PickleballRunner.REPORT_RETENTION = "all";
}
