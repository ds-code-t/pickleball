Feature: Scenario marker data extraction

  @all @regression @scenario-data
  Scenario: Read marker data without executing the component scenario
    * VERIFY SCENARIO DATA: Scenario marker data extraction.Marker data component.marker stored example-row
      | passedValue |
      | stored      |
    * , verify "the component was not executed" equals "the component was not executed"

  @component-definition
  Scenario Outline: Marker data component
    * ---marker <passedValue> <exampleValue>
    * , verify "this component must not execute" equals "data extraction only"

    Examples:
      | exampleValue |
      | example-row  |
