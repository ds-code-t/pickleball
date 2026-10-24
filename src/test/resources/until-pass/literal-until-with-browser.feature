@until-pass-browser
Feature: Until pass with a browser session

  Scenario: A literal until with a session open does not settle
    * a headless Chrome session is open
    * , until "0" equals "1":
      : * , save "tick" as "untilTick"

  Scenario: A click still settles when a session is open
    * a headless Chrome session is open
    * a click still waits to settle
