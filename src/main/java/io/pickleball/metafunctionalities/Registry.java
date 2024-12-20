//package io.pickleball.metafunctionalities;
//
//import io.cucumber.core.stepexpression.StepTypeRegistry;
//import io.cucumber.cucumberexpressions.ParameterType; // For defining custom parameter types
//import io.pickleball.customtypes.Coordinates;
//
//import java.util.Locale;
//
//public class Registry {
//    private final StepTypeRegistry stepTypeRegistry = new StepTypeRegistry(Locale.ENGLISH);
//
//    public Registry() {
//        stepTypeRegistry.defineParameterType(coordinatesParameterType);
//    }
//
//    private final ParameterType<Coordinates> coordinatesParameterType = new ParameterType<>(
//            "coordinates",                  // Name of the parameter type
//            "\\((\\d+),(\\d+)\\)",          // Regex to match "(x,y)"
//            Coordinates.class,              // Target type (custom class)
//            (String[] args) -> new Coordinates(  // Transformation logic
//                    Integer.parseInt(args[0]),
//                    Integer.parseInt(args[1])
//            )
//    );
//}
