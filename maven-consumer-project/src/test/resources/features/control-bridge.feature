@all @smoke @control-bridge @phase3h @phase4
Feature: Pickleball Studio control bridge

  Scenario: Paused runtime supports retry-friendly investigation and control
    Given BEGIN CONTROL BRIDGE IPC TEST
    And CONTROL BRIDGE IPC SYNC POINT
    And VERIFY CONTROL BRIDGE IPC TEST
