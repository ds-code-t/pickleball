//package io.pickleball.runner;
//
//import io.cucumber.testng.CucumberPropertiesProvider;
//import io.cucumber.testng.TestNGCucumberRunner;
//
//public class CustomTestNGCucumberRunner extends TestNGCucumberRunner {
//
//    public CustomTestNGCucumberRunner(Class<?> clazz, CucumberPropertiesProvider properties) {
//        super(clazz, properties);
//        initializeCustomRuntime();
//    }
//
//    private void initializeCustomRuntime() {
//        Runtime runtime = Runtime.builder().build();
//        Runtime.setGlobalRuntime(runtime); // Set globalRuntime here
//        System.out.println("Custom Runtime Initialized!");
//    }
//}
