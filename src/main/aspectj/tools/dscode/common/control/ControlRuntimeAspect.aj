package tools.dscode.common.control;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

/** Core-only semantic interception layer. No MCP/Spring dependency is introduced here. */
@Aspect
public class ControlRuntimeAspect {

    @Around("execution(public static io.cucumber.core.runner.StepExtension io.cucumber.core.runner.GlobalState.getRunningStep())")
    public Object runningStep(ProceedingJoinPoint joinPoint) throws Throwable {
        StepExtension override = ControlExecutionScope.currentStepOverride();
        return override == null ? joinPoint.proceed() : override;
    }

    /** Suppresses raw runner stack-trace printing for failures intentionally returned by detached control calls. */
    @Around("call(public void java.lang.Throwable.printStackTrace())"
            + " && withincode(public Object io.cucumber.core.runner.StepExtension+.runAndGetReturnValue())")
    public Object detachedFailureStackTrace(ProceedingJoinPoint joinPoint) throws Throwable {
        return ControlExecutionScope.currentStepOverride() == null
                ? joinPoint.proceed()
                : null;
    }

    @Before("execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())")
    public void beforeScenario(JoinPoint joinPoint) {
        CurrentScenarioState state = (CurrentScenarioState) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.SCENARIO_START, signature(joinPoint), state);
    }

    @AfterReturning("execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())")
    public void afterScenario(JoinPoint joinPoint) {
        CurrentScenarioState state = (CurrentScenarioState) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.SCENARIO_END, signature(joinPoint), state);
    }

    @AfterThrowing(
            pointcut = "execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())",
            throwing = "error"
    )
    public void failedScenario(JoinPoint joinPoint, Throwable error) {
        CurrentScenarioState state = (CurrentScenarioState) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.SCENARIO_END, signature(joinPoint), state, error);
    }

    @Around("execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.StepExtension+.run())")
    public Object runStep(ProceedingJoinPoint joinPoint) throws Throwable {
        StepExtension step = (StepExtension) joinPoint.getThis();
        boolean skip = ControlRuntime.fire(
                ControlHook.BEFORE_STEP,
                signature(joinPoint),
                step
        ).skip();
        if (!skip) {
            return joinPoint.proceed();
        }

        boolean previousLogAndIgnore = step.logAndIgnore;
        step.logAndIgnore = true;
        step.skipped = true;
        try {
            return joinPoint.proceed();
        } finally {
            step.logAndIgnore = previousLogAndIgnore;
        }
    }

    @AfterReturning(
            pointcut = "execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.StepExtension+.run())",
            returning = "result"
    )
    public void afterStep(JoinPoint joinPoint, Result result) {
        StepExtension step = (StepExtension) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_STEP, signature(joinPoint), step, result);
    }

    @AfterThrowing(
            pointcut = "execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.StepExtension+.run())",
            throwing = "error"
    )
    public void failedStep(JoinPoint joinPoint, Throwable error) {
        StepExtension step = (StepExtension) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_STEP, signature(joinPoint), step, error);
    }

    @Around("execution(public Object io.cucumber.core.runner.StepExtension+.runAndGetReturnValue())")
    public Object runStepForValue(ProceedingJoinPoint joinPoint) throws Throwable {
        StepExtension step = (StepExtension) joinPoint.getThis();
        if (ControlRuntime.fire(
                ControlHook.BEFORE_STEP,
                signature(joinPoint),
                step
        ).skip()) {
            step.skipped = true;
            return null;
        }
        return joinPoint.proceed();
    }

    @AfterReturning(
            pointcut = "execution(public Object io.cucumber.core.runner.StepExtension+.runAndGetReturnValue())",
            returning = "value"
    )
    public void afterStepValue(JoinPoint joinPoint, Object value) {
        StepExtension step = (StepExtension) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_STEP, signature(joinPoint), step, value);
    }

    @AfterThrowing(
            pointcut = "execution(public Object io.cucumber.core.runner.StepExtension+.runAndGetReturnValue())",
            throwing = "error"
    )
    public void failedStepValue(JoinPoint joinPoint, Throwable error) {
        StepExtension step = (StepExtension) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_STEP, signature(joinPoint), step, error);
    }

    @Around("execution(public void tools.dscode.common.treeparsing.parsedComponents.Phrase.executePhrase())")
    public Object executePhrase(ProceedingJoinPoint joinPoint) throws Throwable {
        Phrase phrase = (Phrase) joinPoint.getThis();
        if (ControlRuntime.fire(
                ControlHook.BEFORE_PHRASE,
                signature(joinPoint),
                phrase
        ).skip()) {
            phrase.wasPhraseSkipped = true;
            phrase.assertionChain = null;
            return null;
        }
        return joinPoint.proceed();
    }

    @AfterReturning("execution(public void tools.dscode.common.treeparsing.parsedComponents.Phrase.executePhrase())")
    public void afterPhrase(JoinPoint joinPoint) {
        Phrase phrase = (Phrase) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_PHRASE, signature(joinPoint), phrase);
    }

    @AfterThrowing(
            pointcut = "execution(public void tools.dscode.common.treeparsing.parsedComponents.Phrase.executePhrase())",
            throwing = "error"
    )
    public void failedPhrase(JoinPoint joinPoint, Throwable error) {
        Phrase phrase = (Phrase) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_PHRASE, signature(joinPoint), phrase, error);
    }

    @Around("execution(public void tools.dscode.common.treeparsing.parsedComponents.PhraseData.syncWithDOM())")
    public Object syncWithDom(ProceedingJoinPoint joinPoint) throws Throwable {
        PhraseData phrase = (PhraseData) joinPoint.getThis();
        if (ControlRuntime.fire(
                ControlHook.BEFORE_DOM_SYNC,
                signature(joinPoint),
                phrase
        ).skip()) {
            return null;
        }
        return joinPoint.proceed();
    }

    @AfterReturning("execution(public void tools.dscode.common.treeparsing.parsedComponents.PhraseData.syncWithDOM())")
    public void afterDomSync(JoinPoint joinPoint) {
        PhraseData phrase = (PhraseData) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_DOM_SYNC, signature(joinPoint), phrase);
    }

    @AfterThrowing(
            pointcut = "execution(public void tools.dscode.common.treeparsing.parsedComponents.PhraseData.syncWithDOM())",
            throwing = "error"
    )
    public void failedDomSync(JoinPoint joinPoint, Throwable error) {
        PhraseData phrase = (PhraseData) joinPoint.getThis();
        ControlRuntime.fire(ControlHook.AFTER_DOM_SYNC, signature(joinPoint), phrase, error);
    }

    @Around("execution(public static void tools.dscode.common.domoperations.HumanInteractions.blur(..))")
    public Object blur(ProceedingJoinPoint joinPoint) throws Throwable {
        if (ControlRuntime.fire(
                ControlHook.BEFORE_DOM_BLUR,
                signature(joinPoint),
                null,
                joinPoint.getArgs()
        ).skip()) {
            return null;
        }
        return joinPoint.proceed();
    }

    @AfterReturning("execution(public static void tools.dscode.common.domoperations.HumanInteractions.blur(..))")
    public void afterBlur(JoinPoint joinPoint) {
        ControlRuntime.fire(
                ControlHook.AFTER_DOM_BLUR,
                signature(joinPoint),
                null,
                joinPoint.getArgs()
        );
    }

    @Around("execution(public static boolean tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady(org.openqa.selenium.WebDriver, java.time.Duration))")
    public Object waitForPageReady(ProceedingJoinPoint joinPoint) throws Throwable {
        if (ControlRuntime.fire(
                ControlHook.BEFORE_PAGE_READY,
                signature(joinPoint),
                null,
                joinPoint.getArgs()
        ).skip()) {
            return true;
        }
        return joinPoint.proceed();
    }

    @AfterReturning(
            pointcut = "execution(public static boolean tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady(org.openqa.selenium.WebDriver, java.time.Duration))",
            returning = "ready"
    )
    public void afterPageReady(JoinPoint joinPoint, boolean ready) {
        ControlRuntime.fire(
                ControlHook.AFTER_PAGE_READY,
                signature(joinPoint),
                null,
                ready
        );
    }

    @Around("execution(public static void tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities(tools.dscode.common.treeparsing.parsedComponents.PhraseData))")
    public Object waitForPhraseEntities(ProceedingJoinPoint joinPoint) throws Throwable {
        PhraseData phrase = (PhraseData) joinPoint.getArgs()[0];
        if (ControlRuntime.fire(
                ControlHook.BEFORE_ENTITY_READY,
                signature(joinPoint),
                phrase
        ).skip()) {
            return null;
        }
        return joinPoint.proceed();
    }

    @AfterReturning("execution(public static void tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities(tools.dscode.common.treeparsing.parsedComponents.PhraseData))")
    public void afterPhraseEntities(JoinPoint joinPoint) {
        PhraseData phrase = (PhraseData) joinPoint.getArgs()[0];
        ControlRuntime.fire(ControlHook.AFTER_ENTITY_READY, signature(joinPoint), phrase);
    }

    @Around("call(public static void tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds(long))"
            + " && (withincode(public void tools.dscode.common.treeparsing.parsedComponents.PhraseData.syncWithDOM())"
            + " || withincode(public void tools.dscode.common.treeparsing.parsedComponents.PhraseData.runOperation())"
            + " || withincode(public void tools.dscode.common.treeparsing.parsedComponents.PhraseData.runUntilOperation())"
            + " || withincode(public static boolean tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady(..)))")
    public Object fixedWait(ProceedingJoinPoint joinPoint) throws Throwable {
        long milliseconds = ((Number) joinPoint.getArgs()[0]).longValue();
        boolean skip = ControlRuntime.fire(
                ControlHook.BEFORE_FIXED_WAIT,
                signature(joinPoint),
                null,
                milliseconds,
                enclosingSignature(joinPoint)
        ).skip();
        Object result = skip ? null : joinPoint.proceed();
        ControlRuntime.fire(
                ControlHook.AFTER_FIXED_WAIT,
                signature(joinPoint),
                null,
                milliseconds,
                skip
        );
        return result;
    }

    @Before("call(* org.openqa.selenium.WebElement+.*(..)) && within(tools.dscode..*)")
    public void beforeDomAccess(JoinPoint joinPoint) {
        if (ControlRuntime.hasHandler()) {
            ControlRuntime.fire(
                    ControlHook.BEFORE_DOM_ACCESS,
                    signature(joinPoint),
                    joinPoint.getTarget(),
                    joinPoint.getArgs()
            );
        }
    }

    @AfterReturning("call(* org.openqa.selenium.WebElement+.*(..)) && within(tools.dscode..*)")
    public void afterDomAccess(JoinPoint joinPoint) {
        if (ControlRuntime.hasHandler()) {
            ControlRuntime.fire(
                    ControlHook.AFTER_DOM_ACCESS,
                    signature(joinPoint),
                    joinPoint.getTarget(),
                    joinPoint.getArgs()
            );
        }
    }

    @AfterThrowing(
            pointcut = "call(* org.openqa.selenium.WebElement+.*(..)) && within(tools.dscode..*)",
            throwing = "error"
    )
    public void failedDomAccess(JoinPoint joinPoint, Throwable error) {
        if (ControlRuntime.hasHandler()) {
            ControlRuntime.fire(
                    ControlHook.AFTER_DOM_ACCESS,
                    signature(joinPoint),
                    joinPoint.getTarget(),
                    error
            );
        }
    }

    @Before("call(* org.openqa.selenium.WebDriver+.*(..)) && within(tools.dscode..*)")
    public void beforeDriverCommand(JoinPoint joinPoint) {
        if (ControlRuntime.hasHandler()) {
            ControlRuntime.fire(
                    ControlHook.BEFORE_DRIVER_COMMAND,
                    signature(joinPoint),
                    joinPoint.getTarget(),
                    joinPoint.getArgs()
            );
        }
    }

    @AfterReturning("call(* org.openqa.selenium.WebDriver+.*(..)) && within(tools.dscode..*)")
    public void afterDriverCommand(JoinPoint joinPoint) {
        if (ControlRuntime.hasHandler()) {
            ControlRuntime.fire(
                    ControlHook.AFTER_DRIVER_COMMAND,
                    signature(joinPoint),
                    joinPoint.getTarget(),
                    joinPoint.getArgs()
            );
        }
    }

    @AfterThrowing(
            pointcut = "call(* org.openqa.selenium.WebDriver+.*(..)) && within(tools.dscode..*)",
            throwing = "error"
    )
    public void failedDriverCommand(JoinPoint joinPoint, Throwable error) {
        if (ControlRuntime.hasHandler()) {
            ControlRuntime.fire(
                    ControlHook.AFTER_DRIVER_COMMAND,
                    signature(joinPoint),
                    joinPoint.getTarget(),
                    error
            );
        }
    }

    @Before("execution(public static * tools.dscode.common.domoperations.HumanInteractions.*(..))"
            + " && !execution(public static void tools.dscode.common.domoperations.HumanInteractions.blur(..))")
    public void beforeBrowserInteraction(JoinPoint joinPoint) {
        ControlRuntime.fire(
                ControlHook.BEFORE_BROWSER_INTERACTION,
                signature(joinPoint),
                joinPoint.getThis(),
                joinPoint.getArgs()
        );
    }

    @AfterReturning("execution(public static * tools.dscode.common.domoperations.HumanInteractions.*(..))"
            + " && !execution(public static void tools.dscode.common.domoperations.HumanInteractions.blur(..))")
    public void afterBrowserInteraction(JoinPoint joinPoint) {
        ControlRuntime.fire(
                ControlHook.AFTER_BROWSER_INTERACTION,
                signature(joinPoint),
                joinPoint.getThis(),
                joinPoint.getArgs()
        );
    }

    @AfterThrowing(
            pointcut = "execution(public static * tools.dscode.common.domoperations.HumanInteractions.*(..))"
                    + " && !execution(public static void tools.dscode.common.domoperations.HumanInteractions.blur(..))",
            throwing = "error"
    )
    public void failedBrowserInteraction(JoinPoint joinPoint, Throwable error) {
        ControlRuntime.fire(
                ControlHook.AFTER_BROWSER_INTERACTION,
                signature(joinPoint),
                joinPoint.getThis(),
                error
        );
    }

    @Before(
            "execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.executeServiceCall())"
                    + " || execution(public static Object tools.dscode.coredefinitions.ServiceCallSteps.inlineCall(..))"
                    + " || execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.serviceCalls(..))"
    )
    public void beforeServiceCall(JoinPoint joinPoint) {
        ControlRuntime.fire(
                ControlHook.BEFORE_SERVICE_CALL,
                signature(joinPoint),
                joinPoint.getThis(),
                joinPoint.getArgs()
        );
    }

    @AfterReturning(
            "execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.executeServiceCall())"
                    + " || execution(public static Object tools.dscode.coredefinitions.ServiceCallSteps.inlineCall(..))"
                    + " || execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.serviceCalls(..))"
    )
    public void afterServiceCall(JoinPoint joinPoint) {
        ControlRuntime.fire(
                ControlHook.AFTER_SERVICE_CALL,
                signature(joinPoint),
                joinPoint.getThis(),
                joinPoint.getArgs()
        );
    }

    @AfterThrowing(
            pointcut = "execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.executeServiceCall())"
                    + " || execution(public static Object tools.dscode.coredefinitions.ServiceCallSteps.inlineCall(..))"
                    + " || execution(public static void tools.dscode.coredefinitions.ServiceCallSteps.serviceCalls(..))",
            throwing = "error"
    )
    public void failedServiceCall(JoinPoint joinPoint, Throwable error) {
        ControlRuntime.fire(
                ControlHook.AFTER_SERVICE_CALL,
                signature(joinPoint),
                joinPoint.getThis(),
                error
        );
    }

    @Before("execution(public static org.openqa.selenium.remote.RemoteWebDriver tools.dscode.common.driver.DriverConstruction.create*(..))")
    public void beforeRemoteDriverAccess(JoinPoint joinPoint) {
        ControlRuntime.fire(
                ControlHook.BEFORE_REMOTE_DRIVER_ACCESS,
                signature(joinPoint),
                null,
                joinPoint.getArgs()
        );
    }

    @AfterReturning(
            pointcut = "execution(public static org.openqa.selenium.remote.RemoteWebDriver tools.dscode.common.driver.DriverConstruction.create*(..))",
            returning = "driver"
    )
    public void afterRemoteDriverAccess(JoinPoint joinPoint, Object driver) {
        ControlRuntime.fire(
                ControlHook.AFTER_REMOTE_DRIVER_ACCESS,
                signature(joinPoint),
                driver
        );
    }

    @AfterThrowing(
            pointcut = "execution(public static org.openqa.selenium.remote.RemoteWebDriver tools.dscode.common.driver.DriverConstruction.create*(..))",
            throwing = "error"
    )
    public void failedRemoteDriverAccess(JoinPoint joinPoint, Throwable error) {
        ControlRuntime.fire(
                ControlHook.AFTER_REMOTE_DRIVER_ACCESS,
                signature(joinPoint),
                null,
                error
        );
    }

    @Around("execution(public String tools.dscode.common.mappings.ParsingMap.resolveWholeText(String, boolean, ..))")
    public Object resolveWholeText(ProceedingJoinPoint joinPoint) throws Throwable {
        ParsingMap parsingMap = (ParsingMap) joinPoint.getThis();
        Object[] args = joinPoint.getArgs();
        String input = (String) args[0];
        boolean resolveEvaluations = (Boolean) args[1];
        if (!ControlRuntime.hasHandler()) {
            return joinPoint.proceed();
        }
        String signature = signature(joinPoint);
        if (ControlRuntime.fire(
                ControlHook.BEFORE_MAPPING_RESOLVE,
                signature,
                parsingMap,
                input,
                resolveEvaluations,
                "TEXT"
        ).skip()) {
            return input;
        }
        Object transformedInput = ControlRuntime.transform(
                ControlHook.BEFORE_MAPPING_RESOLVE,
                "input",
                signature,
                parsingMap,
                input,
                resolveEvaluations,
                "TEXT"
        );
        String effectiveInput = transformedInput instanceof String
                ? (String) transformedInput
                : input;
        args[0] = effectiveInput;
        String resolved = (String) joinPoint.proceed(args);
        Object transformedResult = ControlRuntime.transform(
                ControlHook.AFTER_MAPPING_RESOLVE,
                "result",
                signature,
                parsingMap,
                resolved,
                input,
                effectiveInput,
                resolveEvaluations,
                "TEXT"
        );
        String result = transformedResult == null
                ? null
                : String.valueOf(transformedResult);
        ControlRuntime.fire(
                ControlHook.AFTER_MAPPING_RESOLVE,
                signature,
                parsingMap,
                input,
                effectiveInput,
                result,
                "TEXT"
        );
        return result;
    }

    @Around("execution(public Object tools.dscode.common.mappings.ParsingMap.resolveWholeValue(String, boolean, ..))")
    public Object resolveWholeValue(ProceedingJoinPoint joinPoint) throws Throwable {
        ParsingMap parsingMap = (ParsingMap) joinPoint.getThis();
        Object[] args = joinPoint.getArgs();
        String input = (String) args[0];
        boolean resolveEvaluations = (Boolean) args[1];
        if (!ControlRuntime.hasHandler()) {
            return joinPoint.proceed();
        }
        String signature = signature(joinPoint);
        if (ControlRuntime.fire(
                ControlHook.BEFORE_MAPPING_RESOLVE,
                signature,
                parsingMap,
                input,
                resolveEvaluations,
                "VALUE"
        ).skip()) {
            return input;
        }
        Object transformedInput = ControlRuntime.transform(
                ControlHook.BEFORE_MAPPING_RESOLVE,
                "input",
                signature,
                parsingMap,
                input,
                resolveEvaluations,
                "VALUE"
        );
        String effectiveInput = transformedInput instanceof String
                ? (String) transformedInput
                : input;
        args[0] = effectiveInput;
        Object resolved = joinPoint.proceed(args);
        Object result = ControlRuntime.transform(
                ControlHook.AFTER_MAPPING_RESOLVE,
                "result",
                signature,
                parsingMap,
                resolved,
                input,
                effectiveInput,
                resolveEvaluations,
                "VALUE"
        );
        ControlRuntime.fire(
                ControlHook.AFTER_MAPPING_RESOLVE,
                signature,
                parsingMap,
                input,
                effectiveInput,
                result,
                "VALUE"
        );
        return result;
    }

    @Around("execution(public Object tools.dscode.common.mappings.MappingProcessor.get(String))")
    public Object lookup(ProceedingJoinPoint joinPoint) throws Throwable {
        return lookup(joinPoint, false);
    }

    @Around("execution(public Object tools.dscode.common.mappings.MappingProcessor.getCaseInsensitive(String))")
    public Object lookupCaseInsensitive(ProceedingJoinPoint joinPoint) throws Throwable {
        return lookup(joinPoint, true);
    }

    @Around("execution(public void tools.dscode.common.mappings.NodeMap.put(String, Object))")
    public Object write(ProceedingJoinPoint joinPoint) throws Throwable {
        return write(joinPoint, false);
    }

    @Around("execution(public void tools.dscode.common.mappings.NodeMap.putAsSingleton(String, Object))")
    public Object writeSingleton(ProceedingJoinPoint joinPoint) throws Throwable {
        return write(joinPoint, true);
    }

    private Object lookup(ProceedingJoinPoint joinPoint, boolean caseInsensitive) throws Throwable {
        MappingProcessor processor = (MappingProcessor) joinPoint.getThis();
        String key = (String) joinPoint.getArgs()[0];
        if (!ControlRuntime.hasHandler()) {
            return joinPoint.proceed();
        }
        String signature = signature(joinPoint);
        if (ControlRuntime.fire(
                ControlHook.BEFORE_MAPPING_LOOKUP,
                signature,
                processor,
                key,
                caseInsensitive
        ).skip()) {
            return null;
        }
        Object transformedKey = ControlRuntime.transform(
                ControlHook.BEFORE_MAPPING_LOOKUP,
                "key",
                signature,
                processor,
                key,
                caseInsensitive
        );
        String effectiveKey = transformedKey instanceof String
                ? (String) transformedKey
                : key;
        Object result = joinPoint.proceed(new Object[]{effectiveKey});
        result = ControlRuntime.transform(
                ControlHook.AFTER_MAPPING_LOOKUP,
                "result",
                signature,
                processor,
                result,
                key,
                effectiveKey,
                caseInsensitive
        );
        ControlRuntime.fire(
                ControlHook.AFTER_MAPPING_LOOKUP,
                signature,
                processor,
                key,
                effectiveKey,
                result,
                caseInsensitive
        );
        return result;
    }

    private Object write(ProceedingJoinPoint joinPoint, boolean singleton) throws Throwable {
        NodeMap nodeMap = (NodeMap) joinPoint.getThis();
        Object[] args = joinPoint.getArgs();
        String key = (String) args[0];
        Object value = args[1];
        if (!ControlRuntime.hasHandler()) {
            return joinPoint.proceed();
        }
        String signature = signature(joinPoint);
        if (ControlRuntime.fire(
                ControlHook.BEFORE_MAPPING_WRITE,
                signature,
                nodeMap,
                key,
                value,
                singleton
        ).skip()) {
            return null;
        }
        Object transformedKey = ControlRuntime.transform(
                ControlHook.BEFORE_MAPPING_WRITE,
                "key",
                signature,
                nodeMap,
                key,
                value,
                singleton
        );
        String effectiveKey = transformedKey instanceof String
                ? (String) transformedKey
                : key;
        Object effectiveValue = ControlRuntime.transform(
                ControlHook.BEFORE_MAPPING_WRITE,
                "value",
                signature,
                nodeMap,
                value,
                key,
                effectiveKey,
                singleton
        );
        Object result = joinPoint.proceed(new Object[]{effectiveKey, effectiveValue});
        ControlRuntime.fire(
                ControlHook.AFTER_MAPPING_WRITE,
                signature,
                nodeMap,
                key,
                effectiveKey,
                effectiveValue,
                singleton
        );
        return result;
    }

    private static String signature(JoinPoint joinPoint) {
        return joinPoint.getSignature().toLongString();
    }

    private static String enclosingSignature(JoinPoint joinPoint) {
        JoinPoint.StaticPart enclosing = joinPoint.getStaticPart();
        return enclosing == null ? "" : enclosing.getSignature().toLongString();
    }
}
