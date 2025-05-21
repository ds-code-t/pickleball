package io.pickleball.datafunctions;

import com.googlecode.aviator.AviatorEvaluator;
import io.pickleball.valueresolution.ValueChecker;

import java.util.*;
import java.util.function.*;

import static io.pickleball.datafunctions.EvalList.CheckMode.*;


public class EvalList extends ArrayList<ValWrapper> implements ValueChecker {

    public ArrayList<?> mapToArrayList() {
        return new ArrayList<>(this.stream()
                .map(ValWrapper::getValue)
                .toList());
    }

    public ArrayList<?> mapToStringArrayList() {
        forEach(s -> {
            System.out.println("\n--------\n@s:  " + s);
            System.out.println("@s.class:  " + s.getClass());

            System.out.println("@s.getInitialVal():  " + s.getInitialVal());
            System.out.println("@s.getInitialVal().class:  " + s.getInitialVal().getClass());


            System.out.println("@s.getString():  " + s.getString());
            System.out.println("@s.getString().class:  " + s.getString().getClass());



        });
        return new ArrayList<>(this.stream()
                .map(ValWrapper::getString)
                .toList());
    }

    public enum CheckMode {
        ANY,
        NONE,
        ALL,
        NOT,
        IS,
        HAS,
        VALUE,
        BOOLEAN,
//        EQUAL
    }

    List<CheckMode> matches;
    public void matchCheckModes(String input) {
        matches = new ArrayList<>();
        if (input == null) return;
        for (CheckMode mode : CheckMode.values()) {
            if (input.contains(mode.name())) {
                matches.add(mode);
            }
        }
        setPredicate();
    }

    private Predicate<ValWrapper> currentPredicate;

    private void setPredicate() {
        if (matches.contains(CheckMode.VALUE)) {
            currentPredicate = ValWrapper::hasValue;
//        } else if (matches.contains(CheckMode.EQUAL)) {
//            currentPredicate = ValWrapper::isEqual;
        } else {
            currentPredicate = ValWrapper::getBoolValue;
        }
    }

    // Generic method to accept any type
    public boolean addObject(Object element) {
        return add(ValWrapper.wrapVal(element));
    }


    private EvalList(List<?> list) {
        super(); // Initialize the ArrayList
        if (list != null) {
            list.stream()
                    .map(ValWrapper::wrapVal)    // Convert each element to ValWrapper
                    .forEach(this::add);     // Add to this EvalList
        }
    }

    @Override
    public boolean getBoolValue() {
        if(matches==null)
            return this.stream().anyMatch(ValWrapper::getBoolValue);

        if(matches.contains(NONE)){
            return this.stream().noneMatch(currentPredicate);
        } else  if(matches.contains(ALL)){
            return this.stream().allMatch(currentPredicate);
        }
        else {
            return this.stream().anyMatch(currentPredicate);
        }
    }



    public static EvalList wrapListItems(List list) {
        return new EvalList(list);
    }

    public static EvalList createEvalList(Object... objects) {
        return new EvalList(Arrays.stream(objects).toList());
    }


    @Override
    public String toString() {
        return "seq.list(" + String.join(", ",
                this.stream()
                        .map(Object::toString)
                        .toList()) +
                ")";
    }


}