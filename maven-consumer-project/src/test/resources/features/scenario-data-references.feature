Feature: Scenario data references

  @all @regression @scenario-data
  Scenario: Read marker data from the default data path
    * VERIFY DATA ADDRESS "Customer record.payload" HAS MARKER "payload"
    * VERIFY EMBEDDED DATA ADDRESS "Customer record.payload" HAS MARKER "payload"


  @all @regression @scenario-data
  Scenario: Use RUN SCENARIO options for marker data lookup
    * VERIFY DATA ADDRESS "payload" HAS MARKER "payload"
      | pkb_features                            | pkb_name          |
      | src/test/resources/data                 | ^Customer record$ |

  @all @regression @scenario-data
  Scenario: Resolve a marker from the closest current scenario
    * ---local payload
    * VERIFY EMBEDDED DATA ADDRESS "local payload" HAS MARKER "local payload"
