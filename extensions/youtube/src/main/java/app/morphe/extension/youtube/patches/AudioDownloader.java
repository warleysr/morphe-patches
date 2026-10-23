/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

public final class AudioDownloader {

    private static final String TAG = "morphe-download";

    private static final String USER_AGENT =
            "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip";

    private static final int SEGMENT_COUNT = 4;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 15_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 30_000;
    private static final int MAX_STALLED_ATTEMPTS = 5;

    private static final Set<String> ACTIVE_DOWNLOADS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private AudioDownloader() {
    }

    public static void downloadAudioToDevice(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        if (!ACTIVE_DOWNLOADS.add(videoId)) {
            Log.i(TAG, "Download already running for: " + videoId);
            return;
        }

        Utils.submitOnBackgroundThread(() -> {
            try {
                Log.i(TAG, "Fetching audio streams to save to device: " + videoId);
                StreamingDataRequest.StreamData streamData = StreamingDataRequest.fetchRequestForDownload(
                        videoId, InAppDownloadsPatch.DOWNLOAD_CLIENTS, InAppDownloadsPatch.DOWNLOAD_CLIENTS.get(0)).getStream();
                if (streamData != null && streamData.streamingData() != null) {
                    startDownloadInternal(videoId, streamData.streamingData());
                } else {
                    Log.w(TAG, "Failed to get stream data for videoId: " + videoId);
                    ACTIVE_DOWNLOADS.remove(videoId);
                }
            } catch (Exception ex) {
                Log.e(TAG, "downloadAudioToDevice background fetch failure", ex);
                ACTIVE_DOWNLOADS.remove(videoId);
            }
            return null;
        });
    }

    public static void downloadAudioToDevice(String videoId, byte[] playerResponseBytes) {
        if (videoId == null || videoId.isEmpty() || playerResponseBytes == null) {
            return;
        }
        if (!ACTIVE_DOWNLOADS.add(videoId)) {
            Log.i(TAG, "Download already running for: " + videoId);
            return;
        }

        Utils.submitOnBackgroundThread(() -> {
            startDownloadInternal(videoId, playerResponseBytes);
            return null;
        });
    }

    private static void startDownloadInternal(String videoId, byte[] playerResponseBytes) {
        try {
            PlayerResponse playerResponse = PlayerResponse.parseFrom(playerResponseBytes);
            VideoDetails details = playerResponse.hasVideoDetails() ? playerResponse.getVideoDetails() : null;
            String title = (details != null && !details.getTitle().isEmpty())
                    ? details.getTitle()
                    : "audio_" + videoId;

            Format bestAudio = findBestAudioFormat(playerResponse);
            if (bestAudio == null || !bestAudio.hasUrl() || bestAudio.getUrl().isEmpty()) {
                Log.w(TAG, "No audio stream URL found for: " + videoId);
                return;
            }

            String audioUrl = bestAudio.getUrl();
            String mimeType = bestAudio.hasMimeType() ? bestAudio.getMimeType() : "audio/mp4";
            String extension = mimeType.contains("mp4") ? ".m4a" : ".webm";
            String sanitizedTitle = sanitizeFileName(title);
            String fileName = sanitizedTitle + extension;

            Context context = Utils.getContext();
            Log.i(TAG, "Starting audio download to device: " + fileName + ", mime=" + mimeType);
            Utils.showToastShort("Baixando áudio: " + title);

            long expectedLength = bestAudio.hasContentLength() ? bestAudio.getContentLength() : 0;
            if (expectedLength <= 0) {
                expectedLength = probeContentLength(audioUrl);
            }

            boolean savedViaMediaStore = false;
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    saveAudioViaMediaStore(context, fileName, title, mimeType, audioUrl, expectedLength);
                    savedViaMediaStore = true;
                } catch (Exception ex) {
                    Log.w(TAG, "Failed to save via MediaStore, trying fallback", ex);
                }
            }

            if (!savedViaMediaStore) {
                saveAudioViaFilesystem(context, fileName, title, mimeType, audioUrl, expectedLength);
            }

            Log.i(TAG, "Audio download completed successfully for: " + fileName);
            Utils.showToastShort("Áudio salvo em Música: " + title);

        } catch (Exception ex) {
            Log.e(TAG, "Parallel audio download failed for videoId: " + videoId, ex);
            Utils.showToastShort("Erro ao baixar áudio no dispositivo");
        } finally {
            ACTIVE_DOWNLOADS.remove(videoId);
        }
    }

    private static void saveAudioViaMediaStore(Context context, String fileName, String title,
                                               String mimeType, String audioUrl, long expectedLength) throws IOException {
        String cleanMime = cleanMimeType(mimeType);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.TITLE, title);
        values.put(MediaStore.Audio.Media.MIME_TYPE, cleanMime);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        try {
            resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media.DISPLAY_NAME + "=?", new String[]{fileName});
        } catch (Exception ignored) {
        }

        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Failed to create MediaStore entry for: " + fileName);
        }

        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw");
        if (pfd == null) {
            throw new IOException("Failed to open file descriptor for: " + uri);
        }

        try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
             FileChannel channel = fos.getChannel()) {

            if (expectedLength <= 0) {
                downloadUnknownLengthToChannel(audioUrl, channel);
            } else {
                downloadInParallelToChannel(audioUrl, channel, expectedLength);
            }
        } finally {
            pfd.close();
        }

        values.clear();
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private static void saveAudioViaFilesystem(Context context, String fileName, String title,
                                              String mimeType, String audioUrl, long expectedLength) throws IOException {
        File targetDir = null;
        if (context != null) {
            targetDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        }
        if (targetDir == null || (!targetDir.exists() && !targetDir.mkdirs())) {
            targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            }
        }

        File destination = new File(targetDir, fileName);
        File temporary = new File(targetDir, destination.getName() + ".part");

        if (temporary.exists() && !temporary.delete()) {
            Log.w(TAG, "Could not delete temporary partial file: " + temporary);
        }

        try (RandomAccessFile raf = new RandomAccessFile(temporary, "rw");
             FileChannel channel = raf.getChannel()) {
            if (expectedLength <= 0) {
                downloadUnknownLengthToChannel(audioUrl, channel);
            } else {
                downloadInParallelToChannel(audioUrl, channel, expectedLength);
            }
        }

        if (destination.exists() && !destination.delete()) {
            Log.w(TAG, "Could not delete existing destination: " + destination);
        }

        if (!temporary.renameTo(destination)) {
            throw new IOException("Could not rename partial download to: " + destination);
        }

        if (context != null) {
            MediaScannerConnection.scanFile(
                    context,
                    new String[]{ destination.getAbsolutePath() },
                    new String[]{ mimeType.contains("mp4") ? "audio/mp4" : mimeType },
                    (path, uri) -> Log.i(TAG, "MediaScanner indexed: " + path + " -> " + uri)
            );
        }
    }

    private static void downloadInParallelToChannel(String url, FileChannel channel, long totalLength) throws IOException {
        channel.truncate(totalLength);

        AtomicLong downloaded = new AtomicLong();
        long segmentSize = (totalLength + SEGMENT_COUNT - 1) / SEGMENT_COUNT;

        ExecutorService executor = Executors.newFixedThreadPool(SEGMENT_COUNT);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < SEGMENT_COUNT; index++) {
                long start = index * segmentSize;
                long end = Math.min(totalLength - 1, start + segmentSize - 1);
                if (start > end) continue;

                futures.add(executor.submit(() -> {
                    try {
                        downloadRangeToChannel(url, channel, start, end, downloaded);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception ex) {
            throw new IOException("Parallel download failed: " + ex.getMessage(), ex);
        } finally {
            executor.shutdown();
        }
    }

    private static void downloadRangeToChannel(String url, FileChannel channel, long start, long end,
                                               AtomicLong downloaded) throws IOException {
        long position = start;
        int failures = 0;

        while (position <= end) {
            long before = position;
            HttpURLConnection connection = openAudioConnection(url, position, end);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("Unexpected HTTP response code " + code + " for range " + position + "-" + end);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (position <= end) {
                    int toRead = (int) Math.min(buffer.length, end - position + 1);
                    int read = input.read(buffer, 0, toRead);
                    if (read < 0) break;

                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                    while (byteBuffer.hasRemaining()) {
                        position += channel.write(byteBuffer, position);
                    }
                    downloaded.addAndGet(read);
                }
            } catch (IOException ex) {
                final long pos = position;
                Log.w(TAG, "Audio range interrupted at " + pos + "; retrying", ex);
            } finally {
                connection.disconnect();
            }

            failures = position > before ? 0 : failures + 1;
            if (failures >= MAX_STALLED_ATTEMPTS) {
                throw new IOException("Download range stalled at " + position);
            }
        }
    }

    private static void downloadUnknownLengthToChannel(String url, FileChannel channel) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);

        try (InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long position = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                while (byteBuffer.hasRemaining()) {
                    position += channel.write(byteBuffer, position);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static long probeContentLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = openAudioConnection(url, 0, 0);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange != null && contentRange.contains("/")) {
                String total = contentRange.substring(contentRange.lastIndexOf('/') + 1).trim();
                return Long.parseLong(total);
            }
            return connection.getContentLengthLong();
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openAudioConnection(String url, long start, long end) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        return connection;
    }

    @Nullable
    private static Format findBestAudioFormat(PlayerResponse response) {
        if (!response.hasStreamingData()) {
            return null;
        }

        Format bestM4a = null;
        Format bestAny = null;
        int maxM4aBitrate = -1;
        int maxAnyBitrate = -1;

        for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
            String mime = format.hasMimeType() ? format.getMimeType() : "";
            if (!mime.startsWith("audio/")) {
                continue;
            }

            int bitrate = format.hasBitrate() ? format.getBitrate() : 0;
            if (mime.contains("mp4")) {
                if (bitrate > maxM4aBitrate) {
                    maxM4aBitrate = bitrate;
                    bestM4a = format;
                }
            }
            if (bitrate > maxAnyBitrate) {
                maxAnyBitrate = bitrate;
                bestAny = format;
            }
        }
        return bestM4a != null ? bestM4a : bestAny;
    }

    private static String cleanMimeType(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return "audio/mp4";
        int semicolon = mimeType.indexOf(';');
        if (semicolon != -1) {
            mimeType = mimeType.substring(0, semicolon).trim();
        }
        return mimeType;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
