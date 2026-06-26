package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;

public aspect RunnerRunPickleShouldRunAspect {

    pointcut runPickle(Pickle pickle):
            execution(public void io.cucumber.core.runner.Runner.runPickle(io.cucumber.core.gherkin.Pickle))
                    && args(pickle);

    void around(Pickle pickle)
            : runPickle(pickle) {
        if (pickle != null && !pickle.isShouldRun()) {
            return;
        }
        proceed(pickle);
    }
}
