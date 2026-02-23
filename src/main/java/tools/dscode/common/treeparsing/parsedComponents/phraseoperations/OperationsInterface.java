package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;


import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.*;
import java.util.stream.*;

import java.util.stream.*;

public interface OperationsInterface<E extends Enum<E> & OperationsInterface<E>> {

//    default   OperationsInterface fromString(String input) {
//        return this.requireOperationEnum(input);
//    }

    public abstract void execute(PhraseData phraseData);


    public static <E extends Enum<E> & OperationsInterface> E requireOperationEnum(Class<E> enumClass, String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Operation text was '" + input + "'");
        }

        String normalized = input.trim()
                .replaceFirst("non|un", "")
                .replaceFirst("disabled", "enabled")
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "_");

        E[] constants = enumClass.getEnumConstants();

        return Arrays.stream(constants)
                .sorted(Comparator.comparingInt((E e) -> e.name().length()).reversed()) // longest first
                .filter(e -> normalized.contains(e.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No " + enumClass.getSimpleName() + " matched text: '" + input + "' " +
                                "(normalized: '" + normalized + "'). Expected one of: " +
                                Arrays.toString(constants)
                ));
    }

}

