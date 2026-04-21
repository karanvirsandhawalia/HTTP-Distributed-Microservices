package ISCS;

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
 * Java-based Inter-Service Communication Server (ISCS).
 * Replaces ISCS.py for better concurrency — no GIL, true parallel threads.
 *
 * Validates and forwards requests between OrderServer and User/Product services.
 * Matches all logic from the original ISCS.py exactly.
 */
public class ISCS {

    static String USER_IP;
    static int USER_PORT;
    static String PRODUCT_IP;
    static int PRODUCT_PORT;
    static String ISCS_IP;
    static int ISCS_PORT;

    // CHANGED: Shared HttpClient with large thread pool — no GIL, true parallelism
    static HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(500))
            .build();

    static HashMap<String, String> stringToMap(String json) {
        HashMap<String, String> map = new HashMap<>();
        if (json == null) return map;
        json = json.trim();
        if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length()-1) != '}') return map;
        json = json.substring(1, json.length()-1).trim();
        if (json.isEmpty()) return map;
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().replaceAll("\"", "");
            String value = kv[1].trim().replaceAll("\"", "");
            map.put(key, value);
        }
        return map;
    }

    static HashMap<String, HashMap<String, String>> parseConfig(String configJson) {
        HashMap<String, HashMap<String, String>> result = new HashMap<>();
        configJson = configJson.trim();
        if (configJson.startsWith("{") && configJson.endsWith("}")) {
            configJson = configJson.substring(1, configJson.length() - 1).trim();
        }
        int idx = 0;
        while (idx < configJson.length()) {
            while (idx < configJson.length() && Character.isWhitespace(configJson.charAt(idx))) idx++;
            if (idx >= configJson.length()) break;
            if (configJson.charAt(idx) != '"') break;
            int keyStart = ++idx;
            while (idx < configJson.length() && configJson.charAt(idx) != '"') idx++;
            String key = configJson.substring(keyStart, idx);
            idx++;
            while (idx < configJson.length() && (configJson.charAt(idx) == ' ' || configJson.charAt(idx) == ':')) idx++;
            if (idx >= configJson.length() || configJson.charAt(idx) != '{') break;
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
            while (idx < configJson.length() && (configJson.charAt(idx) == ',' || Character.isWhitespace(configJson.charAt(idx)))) idx++;
        }
        return result;
    }

    public static void main(String[] args) throws IOException {
        String configPath = args.length > 0 ? args[0] : "config.json";
        String configJson = Files.readString(Path.of(configPath));
        HashMap<String, HashMap<String, String>> configMap = parseConfig(configJson);

        USER_IP = configMap.get("UserService").get("ip");
        USER_PORT = Integer.parseInt(configMap.get("UserService").get("port"));
        PRODUCT_IP = configMap.get("ProductService").get("ip");
        PRODUCT_PORT = Integer.parseInt(configMap.get("ProductService").get("port"));
        ISCS_IP = configMap.get("InterServiceCommunication").get("ip");
        ISCS_PORT = Integer.parseInt(configMap.get("InterServiceCommunication").get("port").replaceAll("[^0-9]", ""));

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", ISCS_PORT), 0);
        server.createContext("/user", new UserHandler());
        server.createContext("/product", new ProductHandler());
        server.createContext("/shutdown", new ShutdownHandler());
        // CHANGED: Large thread pool — each request handled in parallel, no GIL
        server.setExecutor(Executors.newFixedThreadPool(500));
        server.start();
        System.out.println("ISCS running on http://" + ISCS_IP + ":" + ISCS_PORT);
    }

    // -------------------------------------------------------------------------
    // Shutdown handler
    // -------------------------------------------------------------------------
    static class ShutdownHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, "{}");
            System.out.println("Shutting down ISCS...");
            System.exit(0);
        }
    }

    // -------------------------------------------------------------------------
    // GET/POST /user and GET /user/purchased/{id}
    // -------------------------------------------------------------------------
    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equals(method)) {
                // /user/purchased/{id}
                if (path.matches("^/user/purchased/\\d+$")) {
                    int num = Integer.parseInt(path.split("/")[3]);
                    forward(exchange, USER_IP, USER_PORT, "/user/purchased/" + num, "GET", null);
                    return;
                }

                // /user/{id}
                if (path.matches("^/user/\\d+$")) {
                    int num = Integer.parseInt(path.split("/")[2]);
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();

                    // Validate body id matches path id if body is present
                    if (!body.isEmpty()) {
                        HashMap<String, String> data = stringToMap(body);
                        if (data == null || !data.containsKey("id")) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                        try {
                            int bodyId = Integer.parseInt(data.get("id"));
                            if (bodyId != num) {
                                sendResponse(exchange, 400, "{}");
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                    }
                    forward(exchange, USER_IP, USER_PORT, "/user/" + num, "GET", body.isEmpty() ? null : body);
                    return;
                }

                sendResponse(exchange, 404, "{}");

            } else if ("POST".equals(method)) {
                // POST /user
                if (!"/user".equals(path)) {
                    sendResponse(exchange, 404, "{}");
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (body.isEmpty()) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                HashMap<String, String> data = stringToMap(body);
                if (data == null) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                String command = data.get("command");
                String idVal = data.get("id");

                if (command == null || idVal == null) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                try { Integer.parseInt(idVal); } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                switch (command) {
                    case "create":
                    case "delete":
                        if (data.get("username") == null || data.get("email") == null || data.get("password") == null) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                        break;
                    case "purchase":
                        if (data.get("product") == null || data.get("quantity") == null) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                        break;
                    case "update":
                        break;
                    default:
                        sendResponse(exchange, 400, "{}");
                        return;
                }

                forward(exchange, USER_IP, USER_PORT, "/user", "POST", body);

            } else {
                sendResponse(exchange, 405, "{}");
            }
        }
    }

    // -------------------------------------------------------------------------
    // GET/POST /product
    // -------------------------------------------------------------------------
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equals(method)) {
                // /product/{id}
                if (path.matches("^/product/\\d+$")) {
                    int num = Integer.parseInt(path.split("/")[2]);
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();

                    // Validate body id matches path id if body is present
                    if (!body.isEmpty()) {
                        HashMap<String, String> data = stringToMap(body);
                        if (data == null || !data.containsKey("id")) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                        try {
                            int bodyId = Integer.parseInt(data.get("id"));
                            if (bodyId != num) {
                                sendResponse(exchange, 400, "{}");
                                return;
                            }
                        } catch (NumberFormatException e) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                    }
                    forward(exchange, PRODUCT_IP, PRODUCT_PORT, "/product/" + num, "GET", body.isEmpty() ? null : body);
                    return;
                }

                sendResponse(exchange, 404, "{}");

            } else if ("POST".equals(method)) {
                // POST /product
                if (!"/product".equals(path)) {
                    sendResponse(exchange, 404, "{}");
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (body.isEmpty()) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                HashMap<String, String> data = stringToMap(body);
                if (data == null) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                String command = data.get("command");
                String idVal = data.get("id");

                if (command == null || idVal == null) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                try { Integer.parseInt(idVal); } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "{}");
                    return;
                }

                switch (command) {
                    case "create":
                        if (data.get("name") == null || data.get("description") == null ||
                            data.get("price") == null || data.get("quantity") == null) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                        break;
                    case "delete":
                        if (data.get("name") == null || data.get("price") == null || data.get("quantity") == null) {
                            sendResponse(exchange, 400, "{}");
                            return;
                        }
                        break;
                    case "update":
                        break;
                    default:
                        sendResponse(exchange, 400, "{}");
                        return;
                }

                forward(exchange, PRODUCT_IP, PRODUCT_PORT, "/product", "POST", body);

            } else {
                sendResponse(exchange, 405, "{}");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper: forward request to backend service
    // -------------------------------------------------------------------------
    static void forward(HttpExchange exchange, String ip, int port, String endpoint, String method, String body) throws IOException {
        String url = "http://" + ip + ":" + port + endpoint;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");

            if (body != null && !body.isEmpty()) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            sendResponse(exchange, response.statusCode(), response.body());
        } catch (Exception e) {
            sendResponse(exchange, 500, "{}");
        }
    }

    // -------------------------------------------------------------------------
    // Helper: send JSON response
    // -------------------------------------------------------------------------
    static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
}