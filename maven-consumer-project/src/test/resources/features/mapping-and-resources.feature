Feature: Use scenario templates and shared resource data

  @all @regression @browser @local-site @mapping @templating @scenario-outline
  Scenario Outline: Scenario Outline values drive the form
    * navigate to: URL.forms
    * , enter "<name>" in the "First Name" Textbox
    * , enter "<city>" in the "Notes" Textarea
    * , select "<accountType>" in the "Account Type" Dropdown
    * , ensure "Name: <name>" Text is displayed
    * , ensure "Notes: <city>" Text is displayed
    * , ensure "Account Type: <accountType>" Text is displayed

    Examples:
      | name | city    | accountType |
      | Ava  | Phoenix | Premium     |
      | Ben  | Tempe   | Standard    |

  @all @regression @data @mapping @resources @resource-mapping @config-resources @smoke
  Scenario: Shared YAML JSON CSV and text resources resolve as templates
    * , ensure "<configs.TEST_DATA.siteName>" equals "Pickleball Test Lab"
    * , ensure "<configs.TEST_DATA.users #1.name>" equals "Ava"
    * , ensure "<configs.jsonfiles.accounts.primary.id>" equals "A-100"
    * , ensure "<configs.otherfiles.regions #2.code>" equals "SW"
    * , ensure "<configs.otherfiles.banner>" contains "resource mapping is ready"

  @all @regression @data @mapping @resources @resource-mapping @on-demand-resource
  Scenario: A resource loaded on demand supplies nested values
    * , ensure "</files/customers #1.name>" equals "Ava"
    * , ensure "</files/customers #2.city>" equals "Tempe"
    * , ensure "</files/customers #2.tier>" equals "Standard"
