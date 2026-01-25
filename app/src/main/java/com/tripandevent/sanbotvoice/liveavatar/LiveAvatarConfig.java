package com.tripandevent.sanbotvoice.liveavatar;

import com.tripandevent.sanbotvoice.BuildConfig;

/**
 * LiveAvatar Configuration
 *
 * Centralized configuration for LiveAvatar integration.
 * Supports audio-based streaming for lowest latency.
 */
public class LiveAvatarConfig {

    /**
     * Whether LiveAvatar feature is enabled
     */
    public static boolean isEnabled() {
        return BuildConfig.ENABLE_LIVEAVATAR;
    }

    /**
     * Whether to use audio streaming mode (lowest latency)
     * When true: OpenAI audio deltas are sent directly to LiveAvatar
     * When false: Text deltas are used (higher latency, but more compatible)
     */
    public static boolean useAudioStreaming() {
        return BuildConfig.LIVEAVATAR_AUDIO_STREAMING;
    }

    /**
     * Default avatar ID to use
     */
    public static String getAvatarId() {
        String avatarId = BuildConfig.LIVEAVATAR_AVATAR_ID;
        return (avatarId != null && !avatarId.isEmpty()) ? avatarId : null;
    }

    /**
     * LiveAvatar API base URL
     */
    public static String getApiUrl() {
        return "https://api.liveavatar.com";
    }

    /**
     * Session mode:
     * - CUSTOM: Required for external AI (OpenAI). Allows streaming audio for lip sync via repeatAudio/agent.speak
     * - FULL: Uses LiveAvatar's internal AI and TTS (not compatible with external audio)
     */
    public static String getSessionMode() {
        return "CUSTOM";
    }

    // ============================================
    // AUDIO STREAMING CONSTANTS
    // ============================================

    /**
     * Audio sample rate (matches OpenAI output)
     */
    public static final int AUDIO_SAMPLE_RATE = 24000;

    /**
     * Bytes per sample (16-bit audio)
     */
    public static final int BYTES_PER_SAMPLE = 2;

    /**
     * Audio chunk duration in milliseconds
     * 20ms is optimal for WebRTC/VoIP
     */
    public static final int AUDIO_CHUNK_DURATION_MS = 20;

    /**
     * Bytes per audio chunk
     * 24000 Hz * 0.020s * 2 bytes = 960 bytes
     */
    public static final int BYTES_PER_CHUNK = (AUDIO_SAMPLE_RATE * AUDIO_CHUNK_DURATION_MS / 1000) * BYTES_PER_SAMPLE;

    // ============================================
    // SESSION CONSTANTS
    // ============================================

    /**
     * Keep-alive interval in milliseconds
     */
    public static final long KEEP_ALIVE_INTERVAL_MS = 30000;

    /**
     * Session creation timeout (ms)
     */
    public static final int SESSION_TIMEOUT_MS = 30000;

    /**
     * Maximum retry count for failed API calls
     */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * Retry delay between attempts (ms)
     */
    public static final int RETRY_DELAY_MS = 500;

    /**
     * WebSocket reconnection attempts
     */
    public static final int WEBSOCKET_RECONNECT_ATTEMPTS = 3;

    // ============================================
    // VIDEO QUALITY SETTINGS
    // ============================================

    public static class VideoQuality {
        public static final int WIDTH = 1280;
        public static final int HEIGHT = 720;
        public static final int FPS = 30;
    }

    // ============================================
    // CONNECTION QUALITY THRESHOLDS
    // ============================================

    /**
     * MOS (Mean Opinion Score) threshold for good quality
     * MOS >= 3 is considered good, < 3 is bad
     */
    public static final float MOS_GOOD_THRESHOLD = 3.0f;

    /**
     * Quality polling interval in milliseconds
     */
    public static final int QUALITY_POLL_INTERVAL_MS = 3000;
}
