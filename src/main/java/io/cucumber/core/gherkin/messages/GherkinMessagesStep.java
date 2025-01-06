package io.cucumber.core.gherkin.messages;

import io.cucumber.core.backend.Status;
import io.cucumber.core.gherkin.Argument;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.StepType;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.messages.types.PickleDocString;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.plugin.event.Location;
import io.pickleball.cacheandstate.BaseContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.configs.Constants.orSubstitue;
import static io.pickleball.stringutilities.StringComponents.extractPrefix;

public final class GherkinMessagesStep implements Step {

    private final PickleStep pickleStep;
    private final Argument argument;
    private String keyWord;
    private final StepType stepType;
    private final String previousGwtKeyWord;
    private final Location location;
    private int colonNesting = 0;
    private String runTimeText;
    private List<String> flagList;
    private boolean forceRun = false;
//    private BaseContext.RunCondition runFlag;
    //    private String runTimeKeyWord;

    public String getRunTimeText() {
        if (runTimeText == null)
            parseRunTimeParameters();
        System.out.println("@@runTimeText: " + runTimeText);
        return runTimeText;
    }

//    public String getRunTimeKeyWord() {
//        if (runTimeText == null)
//            parseRunTimeParameters();
//        return runTimeKeyWord;
//    }

//    public BaseContext.RunCondition getRunFlag() {
//        if (runTimeText == null)
//            parseRunTimeParameters();
//        return runFlag;
//    }

    public int getColonNesting() {
        if (runTimeText == null)
            parseRunTimeParameters();
        return colonNesting;
    }


    public PickleStep getPickleStep() {
        return pickleStep;
    }

    @Override
    public String getKeyWord() {
        System.out.println("@@keyWord: " + keyWord);
        return keyWord;
    }

    public StepType getStepType() {
        return stepType;
    }

    public String getPreviousGwtKeyWord() {
        return previousGwtKeyWord;
    }

    public GherkinMessagesStep(
            PickleStep pickleStep,
            GherkinDialect dialect,
            String previousGwtKeyWord,
            Location location,
            String keyword
    ) {
        this.pickleStep = pickleStep;
        this.argument = extractArgument(pickleStep, location);
        this.keyWord = keyword;
        this.stepType = extractKeyWordType(keyWord, dialect);
        this.previousGwtKeyWord = previousGwtKeyWord;
        this.location = location;
    }

    private static Argument extractArgument(PickleStep pickleStep, Location location) {
        return pickleStep.getArgument()
                .map(argument -> {
                    if (argument.getDocString().isPresent()) {
                        PickleDocString docString = argument.getDocString().get();
                        // TODO: Fix this work around
                        return new GherkinMessagesDocStringArgument(docString, location.getLine() + 1);
                    }
                    if (argument.getDataTable().isPresent()) {
                        PickleTable table = argument.getDataTable().get();
                        return new GherkinMessagesDataTableArgument(table, location.getLine() + 1);
                    }
                    return null;
                }).orElse(null);
    }

    private static StepType extractKeyWordType(String keyWord, GherkinDialect dialect) {
        if (StepType.isAstrix(keyWord)) {
            return StepType.OTHER;
        }
        if (dialect.getGivenKeywords().contains(keyWord)) {
            return StepType.GIVEN;
        }
        if (dialect.getWhenKeywords().contains(keyWord)) {
            return StepType.WHEN;
        }
        if (dialect.getThenKeywords().contains(keyWord)) {
            return StepType.THEN;
        }
        if (dialect.getAndKeywords().contains(keyWord)) {
            return StepType.AND;
        }
        if (dialect.getButKeywords().contains(keyWord)) {
            return StepType.BUT;
        }
        throw new IllegalStateException("Keyword " + keyWord + " was neither given, when, then, and, but nor *");
    }

    @Override
    public String getKeyword() {
        return keyWord;
    }

    @Override
    public int getLine() {
        return location.getLine();
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public StepType getType() {
        return stepType;
    }

    @Override
    public String getPreviousGivenWhenThenKeyword() {
        return previousGwtKeyWord;
    }

    @Override
    public String getId() {
        return pickleStep.getId();
    }

    @Override
    public Argument getArgument() {
        return argument;
    }

    @Override
    public String getText() {
        if (runTimeText == null)
            return pickleStep.getText();
        return runTimeText;
    }

    //    public static final String POST_SCENARIO_STEPS = "@POST-SCENARIO-STEPS:";
    public static final String ALWAYS_RUN = "@ALWAYS-RUN:";
    public static final String RUN_IF = "@RUN-IF:";

    // pmode keyword getText() @IF
    private void parseRunTimeParameters() {

        System.out.println("@@parseRunTimeParameters: " + getKeyWord() + " " + getText());
        Pattern pattern = Pattern.compile("^(?<colons>(?:\\s*:)*)(?<flags>(?:@\\S*)\\s+)*(?<keyWord>[^@]\\S*\\s+)(?<stepText>.*)$");
        Matcher matcher = pattern.matcher(getKeyWord() + " " + getText());
        if (matcher.find()) {
            runTimeText = matcher.group("stepText");
            colonNesting = Optional.ofNullable(matcher.group("colons"))
                    .map(s -> s.replaceAll("\\s+", "").length())
                    .orElse(0);
            keyWord = matcher.group("keyWord");

            String flagString = matcher.group("flags");
            if (flagString != null)
                flagList = List.of(flagString.split("\\s+"));
        }
        if (flagList.contains(ALWAYS_RUN) || flagList.contains(RUN_IF))
            forceRun = true;

//        System.out.println("@@runTimeText== " + runTimeText);
//        System.out.println("@@keyWord== " + keyWord);
//        System.out.println("@@flagList== " + flagList);
//        System.out.println("@@colonNesting== " + colonNesting);


    }

}
