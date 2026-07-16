Feature: Reuse a customer-saving business flow

  @all @regression @browser @local-site @components @run-scenarios @component-caller @smoke
  Scenario: Save multiple customers through a reusable component
    * navigate to: URL.components
    * , ensure "Component Scenario Playground" Text is displayed
    * RUN SCENARIOS
      | Run Tags       | customerName | tier     |
      | %save_customer | Ava          | Premium  |
      | %save_customer | Ben          | Standard |
    * , ensure "Saved Customer: Ben | Standard" Text is displayed

  @all @regression @browser @local-site @components @component-definition @scenario-outline
  Scenario Outline: Save customer component
    * navigate to: URL.components
    * , enter "<customerName>" in the "Customer Name" Textbox
    * , select "<tier>" in the "Customer Tier" Dropdown
    * , click the "Save Customer" Button
    * , ensure "Saved Customer: <customerName> | <tier>" Text is displayed

    Examples:
      | Scenario Tags  | ?customerName   | tier     |
      | %save_customer | Default Customer | Standard |
