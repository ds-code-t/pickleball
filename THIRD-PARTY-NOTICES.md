# Third-Party Notices for Pickleball

Pickleball is distributed under the Apache License 2.0 and includes
third-party software under additional licenses.

This distribution repackages third-party dependencies into a shaded ("fat") JAR.
Some components are modified at build time via AspectJ weaving (see "Notes on
Modifications" below).

This file lists major direct dependencies. Transitive dependencies (pulled in by
these libraries) may also apply.

---

## Apache License, Version 2.0

The following components are licensed under the Apache License 2.0:

- com.google.guava:guava:33.5.0-jre

- com.fasterxml.jackson.core:jackson-databind:2.20.0
- com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0
- com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.20.0
- com.fasterxml.jackson.datatype:jackson-datatype-guava:2.20.0

- com.aventstack:extentreports:5.1.2
- com.epam.reportportal:client-java:5.4.8

- org.seleniumhq.selenium:selenium-java:4.38.0
- org.apache.poi:poi-ooxml:5.5.1

- com.googlecode.aviator:aviator:5.4.3
- com.ibm.jsonata4java:JSONata4Java:2.6.0

License text:
http://www.apache.org/licenses/LICENSE-2.0

---

## Eclipse Public License, Version 2.0 (EPL-2.0)

The following components are licensed under EPL-2.0:

- org.junit.platform:junit-platform-suite-api:1.10.2
- org.junit.platform:junit-platform-suite-engine:1.10.2
- org.junit.jupiter:junit-jupiter-api:5.10.2

- org.aspectj:aspectjrt:1.9.24
- org.aspectj:aspectjtools:1.9.24

License text:
https://www.eclipse.org/legal/epl-2.0/

---

## MIT License

The following components are licensed under the MIT License:

- io.cucumber:cucumber-core:7.27.2
- io.cucumber:cucumber-java:7.27.2
- io.cucumber:cucumber-plugin:7.27.2
- io.cucumber:messages:29.0.1
- io.cucumber:gherkin:35.1.0
- io.cucumber:cucumber-junit-platform-engine:7.27.2

- io.github.classgraph:classgraph:4.8.184

- xpathy:xpathy:3.0.0 (bundled manually as libs/xpathy-3.0.0.jar)

License text:
https://opensource.org/licenses/MIT

---

## Notes on Modifications

This distribution contains bytecode-modified versions of the following
components, modified via AspectJ compile-time and binary weaving:

- io.cucumber:cucumber-core:7.27.2
- io.cucumber:cucumber-java:7.27.2
- io.cucumber:cucumber-plugin:7.27.2
- io.cucumber:messages:29.0.1
- io.cucumber:gherkin:35.1.0

Modifications are applied at build time using AspectJ weaving (see
src/main/aspectj). These modifications do not imply upstream approval
or endorsement.
