//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.cucumber.core.cli;

import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.CucumberProperties;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.Runtime;
import java.util.Optional;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import tools.ds.modkit.EnsureInstalled;

@API(
        status = Status.STABLE
)
public class Main {
    static {
        EnsureInstalled.ensureOrDie();
    }

    public Main() {
        System.out.println("@@Main-1");
    }

    public static void main(String... argv) {
        System.out.println("@@Main-2");
        byte exitStatus = run(argv, Thread.currentThread().getContextClassLoader());
        System.exit(exitStatus);
    }

    public static byte run(String... argv) {
        return run(argv, Thread.currentThread().getContextClassLoader());
    }

    public static byte run(String[] argv, ClassLoader classLoader) {
        RuntimeOptions propertiesFileOptions = (new CucumberPropertiesParser()).parse(CucumberProperties.fromPropertiesFile()).build();
        RuntimeOptions environmentOptions = (new CucumberPropertiesParser()).parse(CucumberProperties.fromEnvironment()).build(propertiesFileOptions);
        RuntimeOptions systemOptions = (new CucumberPropertiesParser()).parse(CucumberProperties.fromSystemProperties()).build(environmentOptions);
        CommandlineOptionsParser commandlineOptionsParser = new CommandlineOptionsParser(System.out);
        RuntimeOptions runtimeOptions = commandlineOptionsParser.parse(argv).addDefaultGlueIfAbsent().addDefaultFeaturePathIfAbsent().addDefaultSummaryPrinterIfNotDisabled().enablePublishPlugin().build(systemOptions);
        Optional<Byte> exitStatus = commandlineOptionsParser.exitStatus();
        if (exitStatus.isPresent()) {
            return (Byte)exitStatus.get();
        } else {
            Runtime runtime = Runtime.builder().withRuntimeOptions(runtimeOptions).withClassLoader(() -> {
                return classLoader;
            }).build();
            runtime.run();
            return runtime.exitStatus();
        }
    }
}
