package com.sipvideochat.media;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny local HTTP server for exposing media files to peers.
 */
public class LocalMediaServer {
    private static LocalMediaServer instance;

    private final Context appContext;
    private final File mediaDir;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private String localIp;
    private int port;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private LocalMediaServer(Context context, String localIp, int port) {
        this.appContext = context.getApplicationContext();
        this.localIp = localIp;
        this.port = port;
        this.mediaDir = new File(appContext.getCacheDir(), "sip_media_" + port);
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }
    }

    public static synchronized LocalMediaServer getInstance(Context context, String localIp, int port) throws IOException {
        if (instance == null || instance.port != port || !safeEquals(instance.localIp, localIp)) {
            if (instance != null) {
                instance.stop();
            }
            instance = new LocalMediaServer(context, localIp, port);
        }
        instance.start();
        return instance;
    }

    public static synchronized LocalMediaServer peek() {
        return instance;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        serverSocket = new ServerSocket(port);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "media-http-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
    }

    public String saveContentUri(Uri uri, String originalFileName) throws IOException {
        String extension = resolveExtension(originalFileName, appContext.getContentResolver().getType(uri));
        String filename = UUID.randomUUID() + extension;
        File destFile = new File(mediaDir, filename);

        try (InputStream inputStream = appContext.getContentResolver().openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(destFile)) {
            if (inputStream == null) {
                throw new IOException("Unable to open content uri: " + uri);
            }
            copy(inputStream, outputStream);
        }

        return buildUrl(filename);
    }

    public String saveFile(File sourceFile, String originalFileName) throws IOException {
        String extension = resolveExtension(originalFileName, URLConnection.guessContentTypeFromName(originalFileName));
        String filename = UUID.randomUUID() + extension;
        File destFile = new File(mediaDir, filename);

        try (InputStream inputStream = new FileInputStream(sourceFile);
             OutputStream outputStream = new FileOutputStream(destFile)) {
            copy(inputStream, outputStream);
        }

        return buildUrl(filename);
    }

    public String saveText(String content, String originalFileName, String mimeType) throws IOException {
        String extension = resolveExtension(originalFileName, mimeType);
        String filename = UUID.randomUUID() + extension;
        File destFile = new File(mediaDir, filename);
        byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);

        try (OutputStream outputStream = new FileOutputStream(destFile)) {
            outputStream.write(bytes);
        }

        return buildUrl(filename);
    }

    private String buildUrl(String filename) {
        return "http://" + localIp + ":" + port + "/media/" + filename;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running.get()) {
                    // Continue trying while server is expected to be alive.
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket ignored = socket;
             InputStream rawIn = new BufferedInputStream(socket.getInputStream());
             OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream())) {

            String requestLine = readLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String line;
            while ((line = readLine(rawIn)) != null && !line.isEmpty()) {
                // Ignore headers for this tiny server.
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equalsIgnoreCase(parts[0])) {
                writeTextResponse(rawOut, "405 Method Not Allowed", "text/plain", "Method Not Allowed");
                return;
            }

            String path = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name());
            if (!path.startsWith("/media/")) {
                writeTextResponse(rawOut, "404 Not Found", "text/plain", "Not Found");
                return;
            }

            String filename = path.substring("/media/".length());
            File file = new File(mediaDir, filename);
            if (!file.exists() || !file.isFile()) {
                writeTextResponse(rawOut, "404 Not Found", "text/plain", "File not found");
                return;
            }

            String contentType = guessContentType(filename);
            byte[] header = ("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + file.length() + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            rawOut.write(header);

            try (InputStream fileIn = new FileInputStream(file)) {
                copy(fileIn, rawOut);
            }
            rawOut.flush();
        } catch (Exception ignored) {
        }
    }

    private void writeTextResponse(OutputStream out, String status, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\r') {
                int next = in.read();
                if (next != '\n' && next != -1) {
                    builder.append((char) next);
                }
                break;
            }
            if (ch == '\n') {
                break;
            }
            builder.append((char) ch);
        }
        if (ch == -1 && builder.length() == 0) {
            return null;
        }
        return builder.toString();
    }

    private String guessContentType(String filename) {
        String contentType = URLConnection.guessContentTypeFromName(filename);
        return contentType != null ? contentType : "application/octet-stream";
    }

    private String resolveExtension(String fileName, String mimeType) {
        if (fileName != null) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex >= 0) {
                return fileName.substring(dotIndex);
            }
        }
        if (mimeType != null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (ext != null && !ext.isEmpty()) {
                return "." + ext;
            }
        }
        return ".bin";
    }

    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
