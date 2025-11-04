// File: PickleStep_NestingLevel.aj
package io.cucumber.messages.types;

public aspect PickleStep_NestingLevel {

    /** Add nestingLevel field to PickleStep */
    public int io.cucumber.messages.types.PickleStep.nestingLevel = 0;

    /** Intercept getText() and prefix based on nestingLevel */
    pointcut getTextExec(io.cucumber.messages.types.PickleStep self) :
            execution(public String io.cucumber.messages.types.PickleStep.getText())
                    && this(self);

    String around(io.cucumber.messages.types.PickleStep self) : getTextExec(self) {
        String base = proceed(self);
        int n = self.nestingLevel;
        return (n > 0 ? " : ".repeat(n) : "") + base;
    }
}
