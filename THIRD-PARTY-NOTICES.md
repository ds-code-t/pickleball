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

Pickleball Studio additionally bundles Spring Boot and Spring AI components in
its isolated nested executable JAR:

- org.springframework.boot:spring-boot:4.1.0
- org.springframework.boot:spring-boot-loader:4.1.0
- org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.0
- org.gradle:gradle-tooling-api:9.6.1

Pickleball Studio also bundles the Apache Maven 3.9.16 distribution runtime
as opaque build-tool resources used by its isolated Maven child process. Major
direct Apache-licensed components include:

- commons-cli:commons-cli:1.11.0
- org.apache.maven:maven-compat:3.9.16
- org.apache.maven:maven-core:3.9.16
- org.apache.maven:maven-embedder:3.9.16
- org.apache.maven:maven-slf4j-provider:3.9.16
- org.apache.maven.resolver:maven-resolver-connector-basic:1.9.27
- org.apache.maven.resolver:maven-resolver-transport-file:1.9.27
- org.apache.maven.resolver:maven-resolver-transport-http:1.9.27
- org.apache.maven.resolver:maven-resolver-transport-wagon:1.9.27
- org.apache.maven.wagon:wagon-file:3.5.3
- org.apache.maven.wagon:wagon-http:3.5.3
- org.fusesource.jansi:jansi:2.4.3
- org.slf4j:jcl-over-slf4j:1.7.36

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
- org.eclipse.sisu:org.eclipse.sisu.plexus:1.0.0

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

Pickleball Studio source navigation also embeds `io.cucumber:gherkin:35.1.0` and
`io.cucumber:messages:29.0.1` inside its isolated nested executable JAR.

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
