package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.internal.ValuesSupport;
import tools.dscode.common.mappings.ValueFormatting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * One JDBC call for a service-call REQUEST whose endpoint starts with {@code jdbc:}.
 * Each call opens and closes its own connection. There is no pool.
 */
public final class JdbcServiceCall {
    private JdbcServiceCall() {
    }

    public static boolean isJdbc(String endpoint) {
        return endpoint != null && endpoint.regionMatches(true, 0, "jdbc:", 0, 5);
    }

    public static ObjectNode execute(ObjectNode request) {
        String endpoint = text(request, "endpoint");
        String sql = text(request, "sql");
        if (sql.isBlank()) {
            throw new IllegalArgumentException(
                    "The service-call REQUEST must contain sql for a JDBC endpoint"
            );
        }
        String user = credential(request.get("user"));
        String password = credential(request.get("password"));
        ArrayNode params = params(request.get("params"));
        try (Connection connection = open(endpoint, user, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            boolean hasResult = statement.execute();
            ObjectNode response = MAPPER.createObjectNode();
            if (hasResult) {
                ArrayNode rows = readRows(statement.getResultSet());
                response.set("rows", rows);
                response.put("rowCount", rows.size());
                response.put("updateCount", 0);
            } else {
                response.set("rows", MAPPER.createArrayNode());
                int updated = statement.getUpdateCount();
                response.put("rowCount", 0);
                response.put("updateCount", Math.max(updated, 0));
            }
            return response;
        } catch (SQLException exception) {
            ObjectNode response = MAPPER.createObjectNode();
            response.set("rows", MAPPER.createArrayNode());
            response.put("rowCount", 0);
            response.put("updateCount", 0);
            String message = exception.getMessage();
            response.put("error", message == null || message.isBlank()
                    ? exception.toString()
                    : message);
            return response;
        }
    }

    private static Connection open(String endpoint, String user, String password) throws SQLException {
        if (user.isBlank() && password.isBlank()) {
            return DriverManager.getConnection(endpoint);
        }
        return DriverManager.getConnection(endpoint, user, password);
    }

    private static void bind(PreparedStatement statement, ArrayNode params) throws SQLException {
        for (int index = 0; index < params.size(); index++) {
            JsonNode param = params.get(index);
            int jdbcIndex = index + 1;
            if (param == null || param.isNull()) {
                statement.setObject(jdbcIndex, null);
            } else if (param.isIntegralNumber()) {
                statement.setLong(jdbcIndex, param.longValue());
            } else if (param.isFloatingPointNumber()) {
                statement.setDouble(jdbcIndex, param.doubleValue());
            } else if (param.isBoolean()) {
                statement.setBoolean(jdbcIndex, param.booleanValue());
            } else if (param.isTextual()) {
                statement.setString(jdbcIndex, param.asText());
            } else {
                statement.setString(jdbcIndex, param.toString());
            }
        }
    }

    private static ArrayNode readRows(ResultSet resultSet) throws SQLException {
        ArrayNode rows = MAPPER.createArrayNode();
        if (resultSet == null) {
            return rows;
        }
        try (ResultSet rowsResult = resultSet) {
            ResultSetMetaData meta = rowsResult.getMetaData();
            int columns = meta.getColumnCount();
            while (rowsResult.next()) {
                ObjectNode row = MAPPER.createObjectNode();
                for (int column = 1; column <= columns; column++) {
                    row.set(meta.getColumnLabel(column), jsonValue(rowsResult.getObject(column)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static JsonNode jsonValue(Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return MAPPER.valueToTree(value);
        }
        return MAPPER.getNodeFactory().textNode(String.valueOf(value));
    }

    private static ArrayNode params(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return MAPPER.createArrayNode();
        }
        if (node.isArray()) {
            return (ArrayNode) node;
        }
        if (node.isTextual()) {
            try {
                JsonNode parsed = MAPPER.readTree(node.asText());
                if (parsed instanceof ArrayNode array) {
                    return array;
                }
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "JDBC params must be a JSON list.",
                        exception
                );
            }
        }
        throw new IllegalArgumentException("JDBC params must be a JSON list.");
    }

    private static String text(ObjectNode request, String field) {
        if (request == null) {
            return "";
        }
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String credential(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        Object restored = ValueFormatting.fromSafeJsonNode(node);
        Object plain = ValuesSupport.unmask(restored);
        if (plain == null) {
            return "";
        }
        if (plain instanceof JsonNode json) {
            return json.isValueNode() ? json.asText("") : "";
        }
        return String.valueOf(plain);
    }
}
