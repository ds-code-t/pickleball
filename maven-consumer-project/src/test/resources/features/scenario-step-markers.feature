Feature: Scenario step markers

  @all @regression @scenario-markers
  Scenario: Root scenario honors nested start and end markers
    * , verify "root before start" equals "skipped"
    : * ---startstep
    : * , verify "root marker body" equals "root marker body"
    * ---endstep
    * , verify "root after end" equals "skipped"

  @all @regression @scenario-markers
  Scenario: Component scenario honors the default start and end markers
    * RUN SCENARIO: Scenario step markers.Default marker component

  @all @regression @scenario-markers
  Scenario: Component scenario accepts an inline custom start marker
    * RUN SCENARIO: Scenario step markers.Custom marker component.component start

  @all @regression @scenario-markers
  Scenario: Component scenario accepts a table custom start marker
    * RUN SCENARIO
      | pkb_featurename       | pkb_name                    | Step_Marker     |
      | Scenario step markers | ^Custom marker component$   | component start |

  @component-definition
  Scenario: Default marker component
    * , verify "component before start" equals "skipped"
    : * ---startstep
    : * , verify "default component body" equals "default component body"
    * ---endstep
    * , verify "component after end" equals "skipped"

  @component-definition
  Scenario: Custom marker component
    * , verify "component before custom start" equals "skipped"
    : * ---component start
    : * , verify "custom component body" equals "custom component body"
    * ---endstep
    * , verify "component after custom end" equals "skipped"
