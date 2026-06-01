package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.messages.types.Feature;

import java.util.Optional;

import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class MessageUtilities {

     public static String getFeatureName(Pickle pickle){
         try {
             if (pickle instanceof GherkinMessagesPickle gherkinMessagesPickle) {
                 io.cucumber.messages.types.Pickle typePickle = (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");
                 Optional<Feature> featureOptional = (Optional<Feature>) invokeAnyMethod(getProperty(gherkinMessagesPickle, "cucumberQuery"), "findFeatureBy", typePickle);
                 if (featureOptional.isPresent())
                     return featureOptional.get().getName();
             }
             return null;
         }
         catch (Exception e){
             return null;
         }
     }
}
