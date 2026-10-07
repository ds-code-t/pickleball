Feature: Area C context, actions, assertions, and stress

  @all @regression @browser @local-site @area-c @area-context @area-context-in
  Scenario: in scopes a repeated button and misses the other panel
    * navigate to: URL.catalog
    * , in the "Secondary Queue" Test Panel, click the "Approve" Button
    * , ensure "Queue Result: secondary" Text is displayed
    * , in the "Primary Queue" Test Panel, ensure the "Review" Status Badge is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-from
  Scenario: from scopes a repeated button and misses the other panel
    * navigate to: URL.catalog
    * , from the "Primary Queue" Test Panel, click the "Approve" Button
    * , ensure "Queue Result: primary" Text is displayed
    * , from the "Secondary Queue" Test Panel, ensure the "Approved" Status Badge is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-for
  Scenario: for scopes a repeated button and misses the other panel
    * navigate to: URL.catalog
    * , for the "Secondary Queue" Test Panel, click the "Approve" Button
    * , ensure "Queue Result: secondary" Text is displayed
    * , for the "Primary Queue" Test Panel, ensure the "Review" Status Badge is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-after
  Scenario: after chooses the following button and misses the preceding one
    * navigate to: URL.components
    * , after the "Order Anchor" Button, click the "After Marker" Button
    * , ensure "Order Result: after" Text is displayed
    * , after the "Order Anchor" Button, ensure the "Before Marker" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-before
  Scenario: before chooses the preceding button and misses the following one
    * navigate to: URL.components
    * , before the "Order Anchor" Button, click the "Before Marker" Button
    * , ensure "Order Result: before" Text is displayed
    * , before the "Order Anchor" Button, ensure the "After Marker" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-below
  Scenario: below chooses the lower button and misses the upper one
    * navigate to: URL.components
    * , below the "Spatial Anchor" Button, click the "Below Target" Button
    * , ensure "Spatial Result: below" Text is displayed
    * , below the "Spatial Anchor" Button, ensure the "Above Target" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-above
  Scenario: above chooses the upper button and misses the lower one
    * navigate to: URL.components
    * , above the "Spatial Anchor" Button, click the "Above Target" Button
    * , ensure "Spatial Result: above" Text is displayed
    * , above the "Spatial Anchor" Button, ensure the "Below Target" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-left
  Scenario: left of chooses the left button and misses the right one
    * navigate to: URL.components
    * , left of the "Spatial Anchor" Button, click the "Left Target" Button
    * , ensure "Spatial Result: left" Text is displayed
    * , left of the "Spatial Anchor" Button, ensure the "Right Target" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-right
  Scenario: right of chooses the right button and misses the left one
    * navigate to: URL.components
    * , right of the "Spatial Anchor" Button, click the "Right Target" Button
    * , ensure "Spatial Result: right" Text is displayed
    * , right of the "Spatial Anchor" Button, ensure the "Left Target" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-nest-2
  Scenario: nesting two contexts clicks the middle button
    * navigate to: URL.components
    * , in the "Outer Panel" Test Panel, in the "Middle Section" Section, click the "Middle Only" Button
    * , ensure "Nest Result: middle" Text is displayed
    * , in the "Inner Section" Section, ensure the "Middle Only" Button is not displayed

  @all @regression @browser @local-site @area-c @area-context @area-context-nest-3
  Scenario: nesting three contexts clicks the deep button
    * navigate to: URL.components
    * , in the "Outer Panel" Test Panel, in the "Middle Section" Section, in the "Inner Section" Section, click the "Deep Action" Button
    * , ensure "Nest Result: deep" Text is displayed

  @all @regression @browser @local-site @area-c @area-headers
  Scenario: Headers lookup uses the last parent
    * navigate to: URL.catalog
    * , ensure the 2nd Headers is displayed
    * , ensure the "Owner" Header is displayed

  @all @regression @browser @local-site @area-c @area-actions @area-action-hover
  Scenario: hover moves the pointer inline and in a block
    * navigate to: URL.forms
    * , hover the "Interaction Target" Button
    * , ensure "Last Pointer Action: moved over" Text is displayed
    * , hover the "Interaction Target" Button:
    : * , save "hovered" as "hoverBlock"
    * , ensure "<hoverBlock>" equals "hovered"

  @all @regression @browser @local-site @area-c @area-actions @area-action-move
  Scenario: move moves the pointer inline and in a block
    * navigate to: URL.forms
    * , move to the "Interaction Target" Button
    * , ensure "Last Pointer Action: moved over" Text is displayed
    * , move to the "Interaction Target" Button:
    : * , save "moved" as "moveBlock"
    * , ensure "<moveBlock>" equals "moved"

  @all @regression @browser @local-site @area-c @area-actions @area-action-double-click
  Scenario: double click runs inline and in a block
    * navigate to: URL.forms
    * , double click the "Interaction Target" Button
    * , ensure "Last Pointer Action: double click" Text is displayed
    * , double click the "Interaction Target" Button:
    : * , save "doubled" as "doubleBlock"
    * , ensure "<doubleBlock>" equals "doubled"

  @all @regression @browser @local-site @area-c @area-actions @area-action-right-click
  Scenario: right click runs inline and in a block
    * navigate to: URL.forms
    * , right click the "Interaction Target" Button
    * , ensure "Last Pointer Action: right click" Text is displayed
    * , right click the "Interaction Target" Button:
    : * , save "righted" as "rightBlock"
    * , ensure "<rightBlock>" equals "righted"

  @all @regression @browser @local-site @area-c @area-actions @area-action-scroll
  Scenario: scroll runs inline and in a block
    * navigate to: URL.components
    * , scroll the "Scroll Target" Button
    * , ensure "Scroll State: moved" Text is displayed
    * , scroll the "Scroll Target" Button:
    : * , save "scrolled" as "scrollBlock"
    * , ensure "<scrollBlock>" equals "scrolled"

  @all @regression @browser @local-site @area-c @area-actions @area-action-wait
  Scenario: wait runs inline and in a block
    * , wait 1 seconds
    * , save "before" as "waitInline"
    * , ensure "<waitInline>" equals "before"
    * , wait 1 seconds:
    : * , save "waited" as "waitBlock"
    * , ensure "<waitBlock>" equals "waited"

  @all @regression @browser @local-site @area-c @area-actions @area-action-overwrite
  Scenario: overwrite runs inline and in a block
    * navigate to: URL.forms
    * , overwrite "3" in the "Quantity" Textbox
    * , ensure "Quantity: 3" Text is displayed
    * , overwrite "4" in the "Quantity" Textbox:
    : * , save "overwritten" as "overwriteBlock"
    * , ensure "Quantity: 4" Text is displayed
    * , ensure "<overwriteBlock>" equals "overwritten"

  @all @regression @browser @local-site @area-c @area-actions @area-action-clear
  Scenario: clear runs inline and in a block
    * navigate to: URL.forms
    * , enter "Temporary" in the "First Name" Textbox
    * , clear the "First Name" Textbox
    * , ensure "Name: (empty)" Text is displayed
    * , enter "Again" in the "First Name" Textbox
    * , clear the "First Name" Textbox:
    : * , save "cleared" as "clearBlock"
    * , ensure "Name: (empty)" Text is displayed
    * , ensure "<clearBlock>" equals "cleared"

  @all @regression @browser @local-site @area-c @area-actions @area-action-select
  Scenario: select runs inline and in a block
    * navigate to: URL.forms
    * , select "Premium" in the "Account Type" Dropdown
    * , ensure "Account Type: Premium" Text is displayed
    * , select "Standard" in the "Account Type" Dropdown:
    : * , save "selected" as "selectBlock"
    * , ensure "Account Type: Standard" Text is displayed
    * , ensure "<selectBlock>" equals "selected"

  @all @regression @browser @local-site @area-c @area-actions @area-action-attach
  Scenario: attach uploads a file inline and in a block
    * navigate to: URL.components
    * , attach the InternalFileInput "customers.yaml"
    * , ensure "Upload Result: customers.yaml" Text is displayed
    * , attach the InternalFileInput "customers.yaml":
    : * , save "attached" as "attachBlock"
    * , ensure "<attachBlock>" equals "attached"

  @all @regression @browser @local-site @area-c @area-actions @area-action-create-attach
  Scenario: create and attach uploads a new file inline and in a block
    * navigate to: URL.components
    * , create and attach the InternalFileInput "area-c-note.txt"
    * , ensure "Upload Result: area-c-note.txt" Text is displayed
    * , create and attach the InternalFileInput "area-c-note.txt":
    : * , save "created" as "createAttachBlock"
    * , ensure "<createAttachBlock>" equals "created"

  @all @regression @browser @local-site @area-c @area-actions @area-action-drag
  Scenario: dragAndDrop runs inline and in a block
    * navigate to: URL.components
    * , dragAndDrop the "Drag Source" Button the "Drop Target" Button
    * , ensure "Drag Result: dropped" Text is displayed
    * , dragAndDrop the "Drag Source" Button the "Drop Target" Button:
    : * , save "dragged" as "dragBlock"
    * , ensure "<dragBlock>" equals "dragged"

  @all @regression @area-c @area-assertions @area-assert-contains
  Scenario: contains passes on its own
    * , ensure "Phoenix" contains "nix"
    * , ensure "Phoenix" contains "nix":
    : * , save "contained" as "containsBlock"
    * , ensure "<containsBlock>" equals "contained"

  @all @regression @area-c @area-assertions @area-assert-greater
  Scenario: greater than passes on its own
    * , ensure 5 is greater than 1
    * , ensure 5 is greater than 1:
    : * , save "greater" as "greaterBlock"
    * , ensure "<greaterBlock>" equals "greater"

  @all @regression @area-c @area-assertions @area-assert-less
  Scenario: less than passes on its own
    * , ensure 1 is less than 5
    * , ensure 1 is less than 5:
    : * , save "less" as "lessBlock"
    * , ensure "<lessBlock>" equals "less"

  @all @regression @area-c @area-assertions @area-assert-gte
  Scenario: greater than or equal passes on its own
    * , ensure 5 is greater than or equal to 5
    * , ensure 5 is greater than or equal to 5:
    : * , save "gte" as "gteBlock"
    * , ensure "<gteBlock>" equals "gte"

  @all @regression @area-c @area-assertions @area-assert-lte
  Scenario: less than or equal passes on its own
    * , ensure 5 is less than or equal to 5
    * , ensure 5 is less than or equal to 5:
    : * , save "lte" as "lteBlock"
    * , ensure "<lteBlock>" equals "lte"

  @all @regression @browser @local-site @area-c @area-assertions @area-assert-enabled
  Scenario: enabled and disabled pass on their own
    * navigate to: URL.forms
    * , ensure the "Submit Form" Button is enabled
    * , ensure the "Locked Action" Button is disabled
    * , ensure the "Submit Form" Button is enabled:
    : * , save "enabled" as "enabledBlock"
    * , ensure "<enabledBlock>" equals "enabled"

  @all @regression @browser @local-site @area-c @area-assertions @area-assert-on-off
  Scenario: is on and is off pass on their own
    * navigate to: URL.forms
    * , ensure the "Receive Updates" Checkbox is off
    * , click the "Receive Updates" Checkbox
    * , ensure the "Receive Updates" Checkbox is on
    * , ensure the "Receive Updates" Checkbox is on:
    : * , save "on" as "onBlock"
    * , ensure "<onBlock>" equals "on"

  @all @regression @area-c @area-assertions @area-assert-false
  Scenario: is false checks a falsy value and bare false stays falsy
    * , save "stayed" as "bareFalse"
    * , if false, save "ran" as "bareFalse"
    * , ensure "<bareFalse>" equals "stayed"
    * , if "false" is false, save "ran" as "isFalseHit"
    * , ensure "<isFalseHit>" equals "ran"
    * , save "stayed" as "isFalseMiss"
    * , if "abc" is false, save "ran" as "isFalseMiss"
    * , ensure "<isFalseMiss>" equals "stayed"
    * , if "true" is true, save "ran" as "isTrueHit"
    * , ensure "<isTrueHit>" equals "ran"

  @all @regression @browser @local-site @area-c @area-stress @area-stress-page
  Scenario: page context ordinal it nested if data row save and verify
    * navigate to: URL.catalog
    * , click the 2nd "View Details" Button
    * , ensure "Selected Product: 2" Text is displayed
    * , if the "Advanced Filters" Button is present, click it
    * , ensure "Advanced Filter Panel: open" Text is displayed
    * , in the "Primary Queue" Test Panel, if the "Approve" Button is present, click it
    * , ensure "Queue Result: primary" Text is displayed
    * navigate to: URL.forms
    * , if the "Required Marker" Textbox is required, enter "Ava" in the "First Name" Textbox
    * , ensure "Name: Ava" Text is displayed
    * , if the "Submit Form" Button is displayed, click it
    * , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Row with value equaling "blocked" as "stressRow"
    * , verify "<stressRow.id>" equals "r2"
    * , verify "<stressRow.captureKey>" equals "row2Seen"

  @all @regression @browser @local-site @area-c @area-stress @area-stress-keyboard-dialog
  Scenario: keyboard hold dialog accept and one navigation
    * navigate to: URL.home
    * , ensure "Load Count: 1" Text is displayed
    * navigate to: URL.keyboard
    * , click the "Keyboard Input" Textbox
    * , press "CONTROL[A]" in the "Keyboard Input" Textbox
    * , ensure "Last Key State: KeyA active=ControlLeft+KeyA ctrl=true shift=false alt=false" Text is displayed
    * navigate to: URL.dialogs
    * , click the "Show Alert" Button
    * , accept the Alert
    * , ensure "Dialog Result: alert accepted" Text is displayed

  @all @regression @browser @local-site @area-c @area-c-java
  Scenario: Area C expected failures stay inside Java checks
    * RUN AREA C JAVA TESTS
