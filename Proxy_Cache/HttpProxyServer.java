import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class HttpProxyServer {

    private static String APACHE_URL;
    private static int EXPIRATION_TIME; 
    private static int PORT;
    private static final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static ServerSocket serverSocket;

    private static class CacheEntry {
        final String content;
        final String contentType;
        final long timestamp;

        CacheEntry(String content, String contentType) {
            this.content = content;
            this.contentType = contentType;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EXPIRATION_TIME;
        }
    }

    private static void loadConfig() throws IOException, JSONException {
        try (InputStream inputStream = new FileInputStream("config/config.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder jsonContent = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }

            JSONObject jsonObject = new JSONObject(jsonContent.toString());
            APACHE_URL = jsonObject.getString("url");
            PORT = jsonObject.getInt("port");
            EXPIRATION_TIME = (int) (jsonObject.getInt("tempsExpiration") * 60000);
        }
    }

    public static void main(String[] args) throws IOException {
        try {
            loadConfig();
        } catch (JSONException e) {
            System.err.println("Error loading config file: " + e.getMessage());
            return;
        }

        // Démarrer le nettoyage périodique du cache
        startCacheCleanupTask();

        serverSocket = new ServerSocket(PORT);
        System.out.println("Proxy server running on port " + PORT);

        // Start the request handling thread
        new Thread(() -> {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleRequest(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }).start();

        // Interactive menu
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\nChoose an action:");
            System.out.println("1. List cached pages");
            System.out.println("2. Remove a cache entry");
            System.out.println("3. Stop the server");
            System.out.print("Your choice: ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    listCachedPages();
                    break;
                case "2":
                    System.out.print("Enter the key to remove: ");
                    String keyToRemove = scanner.nextLine();
                    removeFromCache(keyToRemove);
                    break;
                case "3":
                    stopServer();
                    scanner.close();
                    return;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    private static void startCacheCleanupTask() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                cleanExpiredCache();
            }
        }, EXPIRATION_TIME, EXPIRATION_TIME);
    }

    private static void cleanExpiredCache() {
        Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                System.out.println("Removed expired cache entry: " + entry.getKey());
            }
        }
    }

    private static void handleRequest(Socket clientSocket) {
        try (OutputStream outputStream = clientSocket.getOutputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

            String requestLine = reader.readLine();
            if (requestLine == null) {
                sendErrorResponse(outputStream, 400, "Bad Request");
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length != 3) {
                sendErrorResponse(outputStream, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            String path = parts[1];
            String version = parts[2];

            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                String[] headerParts = headerLine.split(": ", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0].toLowerCase(), headerParts[1]);
                }
            }

            StringBuilder postData = new StringBuilder();
            if ("POST".equalsIgnoreCase(method)) {
                int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    reader.read(buffer, 0, contentLength);
                    postData.append(buffer);
                }
            }

            String cacheKey = method + "-" + path + ("POST".equalsIgnoreCase(method) ? "-" + postData.toString().hashCode() : "");

            if (cache.containsKey(cacheKey)) {
                CacheEntry cacheEntry = cache.get(cacheKey);
                if (!cacheEntry.isExpired()) {
                    sendCachedResponse(outputStream, cacheEntry, version);
                    return;
                } else {
                    // Supprimer l'entrée expirée du cache
                    cache.remove(cacheKey);
                    System.out.println("Removed expired cache entry during request: " + cacheKey);
                }
            }

            try {
                URL targetUrl = new URL(APACHE_URL + path);
                HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);

                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (!header.getKey().equalsIgnoreCase("connection") &&
                        !header.getKey().equalsIgnoreCase("proxy-connection")) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                if ("POST".equalsIgnoreCase(method) && postData.length() > 0) {
                    connection.setDoOutput(true);
                    try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream())) {
                        writer.write(postData.toString());
                        writer.flush();
                    }
                }

                int responseCode = connection.getResponseCode();
                String contentType = connection.getHeaderField("Content-Type");

                StringBuilder content = new StringBuilder();
                try (BufferedReader responseReader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = responseReader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }

                if (responseCode == 200) {
                    cache.put(cacheKey, new CacheEntry(content.toString(), contentType));
                    System.out.println("Added new cache entry: " + cacheKey);
                }
                if (responseCode == 404) {
                    sendErrorResponse(outputStream, 404, "Not Found");
                    return;
                }


                sendResponse(outputStream, responseCode, connection.getResponseMessage(), version, contentType, content.toString());

            }catch(FileNotFoundException e){
                sendErrorResponse(outputStream, 404, "page non trouvee");
            }
             catch (Exception e) {
                sendErrorResponse(outputStream, 502, "Bad Gateway");
            }

        } catch (Exception e) {
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 500, "Internal Server Error");
            } catch (IOException ex) {
                System.err.println("Error sending error response: " + ex.getMessage());
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    private static void sendCachedResponse(OutputStream outputStream, CacheEntry cacheEntry, String version) throws IOException {
        sendResponse(outputStream, 200, "OK (Cached)", version, cacheEntry.contentType, cacheEntry.content);
    }

    private static void sendResponse(OutputStream outputStream, int statusCode, String statusMessage, String version, String contentType, String content) throws IOException {
        byte[] contentBytes = content.getBytes("UTF-8");

        outputStream.write((version + " " + statusCode + " " + statusMessage + "\r\n").getBytes());
        outputStream.write(("Content-Type: " + (contentType != null ? contentType : "text/html; charset=UTF-8") + "\r\n").getBytes());
        outputStream.write(("Content-Length: " + contentBytes.length + "\r\n").getBytes());
        outputStream.write("Connection: close\r\n\r\n".getBytes());
        outputStream.write(contentBytes);
        outputStream.flush();
    }

    private static void sendErrorResponse(OutputStream outputStream, int statusCode, String message) throws IOException {
        String content = "<html><body><h1>Error " + statusCode + "</h1><p>" + message + "</p></body></html>";
        sendResponse(outputStream, statusCode, message, "HTTP/1.1", "text/html; charset=UTF-8", content);
    }

    private static void listCachedPages() {
        System.out.println("\nCached pages:");
        if (cache.isEmpty()) {
            System.out.println("No pages in cache.");
            return;
        }else{
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            long timeLeft = (EXPIRATION_TIME - (System.currentTimeMillis() - entry.getValue().timestamp)) / 1000;
            if (timeLeft>0) {
                System.out.printf("Key: %s (Expires in: %d seconds)%n", entry.getKey(), Math.max(0, timeLeft));
            }
        }
        }
    }

    private static void removeFromCache(String key) {
        if (cache.remove(key) != null) {
            System.out.println("Cache entry removed: " + key);
        } else {
            System.out.println("No cache entry found for key: " + key);
        }
    }

    private static void stopServer() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
            System.out.println("Server stopped.");
        }
    }
}