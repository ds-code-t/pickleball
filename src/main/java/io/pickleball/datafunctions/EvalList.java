package io.pickleball.datafunctions;

import io.pickleball.valueresolution.ValueChecker;

import java.util.*;
import java.util.function.*;


public class EvalList extends ArrayList<ValWrapper> implements ValueChecker {
    private CheckMode checkMode = CheckMode.ANY;
    private Predicate<ValWrapper> currentPredicate = ValWrapper::getBoolValue;

    public enum CheckMode {
        ANY,
        NONE,
        ALL
    }

    // Generic method to accept any type
    public boolean addObject(Object element) {
        System.out.println("@@EvalList - addObject: " + element);
        return add(ValWrapper.wrapVal(element));
    }


    private EvalList(List<?> list) {
        super(); // Initialize the ArrayList
        System.out.println("@@EvalList - created");
//        new Exception().printStackTrace();
        if (list != null) {
            list.stream()
                    .map(ValWrapper::wrapVal)    // Convert each element to ValWrapper
                    .forEach(this::add);     // Add to this EvalList
        }
    }

    @Override
    public boolean getBoolValue() {
        System.out.println("@@--getBoolValue: " + this);
        return switch (checkMode) {
            case ANY -> this.stream().anyMatch(currentPredicate);
            case NONE -> this.stream().noneMatch(currentPredicate);
            case ALL -> this.stream().allMatch(currentPredicate);
        };
    }

    public EvalList checkAny() {
        this.checkMode = CheckMode.ANY;
        return this;
    }

    public EvalList checkNone() {
        this.checkMode = CheckMode.NONE;
        return this;
    }

    public EvalList checkAll() {
        this.checkMode = CheckMode.ALL;
        return this;
    }

    public EvalList checkForValue() {
        this.currentPredicate = ValWrapper::hasValue;
        return this;
    }

    public EvalList checkForNoValue() {
        this.currentPredicate = wrapper -> !wrapper.hasValue();
        return this;
    }

    public EvalList checkIfTrue() {
        this.currentPredicate = ValWrapper::getBoolValue;
        return this;
    }

    public EvalList isFalse() {
        this.currentPredicate = wrapper -> !wrapper.getBoolValue();
        return this;
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