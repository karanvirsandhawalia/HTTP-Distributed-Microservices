package LoadBalancer;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java-based round-robin load balancer for OrderServer instances.
 * Replaces LoadBalancer.py for better concurrency under high load.
 */
public class LoadBalancer {

    static String LB_IP;
    static int LB_PORT;
    static List<String> backends = new ArrayList<>();

    // CHANGED: AtomicInteger for lock-free round-robin counter
    static AtomicInteger counter = new AtomicInteger(0);

    // CHANGED: Shared HttpClient with large thread pool for concurrent forwarding
    static HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(500))
            .build();

    static String getNextBackend() {
        int idx = counter.getAndIncrement() % backends.size();
        return backends.get(idx);
    }

    static HashMap<String, String> stringToMap(String json) {
        HashMap<String, String> map = new HashMap<>();
        json = json.trim();
        if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length()-1) != '}') return null;
        json = json.substring(1, json.length()-1).trim();
        if (json.isEmpty()) return null;
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().replace("\"", "");
            String value = kv[1].trim().replace("\"", "");
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

    static List<String> parseBackends(String configJson) {
        List<String> list = new ArrayList<>();
        int backendsIdx = configJson.indexOf("\"backends\"");
        if (backendsIdx < 0) return list;
        int arrStart = configJson.indexOf('[', backendsIdx);
        int arrEnd = configJson.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return list;
        String arr = configJson.substring(arrStart + 1, arrEnd);
        int i = 0;
        while (i < arr.length()) {
            int objStart = arr.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = arr.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = arr.substring(objStart, objEnd + 1);
            // Extract ip and port manually
            String ip = null;
            String port = null;
            for (String pair : obj.replaceAll("[{}]", "").split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim().replaceAll("\"", "");
                String value = kv[1].trim().replaceAll("\"", "").trim();
                if (key.equals("ip")) ip = value;
                if (key.equals("port")) port = value.replaceAll("[^0-9]", "");
            }
            if (ip != null && port != null) {
                list.add(ip + ":" + port);
            }
            i = objEnd + 1;
        }
        return list;
    }

    public static void main(String[] args) throws IOException {
        String configPath = args.length > 0 ? args[0] : "config.json";
        String configJson = Files.readString(Path.of(configPath));


        //HARD CODED
        LB_IP = "142.1.46.25";
        LB_PORT = 8080;
        backends = parseBackends(configJson);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", LB_PORT), 0);
        server.createContext("/", new ForwardHandler());
        // CHANGED: Large thread pool to handle high concurrency
        server.setExecutor(Executors.newFixedThreadPool(500));
        server.start();

        System.out.println("Load balancer running on http://" + LB_IP + ":" + LB_PORT);
        System.out.println("Backends: " + backends);
    }

    static class ForwardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String backend = getNextBackend();
            String url = "http://" + backend + exchange.getRequestURI().toString();
            String method = exchange.getRequestMethod();

            byte[] reqBody = exchange.getRequestBody().readAllBytes();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10));

            // Copy headers except host and content-length
            exchange.getRequestHeaders().forEach((key, values) -> {
                if (!key.equalsIgnoreCase("host") && !key.equalsIgnoreCase("content-length")) {
                    for (String value : values) {
                        try { builder.header(key, value); } catch (Exception ignored) {}
                    }
                }
            });

            if (reqBody.length > 0) {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(reqBody));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            try {
                HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                byte[] respBody = response.body();

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), respBody.length);
                exchange.getResponseBody().write(respBody);
            } catch (Exception e) {
                byte[] err = "{\"error\": \"Bad Gateway\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(502, err.length);
                exchange.getResponseBody().write(err);
            } finally {
                exchange.close();
            }
        }
    }
}