
@all @control-bridge
Feature: Pickleball Studio control bridge

  Scenario: Paused runtime accepts retry-friendly detached control
    Given BEGIN CONTROL BRIDGE IPC TEST
    And CONTROL BRIDGE IPC SYNC POINT
    And VERIFY CONTROL BRIDGE IPC TEST
