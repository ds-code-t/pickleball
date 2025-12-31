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
//        System.out.println("@@TestCase.getLocation: original Location = " + original);
//
//        if (original == null) {
//        System.out.println("@@TestCase.getLocation: original is null -> returning null");
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
//            System.out.println("@@TestCase.getLocation: tc.getUri() original = " + uriStr);
//
//String fixed = fixFeatureLocationForIntellij(uriStr);
//
//            if (!uriStr.equals(fixed)) {
//        System.out.println("@@TestCase.getLocation: tc.getUri() fixed    = " + fixed);
//            }
//                    } catch (Throwable t) {
//        System.out.println("@@TestCase.getLocation: FAILED to print related URI");
//            t.printStackTrace(System.out);
//        }
//
//                System.out.println("@@TestCase.getLocation: returning Location = " + original);
//        return original;
//    }
//            }
