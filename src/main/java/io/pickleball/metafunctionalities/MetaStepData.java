package io.pickleball.metafunctionalities;

import java.util.HashMap;

public class MetaStepData extends HashMap<String, Object> {
    public final String methodName;
    public final String className;

    MetaStepData() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stackTrace[2];
        this.methodName = caller.getMethodName();
        this.className = caller.getClassName();
        System.out.println("Called by method: " + methodName + " in class: " + className);
    }

    public boolean checkMethodMatch(String methodRefName) {
        if (methodRefName.contains("."))
            return (className + "." + methodName).equals(methodRefName);
        return methodRefName.equals(methodName);
    }

    public MetaStepData set(String key, Object value) {
        this.put(key, value);
        return this; // Enable chaining
    }

}
