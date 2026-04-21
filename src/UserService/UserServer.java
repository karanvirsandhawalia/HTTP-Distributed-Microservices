package UserService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;

/**
 * UserServer is an HTTP-based microservice responsible for user management.
 * <p>Users are stored with the following attributes:</p>
 * <ul>
 *   <li>id - unique integer identifier</li>
 *   <li>username - string username</li>
 *   <li>email - string email address</li>
 *   <li>password - string password</li>
 * </ul>
 *
 * <p><b>API Endpoint:</b> /user</p>
 *
 * <p><b>Supported Methods:</b></p>
 * <ul>
 *   <li>GET /user/{id} - Retrieve user by ID</li>
 *   <li>POST /user - Create, update, or delete user based on command field</li>
 * </ul>
 *
 *
 * @author Arshveer
 * @author Eshaan
 */
public class UserServer {
    /** The port number this server listens on */
    static Integer PORT;

    /** The IP address this server binds to */
    static String IP;

    /** Path to the config file */
    static String PATH;

    // CHANGED: Replaced in-memory HashMaps with PostgreSQL
    static String DB_URL;
    static final String DB_USER = "a2user";
    static final String DB_PASS = "a2password";

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    /**
     * Parses a JSON string into a HashMap of key-value pairs.
     * This is a simple JSON parser that handles flat JSON objects.
     *
     * @param json The JSON string to parse
     * @return HashMap containing the parsed key-value pairs, or null if invalid JSON
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
     * Parses a nested configuration JSON string into a map of service configurations.
     * Each service has its own map of configuration values (ip, port, etc.).
     *
     * @param configJson The configuration JSON string containing service configs
     * @return HashMap where keys are service names and values are config maps
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
     * Main entry point for the UserServer microservice.
     * Reads configuration from the provided config file and starts the HTTP server.
     *
     * @param args Command line arguments. args[0] should be the path to config.json
     * @throws IOException If the config file cannot be read or server fails to start
     */
    public static void main(String[] args) throws IOException {
        PATH = args[0];
        // Get port of other servers
        String jsonConfig = Files.readString(Path.of(PATH));
        HashMap<String, HashMap<String, String>> configMap = parseConfig(jsonConfig);
        PORT = Integer.parseInt(configMap.get("UserService").get("port"));
        IP = configMap.get("UserService").get("ip");

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
        server.createContext("/user", new UserServer.UserHandler());
        // CHANGED: Use thread pool for concurrent request handling
        server.setExecutor(Executors.newFixedThreadPool(100));
        server.start();
        System.out.println("Server started on port " + PORT);
    }

    /**
     * HTTP Handler for the /user endpoint.
     * Handles GET requests for user retrieval and POST requests for user management.
     * <p><b>Status Codes:</b></p>
     * <ul>
     *   <li>200 - Success</li>
     *   <li>400 - Bad request (missing/invalid fields)</li>
     *   <li>404 - User not found</li>
     *   <li>405 - Method not allowed</li>
     *   <li>409 - Conflict (user already exists)</li>
     * </ul>
     *
     * NEW API ENDPOINTS:
     *   Post: /user with command "purchase" to record a purchase for a user. Requires fields: id, product, quantity.
     *          BODY: {
     *                  "command": "purchase",
     *                  "id": 1,
     *                     "product": "2", "quantity": 3}
     *  Get: /user/purchased/{id} to retrieve purchase history for a user. Returns a JSON object of product-quantity pairs.
     *
     */
    static class UserHandler implements HttpHandler {
        /**
         * Handles incoming HTTP requests to the /user endpoint.
         *
         * @param exchange The HTTP exchange containing request and response
         * @throws IOException If an I/O error occurs
         */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                String[] tokenized_path = path.split("/");
                System.out.println("Received GET request for path: " + String.join("/", tokenized_path));

                if (tokenized_path.length != 3 && tokenized_path.length != 4) {
                    sendJsonwithCode(exchange, path, 400);
                    System.out.println("Invalid GET request path: " + path);
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

                // CHANGED: /user/purchased/{id} now queries PostgreSQL
                int userID;
                if (tokenized_path.length == 4 && "purchased".equals(tokenized_path[2])) {
                    userID = Integer.parseInt(tokenized_path[3]);
                    try (Connection conn = getConnection()) {
                        PreparedStatement checkUser = conn.prepareStatement("SELECT id FROM users WHERE id = ?");
                        checkUser.setInt(1, userID);
                        if (!checkUser.executeQuery().next()) {
                            sendJsonwithCode(exchange, "{}", 404);
                            return;
                        }
                        PreparedStatement ps = conn.prepareStatement(
                                "SELECT product_id, SUM(quantity) as total FROM purchases WHERE user_id = ? GROUP BY product_id"
                        );
                        ps.setInt(1, userID);
                        ResultSet rs = ps.executeQuery();
                        StringBuilder sb = new StringBuilder("{");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) sb.append(",");
                            sb.append("\"").append(rs.getInt("product_id")).append("\":").append(rs.getInt("total"));
                            first = false;
                        }
                        sb.append("}");
                        sendJson(exchange, sb.toString());
                    } catch (SQLException e) {
                        e.printStackTrace();
                        sendJsonwithCode(exchange, "{}", 500);
                    }
                    return;
                } else if (tokenized_path.length == 4) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                try {
                    userID = Integer.parseInt(tokenized_path[2]);
                } catch (NumberFormatException e) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                // CHANGED: GET /user/{id} now queries PostgreSQL
                try (Connection conn = getConnection()) {
                    PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
                    ps.setInt(1, userID);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        sendJsonwithCode(exchange, "{}", 404);
                        return;
                    }
                    String json = "{"
                            + "\"id\": " + rs.getInt("id") + ","
                            + "\"username\": \"" + rs.getString("username") + "\","
                            + "\"email\": \"" + rs.getString("email") + "\","
                            + "\"password\": \"" + rs.getString("password") + "\""
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

                // CHANGED: shutdown just exits — PostgreSQL persists data automatically
                if ("shutdown".equals(tokenized_path[tokenized_path.length - 1])) {
                    sendJsonwithCode(exchange, "{}", 200);
                    System.exit(0);
                } else if ("restart".equals(tokenized_path[tokenized_path.length - 1])) {
                    // CHANGED: restart is a no-op — data already in PostgreSQL
                    sendJsonwithCode(exchange, "{}", 200);
                    return;
                }

                // POST must target the collection root: /user
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

                int code = UserValidation(bodyMap, exchange);
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
         * Validates user request and routes to appropriate handler.
         * Checks for required fields and valid command type.
         *
         * @param bodyMap The parsed request body as a HashMap
         * @return HTTP status code (200 for success, 400/404/409 for errors)
         */
        // CHANGED: All DB operations now use PostgreSQL
        static int UserValidation(HashMap<String, String> bodyMap, HttpExchange exchange) throws IOException {

            String command = bodyMap.get("command");
            String idStr = bodyMap.get("id");
            String username = bodyMap.get("username");
            String email = bodyMap.get("email");
            String password = bodyMap.get("password");

            if (idStr == null) {
                return 400;
            }
            int id;
            try {
                id = Integer.parseInt(idStr);
                if (username != null) username = username.trim();
                if (email != null) email = email.trim();
                if (password != null) password = password.trim();
            } catch (NumberFormatException e) {
                return 400;
            }

            if (command == null) {
                return 400;
            }

            try (Connection conn = getConnection()) {
                switch (command) {
                    case "create":
                        if (username == null || email == null || password == null) return 400;
                        if (username.trim().isEmpty() || email.trim().isEmpty() || password.trim().isEmpty()) return 400;
                        if (email.indexOf('@') < 0) return 400;

                        PreparedStatement checkCreate = conn.prepareStatement("SELECT id FROM users WHERE id = ?");
                        checkCreate.setInt(1, id);
                        if (checkCreate.executeQuery().next()) return 409;

                        createHandler(bodyMap, id, exchange, conn);
                        break;

                    case "update":
                        PreparedStatement checkUpdate = conn.prepareStatement("SELECT id FROM users WHERE id = ?");
                        checkUpdate.setInt(1, id);
                        if (!checkUpdate.executeQuery().next()) return 404;

                        updateHandler(bodyMap, id, exchange, conn);
                        break;

                    case "delete":
                        if (username == null || email == null || password == null) return 400;

                        PreparedStatement checkDelete = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
                        checkDelete.setInt(1, id);
                        ResultSet rsDelete = checkDelete.executeQuery();
                        if (!rsDelete.next()) return 404;

                        if (!(username.equals(rsDelete.getString("username"))
                                && email.equals(rsDelete.getString("email"))
                                && hashSHA256(password).equals(rsDelete.getString("password"))))
                            return 404;

                        deleteHandler(exchange, id, conn);
                        break;

                    case "purchase":
                        PreparedStatement checkPurchase = conn.prepareStatement("SELECT id FROM users WHERE id = ?");
                        checkPurchase.setInt(1, id);
                        if (!checkPurchase.executeQuery().next()) return 404;

                        String product = bodyMap.get("product");
                        String quantityStr = bodyMap.get("quantity");
                        if (product == null || quantityStr == null) return 400;

                        int quantity;
                        try {
                            quantity = Integer.parseInt(quantityStr);
                        } catch (NumberFormatException e) {
                            return 400;
                        }

                        PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO purchases (user_id, product_id, quantity) VALUES (?, ?, ?)"
                        );
                        ps.setInt(1, id);
                        ps.setInt(2, Integer.parseInt(product));
                        ps.setInt(3, quantity);
                        ps.executeUpdate();
                        sendJson(exchange, "{}");
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
         * Creates a new user in PostgreSQL.
         *
         * @param bodyMap The request body containing user data
         * @param id The unique user ID
         */
        static void createHandler(HashMap<String, String> bodyMap, int id, HttpExchange exchange, Connection conn) throws IOException, SQLException {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, username, email, password) VALUES (?, ?, ?, ?)"
            );
            ps.setInt(1, id);
            ps.setString(2, bodyMap.get("username"));
            ps.setString(3, bodyMap.get("email"));
            ps.setString(4, hashSHA256(bodyMap.get("password")));
            ps.executeUpdate();

            String payload = "{"
                    + "\"id\": " + id + ","
                    + "\"username\": \"" + bodyMap.get("username") + "\","
                    + "\"email\": \"" + bodyMap.get("email") + "\","
                    + "\"password\": \"" + hashSHA256(bodyMap.get("password")) + "\""
                    + "}";
            sendJsonwithCode(exchange, payload, 200);
        }

        /**
         * Updates an existing user's fields in PostgreSQL.
         * Only updates fields that are present in the request.
         *
         * @param bodyMap The request body containing fields to update
         * @param id The user ID to update
         */
        static void updateHandler(HashMap<String, String> bodyMap, int id, HttpExchange exchange, Connection conn) throws IOException, SQLException {
            String username = bodyMap.get("username");
            if (username != null) {
                PreparedStatement ps = conn.prepareStatement("UPDATE users SET username = ? WHERE id = ?");
                ps.setString(1, username);
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            String email = bodyMap.get("email");
            if (email != null) {
                if (email.indexOf('@') < 0) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }
                PreparedStatement ps = conn.prepareStatement("UPDATE users SET email = ? WHERE id = ?");
                ps.setString(1, email);
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            String rawPassword = bodyMap.get("password");
            if (rawPassword != null) {
                PreparedStatement ps = conn.prepareStatement("UPDATE users SET password = ? WHERE id = ?");
                ps.setString(1, hashSHA256(rawPassword));
                ps.setInt(2, id);
                ps.executeUpdate();
            }

            PreparedStatement fetch = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
            fetch.setInt(1, id);
            ResultSet updated = fetch.executeQuery();
            updated.next();
            String payload = "{"
                    + "\"id\": " + id + ","
                    + "\"username\": \"" + updated.getString("username") + "\","
                    + "\"email\": \"" + updated.getString("email") + "\","
                    + "\"password\": \"" + updated.getString("password") + "\""
                    + "}";
            sendJsonwithCode(exchange, payload, 200);
        }

        /**
         * Deletes a user from PostgreSQL.
         *
         * @param id The user ID to delete
         */
        static void deleteHandler(HttpExchange exchange, int id, Connection conn) throws IOException, SQLException {
            PreparedStatement del = conn.prepareStatement("DELETE FROM users WHERE id = ?");
            del.setInt(1, id);
            del.executeUpdate();

            PreparedStatement delPurchases = conn.prepareStatement("DELETE FROM purchases WHERE user_id = ?");
            delPurchases.setInt(1, id);
            delPurchases.executeUpdate();

            sendJsonwithCode(exchange, "{}", 200);
        }
    }

    /**
     * Sends a JSON response to the client with HTTP 200 status.
     *
     * @param exchange The HTTP exchange
     * @param json The JSON string to send
     * @throws IOException If an I/O error occurs
     */
    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private static void sendJsonwithCode(HttpExchange exchange, String json, int code) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    public static String hashSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes());

            // Convert bytes to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString().toUpperCase();

        } catch (NoSuchAlgorithmException e) {
            // Should never happen since SHA-256 is guaranteed
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}
