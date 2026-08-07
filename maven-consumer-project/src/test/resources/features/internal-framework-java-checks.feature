Feature: Internal Pickleball Java checks

  @all @regression @internal-java-checks
  Scenario: Run framework checks through the published Pickleball dependency
    * RUN INTERNAL PICKLEBALL JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-1
  Scenario: Run Data Element phase one checks
    * RUN DATA ELEMENT PHASE 1 JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-2
  Scenario: Run Data Element phase two checks
    * RUN DATA ELEMENT PHASE 2 JAVA TESTS

  @all @regression @internal-java-checks @data-element-phase-3
  Scenario: Run Data Element phase three checks
    * RUN DATA ELEMENT PHASE 3 JAVA TESTS
