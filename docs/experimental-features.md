# Experimental features

Features that work now but are not recommended and may change.

## Position context words

`below`, `above`, `left of`, and `right of` parse and resolve by comparing element rectangles. The anchor itself is not a match. The edge comparison allows one pixel of slack. Do not treat these words as stable context words. The stable context words are `in`, `from`, `for`, `after`, and `before`.

| Word | Match |
|---|---|
| `below` | the candidate's top is at or below the anchor's bottom, and the rectangles overlap horizontally |
| `above` | the candidate's bottom is at or above the anchor's top, and the rectangles overlap horizontally |
| `left of` | the candidate's right is at or left of the anchor's left, and the rectangles overlap vertically |
| `right of` | the candidate's left is at or right of the anchor's right, and the rectangles overlap vertically |

Horizontal overlap means the candidate's left edge is left of the anchor's right edge and the candidate's right edge is right of the anchor's left edge. Vertical overlap means the candidate's top is above the anchor's bottom and the candidate's bottom is below the anchor's top.

```gherkin
* , below the "Spatial Anchor" Button, click the "Below Target" Button
* , above the "Spatial Anchor" Button, click the "Above Target" Button
* , left of the "Spatial Anchor" Button, click the "Left Target" Button
* , right of the "Spatial Anchor" Button, click the "Right Target" Button
```

## JDBC service calls

A service call whose `REQUEST.endpoint` starts with `jdbc:` runs a JDBC statement. Any other endpoint stays an HTTP call, using the same `EXECUTE SERVICE CALL` step and the same `RUN` / `CALL` / service-call scenario. This is experimental and may change.

Request keys:

| Key | Required | Meaning |
|---|---|---|
| `endpoint` | yes | JDBC URL, for example `jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1` |
| `sql` | yes | Statement text. A blank `sql` fails the step. |
| `user` | no | JDBC user. Omit both user and password to connect with the URL alone. |
| `password` | no | JDBC password. Use `<password:qaDb>` or `~MASK;` so logs stay masked. |
| `params` | no | JSON list bound to `?` placeholders. |

`CONFIGURATION` is optional and is not required for a JDBC call.

Response keys:

| Key | Meaning |
|---|---|
| `rows` | List of column-to-value objects. Numbers stay numbers. Nulls stay null. Other values are strings. |
| `rowCount` | Number of rows for a query. `0` for an update. |
| `updateCount` | Number of updated rows. `0` for a query. |
| `error` | Database error text. The step continues, the same way a failed HTTP call records its result. |

Each call opens a new connection and closes it. There is no pool.

Pickleball publishes these drivers as dependencies: PostgreSQL, Microsoft SQL Server, Oracle ojdbc, MariaDB (use this for MySQL as well), and H2. The MySQL Connector/J driver is not included.

```gherkin
* MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
  | endpoint | jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1 |
  | user | sa |
  | password | <password:qaDb> |
  | sql | select 1 as n |
* EXECUTE SERVICE CALL
```

The executable example is [`release-2-1-16.feature`](../maven-consumer-project/src/test/resources/features/release-2-1-16.feature). HTTP request and response keys stay in [Service-call scenarios](service-call-scenarios.md).
