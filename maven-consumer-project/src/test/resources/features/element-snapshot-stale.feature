Feature: Element snapshot stale recovery

  # pkb_composite is a run-level setting, not a scenario tag. Both scenarios
  # call the one refresh path. ElementOperations follows the run: composite
  # when that backend is primary, Selenium when pkb_composite is off or no
  # binary is present. A composite-held node with the binary absent is covered
  # by ElementWrapperRefreshTest.

  @all @regression @browser @local-site @dom-composite @stale-recovery @pkb-composite-off
  Scenario: Stale element refreshes through its stable selector when composite is off
    * navigate to: URL.snapshotStale
    * the live element refreshes after it is re-rendered

  @all @regression @browser @local-site @dom-composite @stale-recovery @pkb-composite-on
  Scenario: Stale element refreshes through its stable selector when composite is on
    * navigate to: URL.snapshotStale
    * the live element refreshes after it is re-rendered
