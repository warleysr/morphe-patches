/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.innertube.Offline.OfflineCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineDownloadAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineDownloadActionCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormat;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatAvailability;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatLabel;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatType;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerConfig;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.youtube.innertube.Offline.OfflineState;
import app.morphe.extension.youtube.innertube.Offline.OfflineStateAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamFormat;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamingData;
import app.morphe.extension.youtube.innertube.Offline.OfflineUpsellCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineUpsellResponse;
import app.morphe.extension.youtube.innertube.Offline.OfflineVideoAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineVideoEndpoint;
import app.morphe.extension.youtube.innertube.Offline.OfflineabilityContainer;
import app.morphe.extension.youtube.innertube.Offline.OfflineabilityRenderer;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class InAppDownloadsPatch {

    /**
     * Qualities offered when the server does not list any, highest first.
     */
    private static final OfflineFormatType[] DEFAULT_FORMAT_TYPES = {
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_1080,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD_480,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_LD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_AUDIO_ONLY,
    };

    private static final String[] DEFAULT_FORMAT_LABELS = {
            "1080p", "720p", "480p", "360p", "144p", "Audio"
    };

    private static final String AUDIO_FORMAT_LABEL = "Audio";

    /**
     * Clients that answer with stream urls, which the app can save.
     * The visionOS streams need no JavaScript challenge, whose unsolved streams the server sends slowly.
     */
    static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.VISIONOS_1_03,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_VR_DASH,
            ClientType.TV_DASH,
            ClientType.ANDROID_CREATOR,
            ClientType.TV_SIMPLY
    );

    /**
     * How long a saved video stays valid before the app asks the server about it again.
     */
    private static final int OFFLINE_POLICY_LIFETIME_SECONDS = (int) TimeUnit.DAYS.toSeconds(365);

    /**
     * Opus audio is not saved, the other audio and all video formats are.
     */
    private static final String UNSAVABLE_AUDIO_MIME_TYPE_PREFIX = "audio/webm";

    private static final String TAG = "morphe-download";

    /**
     * Injection point.
     * <p>
     * Resolves the download action of a video by saving the video,
     * instead of asking the server which answers with the Premium upsell.
     *
     * @param command Command of the download action endpoint.
     * @return A command that saves the video instead, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDownloadActionCommand(@Nullable MessageLite command) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP || command == null) {
                return null;
            }

            OfflineDownloadActionCommand downloadActionCommand =
                    OfflineDownloadActionCommand.parseFrom(command.toByteArray());
            if (!downloadActionCommand.hasDownloadAction()) {
                return null;
            }

            // Playlists are saved with their own endpoint, which is left as it is.
            OfflineDownloadAction downloadAction = downloadActionCommand.getDownloadAction();
            String videoId = downloadAction.getVideoId();
            if (videoId.isEmpty() || downloadAction.hasPlaylistId()) {
                return null;
            }

            Log.i(TAG, "Download button clicked for videoId: " + videoId);

            return saveVideoCommand(videoId, List.of()).toByteArray();
        } catch (Exception ex) {
            Log.e(TAG, "replaceDownloadActionCommand failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called before a video is saved, or before the quality picker of the video is shown.
     *
     * @param offlineability Offlineability renderer of the video.
     * @return The renderer with every quality available, or null to keep the original.
     */
    @Nullable
    public static byte[] unlockOfflineability(@Nullable Object offlineability) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
                    || !(offlineability instanceof MessageLite message)) {
                return null;
            }

            OfflineabilityRenderer renderer = OfflineabilityRenderer.parseFrom(message.toByteArray());
            boolean needsAudioUnlocked = Settings.DOWNLOAD_AUDIO_ONLY.get()
                    || !hasAudioFormat(renderer.getFormatsList());
            if (renderer.getOfflineable() && !hasLockedFormat(renderer.getFormatsList()) && !needsAudioUnlocked) {
                return null;
            }

            Logger.printDebug(() -> "Unlocking offlineability, offlineable: " + renderer.getOfflineable());
            return renderer.toBuilder()
                    .setOfflineable(true)
                    .clearNotOfflineableDialog()
                    .clearFormats()
                    .addAllFormats(unlockedFormats(renderer.getFormatsList()))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "unlockOfflineability failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the response of the offline upsell endpoint, which offers Premium instead of saving the video.
     *
     * @param command  Command of the offline upsell endpoint.
     * @param response Response of the endpoint.
     * @return A response that saves the video instead, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceOfflineUpsell(@Nullable MessageLite command, @Nullable Object response) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
                    || command == null
                    || !(response instanceof MessageLite responseMessage)) {
                return null;
            }

            OfflineUpsellResponse upsellResponse = OfflineUpsellResponse.parseFrom(responseMessage.toByteArray());
            if (!upsellResponse.getContent().hasOfflineUpsellRenderer()) {
                return null;
            }

            String videoId = OfflineUpsellCommand.parseFrom(command.toByteArray())
                    .getOfflineUpsellEndpoint().getVideoId();
            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Offline upsell command has no video id");
                return null;
            }

            Logger.printDebug(() -> "Replacing offline upsell with saving the video: " + videoId);
            return upsellResponse.toBuilder()
                    .clearContent()
                    .setCommand(saveVideoCommand(videoId,
                            upsellResponse.getContent().getOfflineUpsellRenderer().getFormatsList()))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOfflineUpsell failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called off the main thread with the player response the app saves a video with.
     * If the server declines to save the video, the streams are fetched with another client.
     *
     * @param playerResponse Player response proto of the video.
     * @return A player response that can be saved, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDeclinedOfflinePlayerResponse(@Nullable Object playerResponse) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
                    || !(playerResponse instanceof MessageLite message)) {
                return null;
            }

            OfflinePlayerResponse original = OfflinePlayerResponse.parseFrom(message.toByteArray());
            if (!original.hasOfflineState()) {
                return null;
            }
            final boolean allowed = original.getOfflineState().getActionValue()
                    == OfflineStateAction.OFFLINE_STATE_ACTION_OK_VALUE;

            String videoId = original.hasVideoDetails() ? original.getVideoDetails().getVideoId() : "";
            boolean isAudio = Settings.DOWNLOAD_AUDIO_ONLY.get();

            Log.i(TAG, "replaceDeclinedOfflinePlayerResponse: videoId=" + videoId
                    + ", allowed=" + allowed + ", isAudio=" + isAudio
                    + ", saveToDevice=" + Settings.SAVE_AUDIO_TO_DEVICE.get());

            if (allowed && original.getStreamingData().getAdaptiveFormatsCount() > 0) {
                if (Settings.SAVE_AUDIO_TO_DEVICE.get()) {
                    AudioDownloader.downloadAudioToDevice(videoId);
                }
                if (isAudio) {
                    return original.toBuilder()
                            .setStreamingData(savableStreamingData(original.getStreamingData(), true))
                            .build()
                            .toByteArray();
                }
                return null;
            }

            if (videoId.isEmpty()) {
                Log.w(TAG, "Declined offline player response has no video id");
                return null;
            }

            StreamingDataRequest.StreamData streams = StreamingDataRequest.fetchRequestForDownload(
                    videoId, DOWNLOAD_CLIENTS, DOWNLOAD_CLIENTS.get(0)).getStream();
            if (streams == null) {
                Log.w(TAG, "No streams to save the video with: " + videoId);
                return null;
            }

            if (Settings.SAVE_AUDIO_TO_DEVICE.get()) {
                AudioDownloader.downloadAudioToDevice(videoId, streams.streamingData());
            }

            // The declined response lacks the parts the app needs to select the streams to save,
            // such as the player config. The response of the other client is used instead,
            // and only the offline state token and player config are kept from the declined response.
            OfflinePlayerResponse.Builder builder = OfflinePlayerResponse.parseFrom(streams.streamingData()).toBuilder();
            builder.setStreamingData(savableStreamingData(builder.getStreamingData(), isAudio))
                    .setOfflineState(OfflineState.newBuilder()
                            .setToken(original.getOfflineState().getToken())
                            .setPolicyLifetimeSeconds(OFFLINE_POLICY_LIFETIME_SECONDS)
                            .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK));

            // Without the ExoPlayer config the app only looks at the progressive formats, which are not savable.
            // The player config of the app's own response fits the device, so it's kept if there is one.
            OfflinePlayerConfig playerConfig = original.hasPlayerConfig()
                    ? original.getPlayerConfig()
                    : builder.getPlayerConfig();
            builder.setPlayerConfig(playerConfig.toBuilder().setExoPlayerConfig(
                    playerConfig.getExoPlayerConfig().toBuilder().setUseExoPlayer(true)));

            Logger.printDebug(() -> "Replaced declined offline player response of: " + videoId
                    + " savable formats: " + countSavableFormats(builder.getStreamingData())
                    + " of: " + builder.getStreamingData().getAdaptiveFormatsCount());
            return builder.build().toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDeclinedOfflinePlayerResponse failure", ex);
            return null;
        }
    }

    /**
     * @return A command of the offline video endpoint that saves the video with the formats.
     */
    private static OfflineCommand saveVideoCommand(String videoId, List<OfflineFormat> formats) {
        OfflineabilityRenderer renderer = OfflineabilityRenderer.newBuilder()
                .setOfflineable(true)
                .addAllFormats(unlockedFormats(formats))
                .build();
        OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                .setVideoId(videoId)
                .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_ADD)
                .setOfflineability(OfflineabilityContainer.newBuilder()
                        .setOfflineabilityRenderer(renderer))
                .build();
        return OfflineCommand.newBuilder().setOfflineVideoEndpoint(endpoint).build();
    }

    private static boolean hasLockedFormat(List<OfflineFormat> formats) {
        for (OfflineFormat format : formats) {
            if (isLocked(format)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAudioFormat(List<OfflineFormat> formats) {
        for (OfflineFormat format : formats) {
            if (format.getFormatType() == OfflineFormatType.OFFLINE_FORMAT_TYPE_AUDIO_ONLY) {
                return true;
            }
        }
        return false;
    }

    private static OfflineFormat createAudioFormat() {
        return OfflineFormat.newBuilder()
                .setName(OfflineFormatLabel.newBuilder().setSimpleText(AUDIO_FORMAT_LABEL))
                .setFormatType(OfflineFormatType.OFFLINE_FORMAT_TYPE_AUDIO_ONLY)
                .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                .build();
    }

    /**
     * @return The formats with the Premium formats available, or the default formats if there are none.
     */
    private static List<OfflineFormat> unlockedFormats(List<OfflineFormat> formats) {
        if (Settings.DOWNLOAD_AUDIO_ONLY.get()) {
            return List.of(createAudioFormat());
        }

        List<OfflineFormat> unlocked = new ArrayList<>();
        if (formats.isEmpty()) {
            for (int i = 0; i < DEFAULT_FORMAT_TYPES.length; i++) {
                unlocked.add(OfflineFormat.newBuilder()
                        .setName(OfflineFormatLabel.newBuilder().setSimpleText(DEFAULT_FORMAT_LABELS[i]))
                        .setFormatType(DEFAULT_FORMAT_TYPES[i])
                        .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                        .build());
            }
            return unlocked;
        }

        boolean hasAudio = false;
        for (OfflineFormat format : formats) {
            if (format.getFormatType() == OfflineFormatType.OFFLINE_FORMAT_TYPE_AUDIO_ONLY) {
                hasAudio = true;
            }
            unlocked.add(isLocked(format)
                    ? format.toBuilder()
                            .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                            .build()
                    : format);
        }

        if (!hasAudio) {
            unlocked.add(createAudioFormat());
        }

        return unlocked;
    }

    private static boolean isLocked(OfflineFormat format) {
        return format.getAvailability() == OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_PREMIUM_LOCKED;
    }

    /**
     * Injection point.
     * <p>
     * Called after the player response model is created from the replaced player response.
     *
     * @param playerResponseModel The created model, or null if the app could not parse the replaced player response.
     */
    public static void onOfflinePlayerResponseReplaced(@Nullable Object playerResponseModel) {
        SpoofVideoStreamsPatch.setKeepStreamsOnThread(false);
        if (playerResponseModel == null) {
            Logger.printException(() -> "Could not create the player response model of the replaced player response");
        }
    }

    private static int countSavableFormats(OfflineStreamingData streamingData) {
        int count = 0;
        for (OfflineStreamFormat format : streamingData.getAdaptiveFormatsList()) {
            if (format.getOfflineEligible()) {
                count++;
            }
        }
        return count;
    }

    private static OfflineStreamingData savableStreamingData(OfflineStreamingData streamingData, boolean audioOnly) {
        OfflineStreamingData.Builder builder = streamingData.toBuilder();
        for (int i = 0, count = builder.getAdaptiveFormatsCount(); i < count; i++) {
            OfflineStreamFormat format = builder.getAdaptiveFormats(i);
            String mimeType = format.getMimeType();
            if (isSavableMimeType(mimeType)) {
                if (audioOnly) {
                    builder.setAdaptiveFormats(i, format.toBuilder().setOfflineEligible(isAudioMimeType(mimeType)));
                } else {
                    builder.setAdaptiveFormats(i, format.toBuilder().setOfflineEligible(true));
                }
            } else {
                builder.setAdaptiveFormats(i, format.toBuilder().setOfflineEligible(false));
            }
        }
        return builder.build();
    }

    private static boolean isAudioMimeType(String mimeType) {
        return mimeType.startsWith("audio/");
    }

    private static boolean isSavableMimeType(String mimeType) {
        return !mimeType.isEmpty() && !mimeType.startsWith(UNSAVABLE_AUDIO_MIME_TYPE_PREFIX);
    }
}
