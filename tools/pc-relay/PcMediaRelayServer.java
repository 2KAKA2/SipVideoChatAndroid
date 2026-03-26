import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Lightweight relay server for Android media messages.
 * PUT /upload/{filename}
 * GET /media/{storedName}
 * GET /health
 */
public class PcMediaRelayServer {
    private final HttpServer server;
    private final Path storageRoot;
    private final String bindHost;

    private PcMediaRelayServer(String bindHost, int port, Path storageRoot) throws IOException {
        this.bindHost = bindHost;
        this.storageRoot = storageRoot;
        Files.createDirectories(storageRoot);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindHost), port), 0);
        server.createContext("/upload", new UploadHandler());
        server.createContext("/media", new MediaHandler());
        server.createContext("/health", exchange -> writeText(exchange, 200, "ok"));
        server.setExecutor(Executors.newCachedThreadPool());
    }

    private void start() {
        server.start();
        System.out.println("PC media relay listening on " + bindHost + ":" + server.getAddress().getPort());
        System.out.println("Storage root: " + storageRoot.toAbsolutePath());
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeText(exchange, 405, "Method Not Allowed");
                    return;
                }

                String requestPath = exchange.getRequestURI().getPath();
                String prefix = "/upload/";
                if (requestPath == null || !requestPath.startsWith(prefix) || requestPath.length() <= prefix.length()) {
                    writeText(exchange, 400, "Missing filename");
                    return;
                }

                String decodedName = URLDecoder.decode(requestPath.substring(prefix.length()), StandardCharsets.UTF_8.name());
                String sanitizedName = sanitizeFileName(decodedName);
                String extension = "";
                int dotIndex = sanitizedName.lastIndexOf('.');
                if (dotIndex >= 0) {
                    extension = sanitizedName.substring(dotIndex);
                }

                String storedName = UUID.randomUUID() + extension;
                Path destination = storageRoot.resolve(storedName);
                try (InputStream inputStream = exchange.getRequestBody()) {
                    Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println("UPLOAD " + exchange.getRemoteAddress() + " -> " + destination.getFileName());

                String hostHeader = exchange.getRequestHeaders().getFirst("Host");
                if (hostHeader == null || hostHeader.isEmpty()) {
                    hostHeader = exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort();
                }
                writeText(exchange, 200, "http://" + hostHeader + "/media/" + storedName);
            } catch (Exception e) {
                e.printStackTrace();
                writeText(exchange, 500, "Upload failed: " + e.getMessage());
            }
        }
    }

    private class MediaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeText(exchange, 405, "Method Not Allowed");
                    return;
                }

                String requestPath = exchange.getRequestURI().getPath();
                String prefix = "/media/";
                if (requestPath == null || !requestPath.startsWith(prefix) || requestPath.length() <= prefix.length()) {
                    writeText(exchange, 400, "Missing filename");
                    return;
                }

                String storedName = URLDecoder.decode(requestPath.substring(prefix.length()), StandardCharsets.UTF_8.name());
                Path target = storageRoot.resolve(storedName).normalize();
                if (!target.startsWith(storageRoot) || !Files.exists(target) || !Files.isRegularFile(target)) {
                    writeText(exchange, 404, "File not found");
                    return;
                }
                System.out.println("MEDIA " + exchange.getRemoteAddress() + " -> " + target.getFileName());

                String contentType = Files.probeContentType(target);
                if (contentType == null || contentType.isEmpty()) {
                    contentType = "application/octet-stream";
                }

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, Files.size(target));

                try (OutputStream outputStream = exchange.getResponseBody()) {
                    Files.copy(target, outputStream);
                }
            } catch (Exception e) {
                e.printStackTrace();
                writeText(exchange, 500, "Read failed: " + e.getMessage());
            }
        }
    }

    private static void writeText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static String sanitizeFileName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "media.bin";
        }
        return value.replace("\\", "_").replace("/", "_").replace(":", "_").trim();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.preferIPv4Stack", "true");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6061;
        String storage = args.length > 1 ? args[1] : "D:\\SipVideoChatMediaRelay";
        String bindHost = args.length > 2 ? args[2] : "0.0.0.0";
        new PcMediaRelayServer(bindHost, port, Path.of(storage)).start();
    }
}
