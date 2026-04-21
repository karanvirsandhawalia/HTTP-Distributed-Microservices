package OrderService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.Executors;

/**
 * OrderServer is an HTTP-based microservice responsible for managing orders
 * and proxying requests to the User and Product services.
 *
 * <p>Exposed endpoints include:</p>
 * <ul>
 *   <li>POST /order       - create an order (collection root)</li>
 *   <li>GET  /user/{id}    - proxied lookup of a user</li>
 *   <li>GET  /product/{id} - proxied lookup of a product</li>
 * </ul>
 */
public class OrderServer {
    static Integer PORT;
    static String IP;

    static Integer ISCS_PORT;
    static String ISCS_IP;
    static String PATH;

    static Integer USER_PORT;
    static String USER_IP;
    static Integer PRODUCT_PORT;
    static String PRODUCT_IP;

    // CHANGED: Shared HttpClient for all requests (better performance)
    static HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(200))
            .build();

    static HashMap<String, String> stringToMap(String json){

        /**
         * Parse a flat JSON object string into a map of keys to values.
         * This minimal parser supports the simple input format used in the assignment
         * (flat objects with string/number values, no nested structures).
         *
         * @param json JSON object text
         * @return map of keys to values, or null if the input is not a valid flat object
         */

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
     * Parse a nested configuration JSON string into a map of service configurations.
     * <p>This parser is minimal and only supports the simple config format used by
     * the assignment (flat string values, no nested arrays beyond one-level objects).
     *
     * @param configJson configuration JSON string
     * @return map where keys are service names and values are maps of configuration keys
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
            while (idx < configJson.length() && (configJson.charAt(idx) == ',' || Character.isWhitespace(configJson.charAt(idx)))) idx++;
        }
        return result;
    }

    /**
     * Entry point for OrderServer. Reads configuration and starts the HTTP server.
     *
     * @param args args[0] should be the path to the config JSON file
     * @throws IOException if the config file cannot be read or the server fails to start
     */
    public static void main(String[] args) throws IOException {

        // get port of other servers
        PATH = args[0];
        String jsonConfig = Files.readString(Path.of(PATH));
        HashMap<String, HashMap<String, String>> configMap = parseConfig(jsonConfig);
        PORT = Integer.parseInt(configMap.get("OrderService").get("port"));
        IP = configMap.get("OrderService").get("ip");

        ISCS_PORT = Integer.parseInt(configMap.get("InterServiceCommunication").get("port"));
        ISCS_IP = configMap.get("InterServiceCommunication").get("ip");

        // Extract endpoints for direct communication (bypass ISCS for system commands)
        USER_PORT = Integer.parseInt(configMap.get("UserService").get("port"));
        USER_IP = configMap.get("UserService").get("ip");
        PRODUCT_PORT = Integer.parseInt(configMap.get("ProductService").get("port"));
        PRODUCT_IP = configMap.get("ProductService").get("ip");

        HttpServer server = HttpServer.create(new InetSocketAddress(IP, PORT), 0);

        server.createContext("/order", new OrderHandler());
        server.createContext("/user", new UserHandler());
        server.createContext("/product", new ProductHandler());
        server.createContext("/shutdown", new ShutdownHandler());
        server.createContext("/restart", new RestartHandler());

        // CHANGED: Use thread pool instead of null executor for concurrency
        server.setExecutor(Executors.newFixedThreadPool(200));
        server.start();
        System.out.println("Server started on port " + PORT);

    }

    /**
     * Handler to bypass ISCS and directly shutdown User and Product services.
     */
    static class ShutdownHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                // Send direct requests to services
                sendRequest(USER_IP, USER_PORT, "/user/shutdown", "POST", "{}");
                sendRequest(PRODUCT_IP, PRODUCT_PORT, "/product/shutdown", "POST", "{}");
                sendRequest(ISCS_IP, ISCS_PORT, "/shutdown", "POST", "{}");
                sendJsonwithCode(exchange, "{}", 200);
                System.exit(0);
            } else {
                sendJsonwithCode(exchange, "{}", 405);
            }
        }
    }

    /**
     * Handler to bypass ISCS and directly restore User and Product services.
     */
    static class RestartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                // Send direct requests to services
                sendRequest(USER_IP, USER_PORT, "/user/restart", "POST", "{}");
                sendRequest(PRODUCT_IP, PRODUCT_PORT, "/product/restart", "POST", "{}");
                sendJsonwithCode(exchange, "{}", 200);
            } else {
                sendJsonwithCode(exchange, "{}", 405);
            }
        }
    }

    /**
     * HTTP handler for /order requests. Validates path shape and processes orders.
     */
    /**
     * Handler for the /order endpoint. Validates order payloads and coordinates
     * calls to User and Product services via the ISCS.
     */
    static class OrderHandler implements HttpHandler {
        @Override
       public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String[] tokenized_path = path.split("/");

            if ("POST".equals(exchange.getRequestMethod())) {
                if (tokenized_path.length != 2) {
                    sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", 400);
                    return;
                }
                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

                HashMap<String, String> bodyMap = stringToMap(body);
                if (bodyMap == null) {
                    sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", 400);
                    exchange.close();
                    return;
                }

                int code = orderValidation(bodyMap);
                if (code == 400) {
                    sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", 400);
                    exchange.close();
                    return;
                }

                // Build both JSON bodies before firing requests
                String userJson = "{\"id\":" + bodyMap.get("user_id") + "}";
                String productJson = "{\"id\":" + bodyMap.get("product_id") + "}";

                // Fire both requests in parallel
                CompletableFuture<HttpResponse<String>> userFuture = httpClient.sendAsync(
                    buildRequest(ISCS_IP, ISCS_PORT, "/user/" + bodyMap.get("user_id"), "GET", userJson),
                    HttpResponse.BodyHandlers.ofString()
                );
                CompletableFuture<HttpResponse<String>> productFuture = httpClient.sendAsync(
                    buildRequest(ISCS_IP, ISCS_PORT, "/product/" + bodyMap.get("product_id"), "GET", productJson),
                    HttpResponse.BodyHandlers.ofString()
                );

                // Collect both results (both were already in flight)
                HttpResponse<String> userResp;
                HttpResponse<String> productResp;
                try {
                    userResp = userFuture.get();
                    productResp = productFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", 500);
                    return;
                }

                // Check if valid user and product
                if (userResp.statusCode() != 200 || productResp.statusCode() != 200) {
                    sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", 404);
                    return;
                }

                HashMap<String, String> productBody = stringToMap(productResp.body());

                // Validate quantity
                int requestedQuantity = Integer.parseInt(bodyMap.get("quantity"));
                int currentQuantity = Integer.parseInt(productBody.get("quantity"));
                if (currentQuantity < requestedQuantity) {
                    sendJsonwithCode(exchange, "{\"status\": \"Exceeded quantity limit\"}", 400);
                    return;
                }

                // Update the quantity
                productBody.put("quantity", String.valueOf(currentQuantity - requestedQuantity));
                productBody.put("command", "update");

                String updateJson = "{"
                        + "\"command\":\"update\","
                        + "\"id\":\"" + productBody.get("id") + "\","
                        + "\"quantity\":\"" + (currentQuantity - requestedQuantity) + "\""
                        + "}";

                HashMap<String, Object> result = sendRequest(ISCS_IP, ISCS_PORT, "/product", "POST", updateJson);
                code = Integer.parseInt((String) result.get("status"));

                if (code == 200) {
                    String storePurchase = "{" +
                            "\"command\":\"purchase\"," +
                            "\"id\":\"" + bodyMap.get("user_id") + "\"," +
                            "\"product\":\"" + bodyMap.get("product_id") + "\"," +
                            "\"quantity\":\"" + bodyMap.get("quantity") + "\"" +
                            "}";
                    sendRequest(ISCS_IP, ISCS_PORT, "/user", "POST", storePurchase);
                    String updateJson1 = "{"
                            + "\"product_id\": " + bodyMap.get("product_id") + ","
                            + "\"user_id\": " + bodyMap.get("user_id") + ","
                            + "\"quantity\": " + bodyMap.get("quantity") + ","
                            + "\"status\":\"Success\""
                            + "}";
                    sendJsonwithCode(exchange, updateJson1, 200);
                    return;
                }

                // Handle non-200 from product update
                sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", code);
            } else {
                sendJsonwithCode(exchange, "{\"status\": \"Invalid Request\"}", 405);
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
            }
        }

        /**
         * Validate an incoming order payload for required fields and types.
         *
         * @param bodyMap parsed request body
         * @return HTTP status code (200 for valid, 400 for bad requests)
         */
        static int orderValidation(HashMap<String, String> bodyMap) {

            // check if any parameters missing
            if (bodyMap.get("command") == null || bodyMap.get("user_id") == null || bodyMap.get("product_id") == null || bodyMap.get("quantity") == null) {
                return 400;
            }

            // check if the ids and quantity are integers and not empty
            try {
                Integer.parseInt(bodyMap.get("user_id"));
                Integer.parseInt(bodyMap.get("product_id"));
                Integer.parseInt(bodyMap.get("quantity"));
            } catch (NumberFormatException e) {
                return 400;
            }

            if (Integer.parseInt(bodyMap.get("quantity")) < 0) {
                return 400;
            }

            // check if the command is correct
            if (!(bodyMap.get("command").equals("place order"))) {
                return 400;
            }

            return 200;
        }
    }

    /**
     * Proxy handler used by OrderServer to forward and validate /user requests.
     * This handler verifies path shape and forwards requests to ISCS.
     */
    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String[] tokenized_path = path.split("/");

            if ("POST".equals(exchange.getRequestMethod())) {
                // POST must target collection root: /user
                if (tokenized_path.length != 2) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                HashMap<String, String> bodyMap = stringToMap(body);
                if (bodyMap == null) {
                    sendJsonwithCode(exchange, "{}", 400);
                }
                int code = UserValidation(bodyMap, body, exchange);
                if (code != 200) {
                    sendJsonwithCode(exchange, "{}", code);
                    return;
                }
            } else if ("GET".equals(exchange.getRequestMethod())) {
                // GET must be /user/{id} exactly
                if (tokenized_path.length != 3 && tokenized_path.length != 4) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                int userID;

                if (tokenized_path.length == 4 && tokenized_path[2].equals("purchased")) {
                    System.out.println("Received request for user purchase history with token: " + tokenized_path[3]);
                    HashMap<String, Object> result = sendRequest(ISCS_IP, ISCS_PORT, "/user/purchased/" + tokenized_path[3], "GET", null);
                    int code = Integer.parseInt((String) result.get("status"));
                    sendJson(exchange, (String) result.get("body"), code);
                    return;
                }

                try {
                    userID = Integer.parseInt(tokenized_path[2]);
                } catch (NumberFormatException e) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }
                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );
                HashMap<String, Object> result = sendRequest(ISCS_IP, ISCS_PORT, "/user/" + userID, "GET", body);
                int code = Integer.parseInt((String) result.get("status"));
                sendJson(exchange, (String) result.get("body"), code);
                return;
            }

        }

        /**
         * Validate a user management payload (create/update/delete) used when proxying
         * requests through the Order service.
         *
         * @param bodyMap parsed request body
         * @param body original request body string
         * @param exchange HttpExchange used by handlers
         * @return HTTP status code (200 on success, otherwise an error status)
         * @throws IOException on I/O errors when delegating
         */
        static int UserValidation(HashMap<String, String> bodyMap, String body, HttpExchange exchange) throws IOException {

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
                if (username != null) {
                    username = username.trim();
                }
                if (email != null) {
                    email = email.trim();
                }
                if (password != null) {
                    password = password.trim();
                }
            } catch (NumberFormatException e) {
                return 400;
            }

            if (command == null) {
                return 400;
            }

            switch (command) {
                case "create", "delete":
                    if (username == null || email == null || password == null) {
                        return 400;
                    }
                    return handler(body, exchange);

                case "update":
                    return handler(body, exchange);

                default:
                    return 400;
            }
        }

        static int handler(String body, HttpExchange exchange) throws IOException {
            HashMap<String, Object> result = sendRequest(ISCS_IP, ISCS_PORT, "/user", "POST", body);
            int code = Integer.parseInt((String) result.get("status"));
            sendJsonwithCode(exchange, (String) result.get("body"), code);
            return code;
        }
    }

    /**
     * Proxy handler used by OrderServer to forward and validate /product requests.
     */
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String[] tokenized_path = path.split("/");

            if ("POST".equals(exchange.getRequestMethod())) {
                // POST must target collection root: /product
                if (tokenized_path.length != 2) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                HashMap<String, String> bodyMap = stringToMap(body);
                if (bodyMap == null) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }
                int code = ProdValidation(bodyMap, body, exchange);
                if (code != 200) {
                    sendJsonwithCode(exchange, "{}", code);
                    return;
                }
            } else if ("GET".equals(exchange.getRequestMethod())) {
                // GET must be /product/{id} exactly
                if (tokenized_path.length != 3) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }

                int prodID;
                try {
                    prodID = Integer.parseInt(tokenized_path[2]);
                } catch (NumberFormatException e) {
                    sendJsonwithCode(exchange, "{}", 400);
                    return;
                }
                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );
                HashMap<String, Object> result = sendRequest(ISCS_IP, ISCS_PORT, "/product/" + prodID, "GET", body);
                int code = Integer.parseInt((String) result.get("status"));
                sendJson(exchange, (String) result.get("body"), code);
                return;
            }
        }

        /**
         * Validate a product command payload when proxying through the Order service.
         *
         * @param bodyMap parsed request body
         * @param body original request body
         * @param exchange HttpExchange used by handlers
         * @return HTTP status code (200 on success)
         * @throws IOException on I/O errors when delegating
         */
        static int ProdValidation(HashMap<String, String> bodyMap, String body, HttpExchange exchange) throws IOException {

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
                    Double.parseDouble(priceStr);
                }
                if (quantityStr != null) {
                    Integer.parseInt(quantityStr);
                }
                if (productNameStr != null) {
                    productNameStr = productNameStr.trim();
                }
                if (descriptionString != null) {
                    descriptionString = descriptionString.trim();
                }
            } catch (NumberFormatException e) {
                return 400;
            }

            if (command == null) {
                return 400;
            }
            switch (command) {
                case "create", "delete":
                    if (priceStr == null || quantityStr == null || productNameStr == null) {
                        return 400;
                    }
                    if (command.equals("create") && descriptionString == null) {
                        return 400;
                    }
                    return handler(body, exchange);

                case "update":
                    return handler(body, exchange);

                default:
                    return 400;
            }
        }

        static int handler(String body, HttpExchange exchange) throws IOException {
            HashMap<String, Object> result = sendRequest(ISCS_IP, ISCS_PORT, "/product", "POST", body);
            int code = Integer.parseInt((String) result.get("status"));
            sendJsonwithCode(exchange, (String) result.get("body"), code);
            return code;
        }
    }

    /**
     * Send a JSON response with HTTP 200 and Content-Type application/json.
     *
     * @param exchange the HttpExchange to write to
     * @param json JSON payload string
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
     * Send a JSON response with a specific HTTP status code.
     *
     * @param exchange the HttpExchange to write to
     * @param json JSON payload string
     * @param status HTTP status code to send
     * @throws IOException on write errors
     */
    private static void sendJson(HttpExchange exchange, String json, int status) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    /**
     * Send JSON with a specific response code (helper wrapper).
     *
     * @param exchange the HttpExchange to write to
     * @param json JSON payload
     * @param code HTTP status code
     * @throws IOException on write errors
     */
    private static void sendJsonwithCode(HttpExchange exchange, String json, int code) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    public static HashMap<String, Object> sendRequest(String ip, int port, String endpoint,
                                                      String method, String jsonBody) {
        /**
         * Send an HTTP request to another service and return the response as a map.
         * <p>The returned map contains keys "status" (HTTP status code as string)
         * and "body" (response body as string). Returns null on failure.</p>
         *
         * @param ip target service IP address
         * @param port target service port
         * @param endpoint request path on target (must begin with '/')
         * @param method HTTP method to use (e.g., "GET", "POST")
         * @param jsonBody optional request body (may be null or empty)
         * @return map with keys "status" and "body", or null on error
         */
        try {
            String urlStr = "http://" + ip + ":" + port + endpoint;

            // CHANGED: Use shared httpClient instead of creating a new one per request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");

            if (jsonBody != null && !jsonBody.isEmpty()) {
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String body = response.body();

            HashMap<String, Object> retVal = new HashMap<String, Object>();
            retVal.put("body", body);
            retVal.put("status", "" + status);
            return retVal;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static HttpRequest buildRequest(String ip, int port, String endpoint, String method, String jsonBody) {
        String urlStr = "http://" + ip + ":" + port + endpoint;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (jsonBody != null && !jsonBody.isEmpty()) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }
}
