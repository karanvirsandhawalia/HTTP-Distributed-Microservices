package ProductService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;

/**
 * ProductServer is an HTTP microservice that manages products.
 *
 * <p>Exposed endpoints:</p>
 * <ul>
 *   <li>GET /product/{id} - retrieve product by id</li>
 *   <li>POST /product     - create, update, or delete products using a JSON command payload</li>
 * </ul>
 *
 * <p>Products are stored in PostgreSQL.</p>
 */
public class ProductServer {
    static Integer PORT;
    static String IP;
    static String PATH;

    // CHANGED: Replaced in-memory HashMap with PostgreSQL
    static String DB_URL;
    static final String DB_USER = "a2user";
    static final String DB_PASS = "a2password";

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    /**
     * Parse a flat JSON object string into a map of key->value strings.
     * This parser is intentionally minimal and only supports simple, flat
     * JSON objects used by the assignment (no nested objects or arrays).
     *
     * @param json JSON object text
     * @return map of string keys to string values, or null if the input is invalid
     */
    static HashMap<String, String> stringToMap(String json) {

        HashMap<String, String> mapOutput = new HashMap<>();

        json = json.trim();
        if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
            return null;
        }

        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) {
            return null;
        }

        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue; // skip malformed
            String key = kv[0].trim();
            String value = kv[1].trim();

            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            mapOutput.put(key, value);
        }

        return mapOutput;
    }

    /**
     * Parse a nested configuration JSON string into a map of service names to
     * configuration maps. Uses the simple {@link #stringToMap} to parse inner objects.
     *
     * @param configJson configuration JSON text
     * @return map where keys are service names and values are config maps
     */
    static HashMap<String, HashMap<String, String>> parseConfig(String configJson) {
        HashMap<String, HashMap<String, String>> result = new HashMap<>();
        configJson = configJson.trim();
        if (configJson.startsWith("{") && configJson.endsWith("}")) {
            configJson = configJson.substring(1, configJson.length() - 1).trim();
        }
        int idx = 0;
        while (idx < configJson.length()) {
            // Find key
            while (idx < configJson.length() && Character.isWhitespace(configJson.charAt(idx))) idx++;
            if (idx >= configJson.length()) break;
            if (configJson.charAt(idx) != '"') break;
            int keyStart = ++idx;
            while (idx < configJson.length() && configJson.charAt(idx) != '"') idx++;
            String key = configJson.substring(keyStart, idx);
            idx++; // skip closing quote
            while (idx < configJson.length() && (configJson.charAt(idx) == ' ' || configJson.charAt(idx) == ':')) idx++;
            // Now at value (should be '{')
            if (configJson.charAt(idx) != '{') break;
            int braceCount = 1;
            int valueStart = idx;
            idx++;
            while (idx < configJson.length() && braceCount > 0) {
                if (configJson.charAt(idx) == '{') braceCount++;
                else if (configJson.charAt(idx) == '}') braceCount--;
                idx++;
            }
            String valueBlock = configJson.substring(valueStart, idx);
            result.put(key, stringToMap(valueBlock));
            // Skip any trailing commas or whitespace
            while (idx < configJson.length() && (configJson.charAt(idx) == ',' || Character.isWhitespace(configJson.charAt(idx))))
                idx++;
        }
        return result;
    }

    /**
     * Main entrypoint for the ProductServer. Reads configuration and starts the HTTP server.
     *
     * @param args command line arguments; args[0] must be the path to the config JSON
     * @throws IOException when configuration file cannot be read or server fails to start
     */
    public static void main(String[] args) throws IOException {
        PATH = args[0];
        String jsonConfig = Files.readString(Path.of(PATH));
        HashMap<String, HashMap<String, String>> configMap = parseConfig(jsonConfig);
        PORT = Integer.parseInt(configMap.get("ProductService").get("port"));
        IP = configMap.get("ProductService").get("ip");

        // CHANGED: Read DB config and initialize JDBC driver
        String dbIp = configMap.get("Database").get("ip");
        String dbPort = configMap.get("Database").get("port");
        DB_URL = "jdbc:postgresql://" + dbIp + ":" + dbPort + "/a2db";
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("PostgreSQL JDBC Driver not found: " + e.getMessage());
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(IP, PORT), 0);
        server.createContext("/product", new ProductServer.ProductHandler());
        // CHANGED: Use thread pool for concurrent request handling
        server.setExecutor(Executors.newFixedThreadPool(100));
        server.start();
        System.out.println("Server started on port " + PORT);
    }

    /**
     * HTTP handler for the /product endpoint. Supports GET and POST operations.
     */
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String[] tokenized_path = path.split("/");

                // require exactly one numeric segment after /product
                if (tokenized_path.length != 3) {
                    sendJsonwithCode(exchange, path, 400);
                    return;
                }

                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

                if (!body.trim().isEmpty()) {
                    String trimmed = body.trim();
                    HashMap<String, String> bodyMap = stringToMap(trimmed);
                    if (bodyMap != null) {

                        String idInBody = bodyMap.get("id");
                        if (idInBody == null) {
                            sendJsonwithCode(exchange, "{}", 400);
                            return;
                        }
                        try {
                            int idBody = Integer.parseInt(idInBody);
                            int idPath = Integer.parseInt(tokenized_path[2]);
                            if (idBody != idPath) {
                                sendJsonwithCode(exchange, "{}", 400);
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sendJsonwithCode(exchange, "{}", 400);
                            return;
                        }
                    }
                }

                int prodID;
                try {
                    prodID = Integer.parseInt(tokenized_path[2]);
                } catch (NumberFormatException e) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                // CHANGED: GET /product/{id} now queries PostgreSQL
                try (Connection conn = getConnection()) {
                    PreparedStatement ps = conn.prepareStatement("SELECT * FROM products WHERE id = ?");
                    ps.setInt(1, prodID);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        sendJsonwithCode(exchange, "{}", 404);
                        return;
                    }
                    String json = "{"
                            + "\"id\": " + rs.getInt("id") + ","
                            + "\"name\": \"" + rs.getString("name") + "\","
                            + "\"description\": \"" + rs.getString("description") + "\","
                            + "\"price\": " + rs.getDouble("price") + ","
                            + "\"quantity\": " + rs.getInt("quantity")
                            + "}";
                    sendJson(exchange, json);
                } catch (SQLException e) {
                    e.printStackTrace();
                    sendJsonwithCode(exchange, "{}", 500);
                }
            }

            else if ("POST".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String[] tokenized_path = path.split("/");

                // Check for shutdown or restart commands first
                if ("shutdown".equals(tokenized_path[tokenized_path.length - 1])) {
                    // CHANGED: shutdown just exits — PostgreSQL persists data automatically
                    sendJsonwithCode(exchange, "{}", 200);
                    System.exit(0);
                } else if ("restart".equals(tokenized_path[tokenized_path.length - 1])) {
                    // CHANGED: restart is a no-op — data already in PostgreSQL
                    sendJsonwithCode(exchange, "{}", 200);
                    return;
                }

                if (tokenized_path.length != 2) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                // Parse the input string
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                HashMap<String, String> bodyMap = stringToMap(body);
                if (bodyMap == null) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                int code = ProdValidation(bodyMap, exchange);
                if (code != 200) {
                    sendJsonwithCode(exchange, "{}", code);
                    return;
                }
            }
            else {
                sendJsonwithCode(exchange, "{}", 400);
            }
        }

        /**
         * Validate a product JSON command payload.
         *
         * @param bodyMap parsed flat JSON body
         * @param exchange HttpExchange (used for handlers to send responses)
         * @return HTTP status code indicating validation result (200 on success)
         * @throws IOException on I/O errors
         */
        // CHANGED: All DB operations now use PostgreSQL
        static int ProdValidation(HashMap<String, String> bodyMap, HttpExchange exchange) throws IOException {

            String command = bodyMap.get("command");
            String idString = bodyMap.get("id");
            String descriptionString = bodyMap.get("description");
            String priceStr = bodyMap.get("price");
            String quantityStr = bodyMap.get("quantity");
            String productNameStr = bodyMap.get("name");

            if (idString == null) {
                return 400;
            }
            int id;
            try {
                id = Integer.parseInt(idString);
                if (priceStr != null) {
                    double v = Double.parseDouble(priceStr);
                    if (v < 0) return 400;
                }
                if (quantityStr != null) {
                    int v = Integer.parseInt(quantityStr);
                    if (v < 1) return 400;
                }
                if (productNameStr != null) {
                    productNameStr = productNameStr.trim();
                    if (productNameStr.isEmpty()) return 400;
                }
                if (descriptionString != null) {
                    descriptionString = descriptionString.trim();
                    if (descriptionString.isEmpty()) return 400;
                }
            } catch (NumberFormatException e) {
                return 400;
            }

            if (command == null) {
                return 400;
            }

            try (Connection conn = getConnection()) {
                switch (command) {
                    case "create":
                        if (priceStr == null || quantityStr == null || productNameStr == null || descriptionString == null) {
                            return 400;
                        }
                        PreparedStatement checkCreate = conn.prepareStatement("SELECT id FROM products WHERE id = ?");
                        checkCreate.setInt(1, id);
                        if (checkCreate.executeQuery().next()) return 409;

                        createHandler(bodyMap, id, exchange, conn);
                        break;

                    case "update":
                        PreparedStatement checkUpdate = conn.prepareStatement("SELECT id FROM products WHERE id = ?");
                        checkUpdate.setInt(1, id);
                        if (!checkUpdate.executeQuery().next()) return 404;

                        updateHandler(bodyMap, id, exchange, conn);
                        break;

                    case "delete":
                        PreparedStatement checkDelete = conn.prepareStatement("SELECT * FROM products WHERE id = ?");
                        checkDelete.setInt(1, id);
                        ResultSet rs = checkDelete.executeQuery();
                        if (!rs.next()) return 404;

                        String name = bodyMap.get("name");
                        String price = bodyMap.get("price");
                        String quantity = bodyMap.get("quantity");

                        if (name == null || price == null || quantity == null) return 404;

                        String storedName = rs.getString("name");
                        String storedPrice = String.format("%.2f", rs.getDouble("price"));
                        String storedQuantity = String.valueOf(rs.getInt("quantity"));

                        if (!(name.equals(storedName)
                                && String.format("%.2f", Double.parseDouble(price)).equals(storedPrice)
                                && quantity.equals(storedQuantity))) {
                            return 401;
                        }

                        deleteHandler(bodyMap, id, exchange, conn);
                        break;

                    default:
                        return 400;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return 500;
            }
            return 200;
        }

        /**
         * Create a new product in PostgreSQL and send a JSON response.
         *
         * @param bodyMap parsed request body
         * @param id product id
         * @param exchange HttpExchange used to send the response
         * @throws IOException on write errors
         */
        static void createHandler(HashMap<String, String> bodyMap, int id, HttpExchange exchange, Connection conn) throws IOException, SQLException {
            double value = Double.parseDouble(bodyMap.get("price"));
            String formatted = String.format("%.2f", value);

            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO products (id, name, description, price, quantity) VALUES (?, ?, ?, ?, ?)"
            );
            ps.setInt(1, id);
            ps.setString(2, bodyMap.get("name"));
            ps.setString(3, bodyMap.get("description"));
            ps.setDouble(4, value);
            ps.setInt(5, Integer.parseInt(bodyMap.get("quantity")));
            ps.executeUpdate();

            String payload = "{"
                    + "\"id\": " + id + ","
                    + "\"name\": \"" + bodyMap.get("name") + "\","
                    + "\"description\": \"" + bodyMap.get("description") + "\","
                    + "\"price\": " + formatted + ","
                    + "\"quantity\": " + bodyMap.get("quantity")
                    + "}";
            sendJsonwithCode(exchange, payload, 200);
        }

        /**
         * Update an existing product's fields in PostgreSQL.
         *
         * @param bodyMap parsed request body
         * @param id product id
         * @param exchange HttpExchange used to send the response
         * @throws IOException on write errors
         */
        static void updateHandler(HashMap<String, String> bodyMap, int id, HttpExchange exchange, Connection conn) throws IOException, SQLException {
            String name = bodyMap.get("name");
            if (name != null) {
                PreparedStatement ps = conn.prepareStatement("UPDATE products SET name = ? WHERE id = ?");
                ps.setString(1, name);
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            String price = bodyMap.get("price");
            if (price != null) {
                PreparedStatement ps = conn.prepareStatement("UPDATE products SET price = ? WHERE id = ?");
                ps.setDouble(1, Double.parseDouble(price));
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            String quantity = bodyMap.get("quantity");
            if (quantity != null) {
                PreparedStatement ps = conn.prepareStatement("UPDATE products SET quantity = ? WHERE id = ?");
                ps.setInt(1, Integer.parseInt(quantity));
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            String description = bodyMap.get("description");
            if (description != null) {
                PreparedStatement ps = conn.prepareStatement("UPDATE products SET description = ? WHERE id = ?");
                ps.setString(1, description);
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            PreparedStatement fetch = conn.prepareStatement("SELECT * FROM products WHERE id = ?");
            fetch.setInt(1, id);
            ResultSet updated = fetch.executeQuery();
            updated.next();
            String payload = "{"
                    + "\"id\": " + id + ","
                    + "\"name\": \"" + updated.getString("name") + "\","
                    + "\"description\": \"" + updated.getString("description") + "\","
                    + "\"price\": " + updated.getDouble("price") + ","
                    + "\"quantity\": " + updated.getInt("quantity")
                    + "}";
            sendJsonwithCode(exchange, payload, 200);
        }

        /**
         * Delete a product by id from PostgreSQL.
         *
         * @param bodyMap parsed request body
         * @param id product id
         * @param exchange HttpExchange used to send the response
         * @throws IOException on write errors
         */
        static void deleteHandler(HashMap<String, String> bodyMap, int id, HttpExchange exchange, Connection conn) throws IOException, SQLException {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM products WHERE id = ?");
            ps.setInt(1, id);
            ps.executeUpdate();
            sendJsonwithCode(exchange, "{}", 200);
        }
    }

    /**
     * Send JSON with HTTP 200 and Content-Type application/json.
     *
     * @param exchange http exchange
     * @param json JSON payload
     * @throws IOException on write errors
     */
    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    /**
     * Send JSON with a specific HTTP status code and Content-Type application/json.
     *
     * @param exchange http exchange
     * @param json JSON payload
     * @param code HTTP status code to send
     * @throws IOException on write errors
     */
    private static void sendJsonwithCode(HttpExchange exchange, String json, int code) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}
