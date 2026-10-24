@xml-table-cell
Feature: XML in a data table cell

  Scenario: A table cell keeps XML and still drops an unresolved reference
    * CLEAR SAVED VALUES
    * MAP "Probe" TABLE VALUES TO SCENARIO MAP
      | plain | <R> <name>Adax</name></R> |
      | ns | <ns1:R xmlns:ns1="urn:pickleball:test"><name>Adax</name></ns1:R> |
      | missing | <no.such.ref> |
    * MAP "Expected" TEXT VALUE TO SCENARIO MAP
      """text
      <R> <name>Adax</name></R>
      """
    * MAP "ExpectedNs" TEXT VALUE TO SCENARIO MAP
      """text
      <ns1:R xmlns:ns1="urn:pickleball:test"><name>Adax</name></ns1:R>
      """
    * INFO: plain=<Probe.plain>
    * INFO: ns=<Probe.ns>
    * ASSERT: "<Probe.plain>" equals "<Expected>"
    * ASSERT: "<Probe.plain~XML;::name>" equals "Adax"
    * ASSERT: "<Probe.ns>" equals "<ExpectedNs>"
    * ASSERT: "<Probe.ns~XML;::name>" equals "Adax"
    * ASSERT: "<Probe.missing>" equals "<Probe.missing>"
    * ASSERT: "<Probe.missing>" does not equal "<no.such.ref>"
    * MAP "Doc" OBJECT VALUE TO SCENARIO MAP
      """xml
      <R><name>Ada</name></R>
      """
    * ASSERT: "<Doc.name>" equals "Ada"
    * CLEAR SAVED VALUES
