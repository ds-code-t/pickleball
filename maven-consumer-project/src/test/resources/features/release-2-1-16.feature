@all @release-216
Feature: Pickleball 2.1.16 value API, matching, masking, JDBC, and cleanup

  Scenario: Java values round-trip with Gherkin and conversion directives
    * , save "42" as "total"
    * , save "v1" as "x"
    * , save "Tom & Jerry" as "name"
    * MAP TABLE VALUES TO RUN MAP
      | payload | {"title":"Tom & Jerry"} |
      | body.title~XML-ESCAPE; | Tom & Jerry |
    * RUN RELEASE 216 JAVA TESTS
    * , ensure "<javaName>" equals "from-java"
    * , ensure "<x>" equals "v1"
    * , ensure "<orders #2.sku>" equals "B"
    * , ensure "<total>" equals "42"
    * , ensure "<name~XML-ESCAPE;>" equals "Tom &amp; Jerry"
    * , ensure "<payload~JSON;::title~XML-ESCAPE;>" equals "Tom &amp; Jerry"
    * , ensure "<body.title>" equals "Tom &amp; Jerry"
    * , ensure "<scenarioMark>" equals "scenario"
    * , ensure "<rootMark>" equals "root"
    * , ensure "<defaultMark>" equals "default"
    * , ensure "<overrideMark>" equals "override"
    * , ensure "<singletonMark>" equals "singleton"
    * , ensure "<reusedSecret>" equals "******"

  Scenario: Lane one keeps its own scenario and step values
    * RUN RELEASE 216 LANE one
    * , ensure "<laneScenario>" equals "one"

  Scenario: Lane two keeps its own scenario and step values
    * RUN RELEASE 216 LANE two
    * , ensure "<laneScenario>" equals "two"

  Scenario: Phrase matches are full matches and honor quote types
    * , save "untouched" as "saveOnly"
    * , save "untouched" as "saveAll"
    * , save "untouched" as "autosave"
    * , save "untouched" as "saveStar"
    * , save "untouched" as "autoStar"
    * , save "untouched" as "caseFold"
    * , save "untouched" as "caseSensitive"
    * , save "untouched" as "rawTick"
    * , save "untouched" as "rawMiss"
    * , save "untouched" as "doesNot"
    * IF: "Save" matches "Save" THEN: , save "yes" as "saveOnly"
    * IF: "Save All" matches "Save" THEN: , save "no" as "saveAll"
    * IF: "Autosave" matches "Save" THEN: , save "no" as "autosave"
    * IF: "Save" matches "Save.*" THEN: , save "yes" as "saveStar"
    * IF: "Save All" matches "Save.*" THEN: , save "yes" as "saveStar"
    * IF: "Autosave" matches "Save.*" THEN: , save "no" as "autoStar"
    * IF: "Save All" matches 'save all' THEN: , save "yes" as "caseFold"
    * IF: "Save All" matches "save all" THEN: , save "no" as "caseSensitive"
    * IF: "Save All" matches `Save All` THEN: , save "yes" as "rawTick"
    * IF: "save all" matches `Save All` THEN: , save "no" as "rawMiss"
    * IF: "Autosave" does not match "Save" THEN: , save "yes" as "doesNot"
    * , ensure "Autosave" does not match "Save"
    * , ensure "<saveOnly>" equals "yes"
    * , ensure "<saveAll>" equals "untouched"
    * , ensure "<autosave>" equals "untouched"
    * , ensure "<saveStar>" equals "yes"
    * , ensure "<autoStar>" equals "untouched"
    * , ensure "<caseFold>" equals "yes"
    * , ensure "<caseSensitive>" equals "untouched"
    * , ensure "<rawTick>" equals "yes"
    * , ensure "<rawMiss>" equals "untouched"
    * , ensure "<doesNot>" equals "yes"

  Scenario: Element text matching is a full match
    * navigate to: URL.textMatch
    * , click the Button matching "Save"
    * RUN RELEASE 216 CLICKED save
    * , click the first Button matching "Save.*"
    * RUN RELEASE 216 CLICKED save
    * , click the 2nd Button matching "Save.*"
    * RUN RELEASE 216 CLICKED save-all
    * , click the Button matching 'save all'
    * RUN RELEASE 216 CLICKED save-all
    * , click the Button matching `Save All`
    * RUN RELEASE 216 CLICKED save-all
    * , save "untouched" as "nope"
    * IF: the Button matching "Nope" is present THEN: , save "seen" as "nope"
    * , ensure "<nope>" equals "untouched"

  Scenario: A masked prefix types the real secret and logs stars
    * navigate to: URL.textMatch
    * , enter "Bearer <password:testAccount>" in the "Secret Field" Textbox
    * RUN RELEASE 216 SECRET FIELD
    * , save "<password:testAccount>" as "pw~MASK;"
    * , save "<pw~UNMASK;>" as "pwPlain"
    * , ensure "<pw>" equals "******"
    * RUN RELEASE 216 UNMASK

  Scenario: softFail continues the step and runs the soft-failed flag
    * , save "no" as "flagSoft"
    * RUN RELEASE 216 SOFT FAIL
    * RUN IF SCENARIO SOFT FAILED: , save "yes" as "flagSoft"
    * , ensure "<flagSoft>" equals "yes"

  Scenario: A JDBC service call runs beside an HTTP service call
    * RUN RELEASE 216 PREPARE JDBC
    * MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
      | endpoint | <jdbcUrl> |
      | user | sa |
      | password | <password:qaDb> |
      | sql | select 1 as n |
    * EXECUTE SERVICE CALL
    * , ensure "<RESPONSE.rowCount>" equals "1"
    * RUN "inlineRead" SERVICE CALL: %inspect-get
      | endpoint | client | traceId | include | mode |
      | http://127.0.0.1:8765 | caller-test | trace-get-1 | inventory | full |
    * , ensure "<inlineRead.RESPONSE.statusCode>" equals "200"
    * RUN RELEASE 216 LOG HIDES SECRETS

  Scenario: Registered-object cleanup runs when the scenario passes
    * RUN RELEASE 216 REGISTER CLEANUP success

  Scenario: Registered-object cleanup runs when the scenario soft-fails
    * RUN RELEASE 216 REGISTER CLEANUP failure
    * RUN RELEASE 216 SOFT FAIL

  Scenario: Registered-object cleanup runs when the scenario hard-fails
    * RUN RELEASE 216 REGISTER CLEANUP hard
    * FAIL: release 216 hard failure
