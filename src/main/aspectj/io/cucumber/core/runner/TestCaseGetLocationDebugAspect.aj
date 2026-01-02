//package io.cucumber.core.runner;
//
//
//import static io.cucumber.core.runner.modularexecutions.FilePathResolver.fixFeatureLocationForIntellij;
//
///**
// * Intercepts io.cucumber.core.runner.TestCase#getLocation()
// * and debug-prints the associated URI before/after IntelliJ normalization.
// *
// * NOTE:
// * - getLocation() returns line/column only
// * - We do NOT modify the returned Location
// * - We only normalize the URI string for debug visibility
// */
//public aspect TestCaseGetLocationDebugAspect {
//
//io.cucumber.plugin.event.Location around() :
//execution(io.cucumber.plugin.event.Location io.cucumber.core.runner.TestCase.getLocation()) {
//
//io.cucumber.plugin.event.Location original = proceed();
//

//
//        if (original == null) {

//            return null;
//                    }
//
//                    try {
//io.cucumber.core.runner.TestCase tc =
//        (io.cucumber.core.runner.TestCase) thisJoinPoint.getThis();
//
//java.net.URI uri = tc.getUri();
//String uriStr = String.valueOf(uri);
//

//
//String fixed = fixFeatureLocationForIntellij(uriStr);
//
//            if (!uriStr.equals(fixed)) {

//            }
//                    } catch (Throwable t) {

//            t.printStackTrace(System.out);
//        }
//

//        return original;
//    }
//            }
