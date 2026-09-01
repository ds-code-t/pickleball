Feature: Internal Pickleball Java checks

  @all @regression @internal-java-checks @diagnostic-single
  Scenario: Run framework checks through the published Pickleball dependency
    * RUN INTERNAL PICKLEBALL JAVA TESTS

  @all @regression @internal-java-checks @mapping @quote-parser
  Scenario: Run quote-parser mapping checks
    * RUN QUOTE PARSER JAVA TESTS

  @all @regression @internal-java-checks @it-placeholder
  Scenario: Run it-placeholder parse checks
    * RUN IT PLACEHOLDER JAVA TESTS

  @all @regression @internal-java-checks @step-override
  Scenario: Run Step Override checks
    * RUN STEP OVERRIDE JAVA TESTS

  @all @regression @internal-java-checks @diagnostic-reporting
  Scenario: Run diagnostic reporting checks
    * RUN DIAGNOSTIC REPORTING JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-1
  Scenario: Run Data Element phase one checks
    * RUN DATA ELEMENT PHASE 1 JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-2
  Scenario: Run Data Element phase two checks
    * RUN DATA ELEMENT PHASE 2 JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-3
  Scenario: Run Data Element phase three checks
    * RUN DATA ELEMENT PHASE 3 JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-4 @data-element-phase-6
  Scenario: Run combined read-only collection and structured-format checks
    * RUN DATA ELEMENT PHASE 4 AND 6 JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-5
  Scenario: Run Data Element phase five copy-on-write checks
    * RUN DATA ELEMENT PHASE 5 JAVA TESTS
