//// File: src/main/aspectj/io/cucumber/core/gherkin/messages/GherkinMessagesStep_NestingLevel.aj
//package io.cucumber.core.gherkin.messages;
//
///**
// * Adds a public 'nestingLevel' field to GherkinMessagesStep and
// * prefixes the returned text with " : " repeated nestingLevel times.
// */
//public aspect GherkinMessagesStep_NestingLevel {
//
//    /** Inter-type field on the GherkinMessagesStep class. Default 0. */
//    public int io.cucumber.core.gherkin.messages.GherkinMessagesStep.nestingLevel = 0;
//
//    /** Pointcut for the getText() execution we want to wrap. */
//    pointcut getTextExec(io.cucumber.core.gherkin.messages.GherkinMessagesStep self) :
//            execution(public String io.cucumber.core.gherkin.messages.GherkinMessagesStep.getText())
//                    && this(self);
//
//    /** Around advice to add prefix based on nestingLevel. */
//    String around(io.cucumber.core.gherkin.messages.GherkinMessagesStep self) : getTextExec(self) {
//        String base = proceed(self);


//        return "QQ" + base + "ZZ" + self.nestingLevel;
////        if (base == null) base = "";
////
////        int n = self.nestingLevel;
////        if (n <= 0) {
////            return base;
////        }
////
////        // Build prefix " : " repeated n times
////        StringBuilder prefix = new StringBuilder(n * 3);
////        for (int i = 0; i < n; i++) {
////            prefix.append(" : ");
////        }
////
////        return prefix.append(base).toString();
//    }
//}
