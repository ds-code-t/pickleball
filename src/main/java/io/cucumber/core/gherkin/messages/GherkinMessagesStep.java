/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Argument;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.StepType;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.messages.types.PickleDocString;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.plugin.event.Location;
import io.pickleball.exceptions.PickleballException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentState;
import static java.util.Arrays.asList;

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
//    private boolean forceRun = false;


    public String getRunTimeText() {
        if (runTimeText == null)
            return getText();
        return runTimeText;
    }

    public int getColonNesting() {
        return colonNesting;
    }

    public List<String> getFlagList() {
        return flagList;
    }

    public PickleStep getPickleStep() {
        return pickleStep;
    }

    @Override
    public String getKeyWord() {
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

//        return pickleStep.getText();
    }


    // pmode keyword getText() @IF


    public void copyTemplateParameters(GherkinMessagesStep templateStep) {
        runFlag = templateStep.runFlag;
        colonNesting = templateStep.colonNesting;
        keyWord = templateStep.keyWord;
        runTimeText = templateStep.runTimeText;
        flagList = templateStep.flagList;
    }


    //    public static final String POST_SCENARIO_STEPS = "@POST-SCENARIO-STEPS:";
    public static final String END_SCENARIO = "@END-SCENARIO:";
    public static final String FAIL_SCENARIO = "@FAIL-SCENARIO:";
    public static final String END_TEST = "@END-TEST:";
    public static final String FAIL_TEST = "@FAIL-TEST:";


    public static final String RUN_ON_PASS = "@RUN_ON_PASS:";
    public static final String RUN_ON_FAIL = "@RUN_ON_FAIL:";
    public static final String RUN_ON_HARD_FAIL = "@RUN_ON_HARD_FAIL:";
    public static final String RUN_ON_SOFT_FAIL = "@RUN_ON_SOFT_FAIL:";

    public static final String RUN_ALWAYS = "@RUN_ALWAYS:";
    public static final String RUN_IF = "@RUN_IF:";
    public static final String IFSuffix = "_IF:";


    public static final List<String> givenPrefixes = asList("* ", "Given ", ":", RUN_ALWAYS, RUN_IF, END_SCENARIO, FAIL_SCENARIO, END_TEST, FAIL_TEST, RUN_ON_PASS, RUN_ON_FAIL, RUN_ON_HARD_FAIL, RUN_ON_SOFT_FAIL);
    public static final String[] prefixWords = Stream.concat(
            givenPrefixes.stream(),
            Stream.of("When ", "Then ", "And ", "But ")
    ).toArray(String[]::new);

    public int parseRunTimeParameters() {
        return parseRunTimeParameters(getKeyWord() + " " + getText());
    }
//    @RUN-ALWAYS:
    final Pattern runFlagCheck = Pattern.compile("@RUN(?:_[^:]+)?:");

    public String getRunFlag() {
        return runFlag;
    }

    private String runFlag = "";

    public String getTextSuffix() {
        return textSuffix;
    }

    public void setTextSuffix(String textSuffix) {
        this.textSuffix = textSuffix;
    }

    private String textSuffix = "";

    public int parseRunTimeParameters(String initialText) {
        Pattern pattern = Pattern.compile("^(?<colons>(?:\\s*:)*)\\s*(?<flags>@\\S*\\s+)*(?:(?<keyWord>[^@]\\S+\\s+)(?<stepText>.*))?$");
        Matcher matcher = pattern.matcher(initialText);
        if (matcher.find()) {
            runTimeText = matcher.group("stepText");
            colonNesting = Optional.ofNullable(matcher.group("colons"))
                    .map(s -> s.replaceAll("\\s+", "").length())
                    .orElse(0);
            keyWord = matcher.group("keyWord");
            keyWord = "\u2003".repeat(colonNesting) + keyWord.strip() + " ";

            String flagString = matcher.group("flags");
            if (flagString != null && flagString.contains("@RUN")) {
                List<String> runFlagMatches = new ArrayList<>();
                Matcher runFlagMatcher = runFlagCheck.matcher(flagString);
                while (runFlagMatcher.find()) {
                    runFlagMatches.add(runFlagMatcher.group());
                }
                if (runFlagMatches.size() > 1) {
                    String matchList = runFlagMatches.stream().collect(Collectors.joining(", "));
                    throw new PickleballException("Step cannot have more than one RUN flag: " + matchList);
                } else if (runFlagMatches.size() == 1) {
                    runFlag = runFlagMatches.get(0);
                    if (runTimeText.isEmpty()) {
                        if (runFlag.equals(RUN_ALWAYS))
                            runTimeText = "IF: true ";
                        else if (runFlag.contains(RUN_IF))
                            runTimeText = "IF: false ";
                    } else if (runFlag.contains(RUN_IF) && !runTimeText.startsWith("IF:")) {
                        runTimeText = "IF: " + runTimeText;
                    }

                    if (runFlag.endsWith(IFSuffix)) {
                        runFlag = runFlag.replace(IFSuffix, ":");
                        runTimeText = "IF: " + runTimeText;
                    }

                }
            }

            if (flagString != null)
                flagList = List.of(flagString.split("\\s+"));
        }


        if (flagList != null && runFlag.isEmpty()) {
            if (flagList.contains(END_SCENARIO))
                runTimeText = "@Terminate:" + END_SCENARIO + ",,,," + runTimeText;
            else if (flagList.contains(FAIL_SCENARIO))
                runTimeText = "@Terminate:" + FAIL_SCENARIO + ",,,," + runTimeText;
            else if (flagList.contains(END_SCENARIO))
                runTimeText = "@Terminate:" + END_TEST + ",,,," + runTimeText;
            else if (flagList.contains(FAIL_SCENARIO))
                runTimeText = "@Terminate:" + FAIL_TEST + ",,,," + runTimeText;
        }

        if(!runFlag.isEmpty())
            setTextSuffix(" - " + runFlag);

        return colonNesting;

    }

}
