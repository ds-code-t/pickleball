//package io.cucumber.core.gherkin.messages;
//
//import java.net.URI;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//
///**
// * Intercepts GherkinMessagesPickle#getUri() and normalizes Windows file URIs
// * so IntelliJ is more likely to recognize them as clickable (file:///C:/...).
// *
// * This does NOT change non-file URIs.
// */
//public aspect GherkinMessagesPickleGetUriAspect {
//
//    URI around() :
//            execution(java.net.URI io.cucumber.core.gherkin.messages.GherkinMessagesPickle.getUri()) {
//
//        URI original = proceed();
//
//        System.out.println("@@GherkinMessagesPickle.getUri: original = " + original);
//
//        if (original == null) {
//            System.out.println("@@GherkinMessagesPickle.getUri: original is null -> returning null");
//            return null;
//        }
//
//        String s = original.toString();
//        System.out.println("@@GherkinMessagesPickle.getUri: original.toString() = " + s);
//
//        // Only touch file URIs that look like they may have encoded spaces
//        if (s.startsWith("file:") && s.contains("%20")) {
//            try {
//                Path p = Paths.get(original);     // decodes %20 -> space in the Path
//                URI fixed = p.toUri();            // produces canonical file:///C:/... on Windows
//
//                System.out.println("@@GherkinMessagesPickle.getUri: Paths.get(uri) = " + p);
//                System.out.println("@@GherkinMessagesPickle.getUri: fixed = " + fixed);
//
//                return fixed;
//            } catch (Exception e) {
//                System.out.println("@@GherkinMessagesPickle.getUri: FIX FAILED, returning original. Error:");
//                e.printStackTrace(System.out);
//            }
//        } else {
//            System.out.println("@@GherkinMessagesPickle.getUri: no change (not file: or no %20)");
//        }
//
//        return original;
//    }
//}
