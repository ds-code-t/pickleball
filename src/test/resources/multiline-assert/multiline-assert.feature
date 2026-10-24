@multiline-assert
Feature: Multi-line ASSERT

  Scenario: Quoted and unquoted multi-line ASSERT keeps line breaks
    * CLEAR SAVED VALUES
    * MAP "Probe" OBJECT VALUE TO SCENARIO MAP
      """json
      {"ml":"line one\nline two","pattern":"(?s)line one\nline two","jdbcPattern":"(?s).*no_such_table_x.*Position: 15"}
      """
    * MAP "Q" OBJECT VALUE TO SCENARIO MAP
      """json
      {"RESPONSE":{"error":"relation \"no_such_table_x\" does not exist\nPosition: 15","errorCrlf":"relation \"no_such_table_x\" does not exist\r\nPosition: 15"}}
      """
    * ASSERT: "<Probe.ml>" contains "line two"
    * ASSERT: <Probe.ml> contains "line two"
    * ASSERT: "<Probe.ml>" equals "<Probe.ml>"
    * ASSERT: "<Probe.ml>" starts with "line one"
    * ASSERT: "<Probe.ml>" ends with "line two"
    * ASSERT: "<Probe.ml>" matches "<Probe.pattern>"
    * ASSERT: '<Probe.ml>' contains 'LINE TWO'
    * ASSERT: `<Probe.ml>` contains `line two`
    * ASSERT: <Q.RESPONSE.error> contains "no_such_table_x"
    * ASSERT: "<Q.RESPONSE.error>" contains "no_such_table_x"
    * ASSERT: <Q.RESPONSE.error> contains "Position: 15"
    * ASSERT: <Q.RESPONSE.error> starts with "relation"
    * ASSERT: <Q.RESPONSE.error> ends with "Position: 15"
    * ASSERT: "<Q.RESPONSE.error>" matches "<Probe.jdbcPattern>"
    * ASSERT: <Q.RESPONSE.errorCrlf> contains "no_such_table_x"
    * ASSERT: "<Q.RESPONSE.errorCrlf>" contains "Position: 15"
    * ASSERT: "hello   world" equals "hello world"
    * ASSERT: "C:\Users\Dan She'll\page.html" contains "She'll"
    * ASSERT: "C:/Program Files/Pickleball/body.json" contains "Program Files"
    * CLEAR SAVED VALUES
