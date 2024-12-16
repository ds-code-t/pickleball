//package io.cucumber.utilities;
//
//import io.cucumber.core.backend.StepDefinition;
////import io.cucumber.core.backend.StubStepDefinition;
//import io.cucumber.core.gherkin.Feature;
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.core.stepexpression.DataTableArgument;
//import io.cucumber.core.stepexpression.RawTableTransformer;
//import io.cucumber.core.stepexpression.StepExpressionFactory;
//import io.cucumber.core.stepexpression.StepTypeRegistry;
//import io.cucumber.datatable.DataTable;
//import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
//
//import java.lang.reflect.Type;
//import java.util.List;
//
//import static java.util.Locale.ENGLISH;
//
//public class GherkinUtilities {
//    public static Feature feature;
//    public static Pickle pickle;
//    public static Step step;
//    public  static StepDefinition stepDefinition;
//
////    static {
////        feature = io.cucumber.core.feature.TestFeatureParser.parse("" +
////                "Feature: Test feature\n" +
////                "  Scenario: Test scenario\n" +
////                "     Given I have 4 cukes in my belly\n");
////        pickle = feature.getPickles().get(0);
////        step = pickle.getSteps().get(0);
//////        stepDefinition = new StubStepDefinition("I have {int} cukes in my belly", Integer.class);
////    }
//    public static final StepTypeRegistry englishStepTypeRegistry = new StepTypeRegistry(ENGLISH);
//    public static final DataTableTypeRegistryTableConverter tableConverter = new DataTableTypeRegistryTableConverter(englishStepTypeRegistry.dataTableTypeRegistry());
//
//    RawTableTransformer<?> tableTransform = (List<List<String>> raw) -> {
//        DataTable dataTable = DataTable.create(raw, StepExpressionFactory.this.tableConverter);
//        Type targetType = tableOrDocStringType.get();
//        return dataTable.convert(Object.class.equals(targetType) ? DataTable.class : targetType, transpose);
//    };
//
//    public static final DataTableArgument dataTableArgument = new DataTableArgument();
//    public static DataTableArgument getDataTable(){
//
//        RawTableTransformer<?> tableTransform = (List<List<String>> raw) -> {
//            DataTable dataTable = DataTable.create(raw, tableConverter);
//            Type targetType = tableOrDocStringType.get();
//            return dataTable.convert(Object.class.equals(targetType) ? DataTable.class : targetType, transpose);
//        };
//    }
//}
