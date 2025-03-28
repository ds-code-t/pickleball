package io.pickleball.cacheandstate;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.pickleball.exceptions.PickleballException;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import io.pickleball.mapandStateutilities.MapsWrapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.gherkin.messages.GherkinMessagesStep.bookmarksPattern;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.cucumberutilities.ArgumentParsing.convertCommandLineToArgv;
import static io.pickleball.cucumberutilities.ArgumentParsing.convertHashMapToArgv;
import static io.pickleball.cucumberutilities.GeneralUtilities.waitTime;
import static io.pickleball.executions.ComponentRuntime.createTestcases;
import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;
import static java.util.Comparator.comparingInt;

public abstract class ScenarioContext extends BaseContext implements io.cucumber.plugin.event.TestStep {
    private final Pickle pickle;             // The static scenario definition
    private TestCaseState testCaseState;    // The mutable scenario state
    protected final List<StepWrapper> topLevelSteps = new ArrayList<>();
    protected final List<StepWrapper> allSteps = new ArrayList<>();
    protected final Map<Object, List<Integer>> indexMap = new HashMap<>();
    protected List<Integer> indexList;

    public void setCurrentWrapperNum(int currentWrapperNum) {
        this.currentWrapperNum = currentWrapperNum;
    }

    protected int currentWrapperNum = 0;


    public Pattern getGoToRegex() {
        return goToRegex;
    }

//    public void setGoToRegex(String regexPattern) {
//        this.runStatus = RunStatus.FIND_ANY;
//        if(regexPattern == null || regexPattern.isBlank())
//            this.goToRegex = null;
//        this.goToRegex = Pattern.compile(regexPattern);
//    }

    public void setGoToRegex(String regexPattern, RunStatus runStatus) {
        this.goToBookmarks = null;
        this.runStatus = runStatus;
        if (regexPattern == null || regexPattern.isBlank())
            this.goToRegex = null;
        this.goToRegex = Pattern.compile(regexPattern);
    }

    public void setGoToBookmark(String bookmarksString, RunStatus runStatus) {
        this.goToRegex = null;
        this.runStatus = runStatus;

        if (bookmarksString == null || bookmarksString.isBlank())
            this.goToBookmarks = null;
        else
            this.goToBookmarks = new ArrayList<>();

        Matcher bookmarksMatcher = bookmarksPattern.matcher(bookmarksString + " ");
        while (bookmarksMatcher.find()) {
            goToBookmarks.add(bookmarksMatcher.group().strip());
        }
    }

    protected Pattern goToRegex = null;
    protected List<String> goToBookmarks = null;

    public enum RunStatus {
        NORMAL,
        FIND_NEXT,
        FIND_PREVIOUS,
        FIND_ANY,
        FIND_FIRST,
        FIND_LAST
    }

    protected RunStatus runStatus = RunStatus.NORMAL;


    public List<Integer> getIndicesForPattern() {
        if (goToRegex == null)
            throw new PickleballException("Step text matching pattern not set");
        return indexMap.computeIfAbsent(goToRegex, p -> {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < allSteps.size(); i++) {
                if (((Pattern) p).matcher(allSteps.get(i).getRunTimeText()).matches()) {
                    indices.add(i);
                }
            }
            indexList = Collections.unmodifiableList(indices);
            return indexList;
        });
    }

    public List<Integer> getIndicesForBookmarks() {
        if (goToBookmarks == null)
            throw new PickleballException("Bookmarks not set");
        return indexMap.computeIfAbsent(goToBookmarks, p -> {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < allSteps.size(); i++) {
                List<String> bookMarksList = allSteps.get(i).getGherkinMessagesStep().getBookmarksList();
//                if (!bookMarksList.isEmpty() && goToBookmarks.stream().allMatch(allSteps::contains))
                if (!bookMarksList.isEmpty() && bookMarksList.containsAll(goToBookmarks))
                    indices.add(i);
            }
            indexList = Collections.unmodifiableList(indices);
            return indexList;
        });
    }


    public Integer findBoundaryValue(Integer val, boolean next) {
        if (getGoToRegex() == null)
            getIndicesForBookmarks();
        else
            getIndicesForPattern();
        if (indexList == null || indexList.isEmpty()) {
            return null;
        }

        if (next) {
            // Find the first integer higher than val
            return indexList.stream()
                    .filter(num -> num > val)
                    .findFirst()
                    .orElse(null);
        } else {
            // Find the last integer lower than val
            return indexList.stream()
                    .filter(num -> num < val)
                    .reduce((first, second) -> second)
                    .orElse(null);
        }
    }


//    public Pattern getGoToNextStepRegex() {
//        return goToNextStepRegex;
//    }
//
//    public void setGoToNextStepRegex(String goToNextStepRegex) {
//        if (goToNextStepRegex == null)
//            this.goToNextStepRegex = null;
//        else
//            this.goToNextStepRegex = Pattern.compile(goToNextStepRegex);
//    }
//
//    Pattern goToNextStepRegex = null;


//    public Pattern getGoToPreviousStepRegex() {
//        return goToPreviousStepRegex;
//    }
//
//    public void setGoToPreviousStepRegex(String goToPreviousStepRegex) {
//        if (goToPreviousStepRegex == null)
//            this.goToPreviousStepRegex = null;
//        else
//            this.goToPreviousStepRegex = Pattern.compile(goToPreviousStepRegex);
//    }
//
//    Pattern goToPreviousStepRegex = null;

//    RunStatus runStatus = RunStatus.CHECKING;
//    RunStatus lastRunStatus = RunStatus.CHECKING;


//    public int nestLevel = 0;

//    Map<Integer, RunStatus> runStates = new HashMap<>();
//
//    public RunStatus getRunStatus() {
//        return runStates.getOrDefault(nestLevel, RunStatus.CHECKING);
//    }
//
//    public void setRunStatus(RunStatus runStatus) {
//        runStates.put(nestLevel, runStatus);
//    }


    public boolean isForceComplete() {
        return forceComplete;
    }

    public void forceComplete() {
        this.forceComplete = true;
    }

    private boolean forceComplete = false;

    private final Runner runner;

    private final List<ScenarioContext> children = new ArrayList<>();
    private final List<StepWrapper> stepChildren = new ArrayList<>();

    private final UUID id;

    public LinkedMultiMap<String, String> getPassedMap() {
        return passedMap;
    }

    public LinkedMultiMap<String, String> getExamplesMap() {
        return examplesMap;
    }

    public LinkedMultiMap<String, String> getStateMap() {
        return stateMap;
    }

    private final LinkedMultiMap<String, String> passedMap;
    private final LinkedMultiMap<String, String> examplesMap;

    public final LinkedMultiMap<String, String> stateMap = new LinkedMultiMap<>();

    public final MapsWrapper runMaps;
    public MapsWrapper configMaps;

    public ScenarioContext(UUID id, GherkinMessagesPickle pickle, Runner runner, LinkedMultiMap<String, String> passedMap) {
        this.id = id;
        this.pickle = pickle;
        this.passedMap = passedMap;
        this.runner = runner;


        TableRow valuesRow = pickle.getMessagePickle().getValueRow();

        if (valuesRow != null) {
            List<String> headers = pickle.getMessagePickle().getHeaderRow().stream().map(TableCell::getValue).toList();
            List<String> values = valuesRow.getCells().stream().map(TableCell::getValue).toList();
            examplesMap = new LinkedMultiMap<>(headers, values);
        } else {
            examplesMap = null;
        }
        runMaps = new MapsWrapper(this.passedMap, this.examplesMap, this.stateMap);
//        runMaps = new MapsWrapper(this.passedMap, this.examplesMap, this.stateMap, getGlobalConfigs());
    }

    public Object replaceAndEval(String inputString) {
        return replaceNestedBrackets(inputString, runMaps);
    }

    public boolean isTopLevel() {
        return descendantLevel == 0;
    }

    public Stack<PickleStepTestStep> getExecutingStepStack() {
        return executingStepStack;
    }

    private final Stack<PickleStepTestStep> executingStepStack = new Stack<>();

    public static void setCurrentStep(PickleStepTestStep currentStep) {
        getCurrentScenario().getExecutingStepStack().add(currentStep);
    }

    public static StepContext popCurrentStep() {
        return getCurrentScenario().getExecutingStepStack().pop();
    }

    public PickleStepTestStep parentStep;

    public void addChildScenarioContext(TestCase child) {
        child.parentTestCase = (TestCase) this;
        child.descendantLevel = (descendantLevel + 1);
        child.position = children.size();
        children.add(child);
        child.parentStep = getCurrentStep();
    }

    public void addChildStepContext(StepWrapper child) {
        child.parentTestCase = (TestCase) this;
        child.descendantLevel = (descendantLevel + 1);
        child.position = stepChildren.size();
        stepChildren.add(child);
    }


    public final void createComponentScenario(String argString, LinkedMultiMap<String, String> map) {
        createComponentScenario(convertCommandLineToArgv(argString), map);
    }
//

    public final void createComponentScenario(Map<String, Object> argMap, LinkedMultiMap<String, String> map) {
        createComponentScenario(convertHashMapToArgv(argMap), map);
    }


    public final List<TestCase> getAndSortTestcases(String[] args, LinkedMultiMap<String, String> map) {
        List<TestCase> tests = createTestcases(args, map);
        List<TestCase> testCases = new ArrayList<>(tests);
        testCases.sort(
                comparingInt(TestCase::getPriority)
                        .thenComparing(TestCase::getName)
                        .thenComparingInt(TestCase::getLine));
        return testCases;
    }

    public void executeTestCases(List<TestCase> testCases) {
        for (TestCase testCase : testCases) {
            addChildScenarioContext(testCase);
            testCase.runComponent(getRunner().bus);
        }
    }

    public final void createComponentScenario(String[] args, LinkedMultiMap<String, String> map) {
        executeTestCases(getAndSortTestcases(args, map));
    }


    public ScenarioContext getRootScenarioContext() {
        if (parentTestCase == null)
            return this;
        return getRootScenarioContext();
    }

    public UUID getRootId() {
        return getRootScenarioContext().getId();
    }


    @Override
    public String getCodeLocation() {
        return parentStep.getCodeLocation();
    }


    public GherkinMessagesPickle getPickle() {
        return (GherkinMessagesPickle) pickle;
    }


    public TestCaseState getTestCaseState() {
        return testCaseState;
    }

    public void setTestCaseState(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }

    public Runner getRunner() {
        return runner;
    }

    public List<ScenarioContext> getChildren() {
        return children;
    }

    public List<StepWrapper> getStepChildren() {
        return stepChildren;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public PickleStepTestStep getParentStep() {
        return parentStep;
    }

}
