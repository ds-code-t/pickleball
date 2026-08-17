Feature: Use keyboard expressions in a text field

  Background:
    * navigate to: URL.keyboard
    * , ensure "Keyboard Playground" Text is displayed

  @all @regression @browser @local-site @keyboard @keyboard-expressions @modifier-keys @smoke
  Scenario: Select all and replace the existing value
    * , enter "replace me" in the "Keyboard Input" Textbox
    * , press "CONTROL[A] BACK_SPACE" in the "Keyboard Input" Textbox
    * , enter "new value" in the "Keyboard Input" Textbox
    * , ensure "Keyboard Value: new value" Text is displayed

  @all @regression @browser @local-site @keyboard @keyboard-expressions @named-keys
  Scenario: A named key is delivered to the focused field
    * , click the "Keyboard Input" Textbox
    * , press "ENTER" in the "Keyboard Input" Textbox
    * , ensure "Last Key: Enter" Text is displayed

  @contract-coverage-217 @regression @browser @local-site @keyboard @keyboard-expressions @simultaneous-keys
  Scenario: Simultaneous keys remain down together
    * , click the "Keyboard Input" Textbox
    * , press "A+B" in the "Keyboard Input" Textbox
    * , ensure "Key Down Codes: KeyA > KeyB" Text is displayed
    * , ensure "Last Key State: KeyB active=KeyA+KeyB ctrl=false shift=false alt=false" Text is displayed

  @contract-coverage-217 @regression @browser @local-site @keyboard @keyboard-expressions @modifier-keys
  Scenario: A held modifier remains active across a sequential group
    * , click the "Keyboard Input" Textbox
    * , press "CONTROL[A B]" in the "Keyboard Input" Textbox
    * , ensure "Key Down Codes: ControlLeft > KeyA > KeyB" Text is displayed
    * , ensure "Last Key State: KeyB active=ControlLeft+KeyB ctrl=true shift=false alt=false" Text is displayed

  @contract-coverage-217 @regression @browser @local-site @keyboard @keyboard-expressions @modifier-keys @simultaneous-keys
  Scenario: Multiple held modifiers remain active across simultaneous groups
    * , click the "Keyboard Input" Textbox
    * , press "CONTROL+SHIFT[A+B C+B]" in the "Keyboard Input" Textbox
    * , ensure "Key Down Codes: ControlLeft > ShiftLeft > KeyA > KeyB > KeyC > KeyB" Text is displayed
    * , ensure "Last Key State: KeyB active=ControlLeft+ShiftLeft+KeyC+KeyB ctrl=true shift=true alt=false" Text is displayed

  @contract-coverage-217 @regression @browser @local-site @keyboard @keyboard-expressions @modifier-keys @nested-modifiers
  Scenario: Nested held groups preserve their outer modifiers
    * , click the "Keyboard Input" Textbox
    * , press "CONTROL+SHIFT[A B ALT[A B]]" in the "Keyboard Input" Textbox
    * , ensure "Key Down Codes: ControlLeft > ShiftLeft > KeyA > KeyB > AltLeft > KeyA > KeyB" Text is displayed
    * , ensure "Last Key State: KeyB active=ControlLeft+ShiftLeft+AltLeft+KeyB ctrl=true shift=true alt=true" Text is displayed

  @contract-coverage-217 @regression @browser @local-site @keyboard @keyboard-expressions @named-keys @navigation-keys
  Scenario: Navigation keys are delivered sequentially
    * , click the "Keyboard Input" Textbox
    * , press "HOME ARROW_RIGHT END" in the "Keyboard Input" Textbox
    * , ensure "Key Down Codes: Home > ArrowRight > End" Text is displayed

  @contract-coverage-217 @regression @browser @local-site @keyboard @keyboard-expressions @named-keys @focus
  Scenario: Tab moves focus to the next control
    * , click the "Keyboard Input" Textbox
    * , press "TAB" in the "Keyboard Input" Textbox
    * , ensure "Key Down Codes: Tab" Text is displayed
    * , ensure "Focus Target: Keyboard Tab Target" Text is displayed
