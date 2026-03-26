package com.sipvideochat.ui.main;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;

import com.sipvideochat.model.Message;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared helpers for media preview thumbnails and opening the preview page.
 */
public final class MediaPreviewHelper {
    private static final String TAG = "MediaPreviewHelper";
    public static final String EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_TYPE = "extra_type";
    public static final String EXTRA_TITLE = "extra_title";

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newCachedThreadPool();
    private static final String CACHE_DIR_NAME = "remote_media_cache";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_REDIRECTS = 5;

    private MediaPreviewHelper() {
    }

    public interface SourceCallback {
        void onSuccess(String resolvedSource);
        void onFailure(Exception exception);
    }

    public static String resolvePreviewSource(Message message) {
        if (message.getLocalUri() != null && !message.getLocalUri().isEmpty()) {
            return message.getLocalUri();
        }
        return message.getMediaUrl();
    }

    public static void openPreview(Context context, Message message) {
        String source = resolvePreviewSource(message);
        if (source == null || source.isEmpty()) {
            return;
        }

        Intent intent = new Intent(context, MediaPreviewActivity.class);
        intent.putExtra(EXTRA_SOURCE, source);
        intent.putExtra(EXTRA_TYPE, message.getType() != null ? message.getType().name() : Message.MessageType.FILE.name());
        intent.putExtra(EXTRA_TITLE, message.getFileName());
        context.startActivity(intent);
    }

    public static void loadImagePreview(Context context, ImageView imageView, String source) {
        loadImageThumbnail(context, imageView, source, null, null);
    }

    public static void loadImagePreview(Context context, ImageView imageView, String source,
                                        Runnable onLoaded, Runnable onFailed) {
        loadImageThumbnail(context, imageView, source, onLoaded, onFailed);
    }

    public static void loadImageThumbnail(Context context, ImageView imageView, String source,
                                          Runnable onLoaded, Runnable onFailed) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int targetSize = Math.max(480, Math.min(metrics.widthPixels, metrics.heightPixels) / 2);
        loadBitmapIntoView(context, imageView, source, targetSize, targetSize, false, onLoaded, onFailed);
    }

    public static void loadFullImagePreview(Context context, ImageView imageView, String source,
                                            Runnable onLoaded, Runnable onFailed) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int targetWidth = Math.max(metrics.widthPixels, 1080);
        int targetHeight = Math.max(metrics.heightPixels, 1920);
        loadBitmapIntoView(context, imageView, source, targetWidth, targetHeight, false, onLoaded, onFailed);
    }

    public static void loadVideoThumbnail(Context context, ImageView imageView, String source,
                                          Runnable onLoaded, Runnable onFailed) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int targetWidth = Math.max(480, metrics.widthPixels / 2);
        int targetHeight = Math.max(320, metrics.heightPixels / 3);
        loadBitmapIntoView(context, imageView, source, targetWidth, targetHeight, true, onLoaded, onFailed);
    }

    private static void loadBitmapIntoView(Context context, ImageView imageView, String source,
                                           int reqWidth, int reqHeight, boolean videoFrame,
                                           Runnable onLoaded, Runnable onFailed) {
        imageView.setTag(source);
        imageView.setImageDrawable(null);

        if (source == null || source.isEmpty()) {
            if (onFailed != null) {
                imageView.post(onFailed);
            }
            return;
        }

        IMAGE_EXECUTOR.execute(() -> {
            Bitmap resolvedBitmap = null;
            try {
                String resolvedSource = ensureLocalPreviewSource(context, source);
                resolvedBitmap = videoFrame
                        ? extractVideoFrame(context, resolvedSource, reqWidth, reqHeight)
                        : decodeBitmap(context, resolvedSource, reqWidth, reqHeight);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load media preview: " + source, e);
            }
            Bitmap bitmap = resolvedBitmap;
            imageView.post(() -> {
                Object tag = imageView.getTag();
                if (tag == null || !source.equals(tag)) {
                    return;
                }
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                    if (onLoaded != null) {
                        onLoaded.run();
                    }
                } else if (onFailed != null) {
                    onFailed.run();
                }
            });
        });
    }

    public static void preparePreviewSource(Context context, String source, SourceCallback callback) {
        IMAGE_EXECUTOR.execute(() -> {
            try {
                String resolvedSource = ensureLocalPreviewSource(context, source);
                callbackOnSuccess(context, callback, resolvedSource);
            } catch (Exception e) {
                Log.w(TAG, "Failed to prepare preview source: " + source, e);
                callbackOnFailure(context, callback, e);
            }
        });
    }

    private static Bitmap decodeBitmap(Context context, String source, int reqWidth, int reqHeight) {
        try {
            byte[] bytes = readBytes(context, source);
            if (bytes == null || bytes.length == 0) {
                return null;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = computeInSampleSize(bounds, reqWidth, reqHeight);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap extractVideoFrame(Context context, String source, int reqWidth, int reqHeight) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Uri uri = Uri.parse(source);
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                retriever.setDataSource(uri.getPath());
            } else {
                retriever.setDataSource(context, uri);
            }
            Bitmap bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return scaleBitmap(bitmap, reqWidth, reqHeight);
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract video frame: " + source, e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap scaleBitmap(Bitmap bitmap, int reqWidth, int reqHeight) {
        if (bitmap == null || reqWidth <= 0 || reqHeight <= 0) {
            return bitmap;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= reqWidth && height <= reqHeight) {
            return bitmap;
        }

        float widthRatio = reqWidth / (float) width;
        float heightRatio = reqHeight / (float) height;
        float scale = Math.min(widthRatio, heightRatio);
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private static String ensureLocalPreviewSource(Context context, String source) throws Exception {
        if (source == null || source.isEmpty()) {
            return source;
        }
        if (!isRemoteHttpSource(source)) {
            return source;
        }

        File cacheFile = new File(getCacheDirectory(context), buildCacheFileName(source));
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return Uri.fromFile(cacheFile).toString();
        }

        File tempFile = new File(cacheFile.getAbsolutePath() + ".tmp");
        downloadRemoteSource(source, tempFile);
        if (cacheFile.exists() && !cacheFile.delete()) {
            throw new IllegalStateException("Unable to replace cache file");
        }
        if (!tempFile.renameTo(cacheFile)) {
            throw new IllegalStateException("Unable to finalize cache file");
        }
        return Uri.fromFile(cacheFile).toString();
    }

    private static byte[] readBytes(Context context, String source) throws Exception {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.connect();
            try (InputStream inputStream = connection.getInputStream()) {
                return readAll(inputStream);
            } finally {
                connection.disconnect();
            }
        }

        Uri uri = Uri.parse(source);
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            try (InputStream inputStream = new FileInputStream(new File(uri.getPath()))) {
                return readAll(inputStream);
            }
        }
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return null;
            }
            return readAll(inputStream);
        }
    }

    private static void downloadRemoteSource(String source, File destinationFile) throws Exception {
        destinationFile.getParentFile().mkdirs();
        String currentUrl = source;
        for (int redirectCount = 0; redirectCount < MAX_REDIRECTS; redirectCount++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(currentUrl).openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Connection", "close");
            connection.connect();
            try {
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    try (InputStream inputStream = connection.getInputStream();
                         OutputStream outputStream = new FileOutputStream(destinationFile)) {
                        copy(inputStream, outputStream);
                    }
                    return;
                }

                String location = connection.getHeaderField("Location");
                if (isRedirect(responseCode) && location != null && !location.isEmpty()) {
                    currentUrl = new URL(new URL(currentUrl), location).toString();
                    continue;
                }

                throw new IllegalStateException("HTTP " + responseCode + " for " + currentUrl);
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects for " + source);
    }

    private static byte[] readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        copy(inputStream, outputStream);
        return outputStream.toByteArray();
    }

    private static void copy(InputStream inputStream, OutputStream outputStream) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
    }

    private static int computeInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        while (height / inSampleSize > reqHeight || width / inSampleSize > reqWidth) {
            inSampleSize *= 2;
        }
        return Math.max(1, inSampleSize);
    }

    private static boolean isRemoteHttpSource(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private static File getCacheDirectory(Context context) {
        File dir = new File(context.getCacheDir(), CACHE_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String buildCacheFileName(String source) throws Exception {
        String extension = ".bin";
        String path = Uri.parse(source).getLastPathSegment();
        if (path != null) {
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex >= 0) {
                extension = path.substring(dotIndex);
            } else {
                String mimeType = URLConnection.guessContentTypeFromName(path);
                if (mimeType != null && mimeType.contains("/")) {
                    extension = "." + mimeType.substring(mimeType.indexOf('/') + 1);
                }
            }
        }
        return md5(source) + extension;
    }

    private static String md5(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    private static void callbackOnSuccess(Context context, SourceCallback callback, String resolvedSource) {
        if (callback == null) {
            return;
        }
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> callback.onSuccess(resolvedSource));
        } else {
            callback.onSuccess(resolvedSource);
        }
    }

    private static void callbackOnFailure(Context context, SourceCallback callback, Exception exception) {
        if (callback == null) {
            return;
        }
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> callback.onFailure(exception));
        } else {
            callback.onFailure(exception);
        }
    }
}
