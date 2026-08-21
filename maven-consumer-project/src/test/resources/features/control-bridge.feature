@all @smoke @control-bridge @phase3h @phase4
Feature: Pickleball Workbench consumer-worker control bridge

  Scenario: Paused runtime supports retry-friendly investigation and control
    Given BEGIN CONTROL BRIDGE IPC TEST
    And CONTROL BRIDGE IPC SYNC POINT
    And VERIFY CONTROL BRIDGE IPC TEST

  Scenario: Player Gherkin and current ParsingMap contracts remain worker-owned
    Given VERIFY WORKBENCH PLAYER RUNTIME SUPPORT
