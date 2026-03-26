package com.sipvideochat.media;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Uploads media files to a PC relay service so both devices can access the same URL.
 */
public final class PcMediaRelayClient {
    public static final int DEFAULT_PORT = 6061;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private PcMediaRelayClient() {
    }

    public static String upload(Context context, Uri uri, String fileName, String mimeType,
                                String relayHost, int relayPort) throws IOException {
        if (context == null) {
            throw new IOException("Context unavailable");
        }
        if (uri == null) {
            throw new IOException("Media uri unavailable");
        }
        if (relayHost == null || relayHost.trim().isEmpty()) {
            throw new IOException("Relay host unavailable");
        }

        String safeFileName = (fileName == null || fileName.trim().isEmpty()) ? "media.bin" : fileName.trim();
        String encodedName = URLEncoder.encode(safeFileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        URL url = new URL("http://" + relayHost + ":" + relayPort + "/upload/" + encodedName);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Content-Type",
                mimeType != null && !mimeType.isEmpty() ? mimeType : "application/octet-stream");
        connection.setChunkedStreamingMode(8192);

        try (InputStream inputStream = new BufferedInputStream(openInputStream(context, uri))) {
            if (inputStream == null) {
                throw new IOException("Unable to open media uri: " + uri);
            }
            try (OutputStream outputStream = connection.getOutputStream()) {
                copy(inputStream, outputStream);
            }
        }

        int responseCode = connection.getResponseCode();
        InputStream responseStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String responseText = readText(responseStream).trim();
        connection.disconnect();

        if (responseCode >= 200 && responseCode < 300 && !responseText.isEmpty()) {
            return responseText;
        }
        throw new IOException("Relay upload failed (" + responseCode + "): " + responseText);
    }

    private static InputStream openInputStream(Context context, Uri uri) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return context.getContentResolver().openInputStream(uri);
    }

    private static void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
    }

    private static String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
