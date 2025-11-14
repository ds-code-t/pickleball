//// File: src/main/aspectj/io/cucumber/java/CustomStepAnnotationAspect.aj
//package io.cucumber.java;
//
//import java.lang.annotation.Annotation;
//
//public aspect CustomStepAnnotationAspect {
//
//    // Fully-qualified name of your custom annotation
//    private static final String RETURN_STEP_FQN = "io.cucumber.java.en.ReturnStep";
//
//    // Intercept the private static isStepDefinitionAnnotation(Annotation) method
//    pointcut stepDefCheck(Annotation ann) :
//            execution(private static boolean io.cucumber.java.MethodScanner.isStepDefinitionAnnotation(Annotation))
//                    && args(ann);
//
//    // Extend the original logic: StepDefinitionAnnotation OR custom annotation
//    boolean around(Annotation ann) : stepDefCheck(ann) {
//        // Original behavior: check @StepDefinitionAnnotation meta-annotation
//        boolean result = proceed(ann);
//        if (result) {
//            return true;
//        }
//
//        // Custom behavior: treat specific custom annotations as step defs
//        Class<? extends Annotation> annotationClass = ann.annotationType();
//        String fqn = annotationClass.getName();
//
//        if (RETURN_STEP_FQN.equals(fqn)) {
//            return true;
//        }
//
//        return false;
//    }
//}
