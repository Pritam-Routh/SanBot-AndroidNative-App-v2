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
     * Whether to use audio streaming mode (CUSTOM mode)
     *
     * IMPORTANT LIMITATION:
     * Audio streaming (CUSTOM mode) requires audio data access from OpenAI.
     * - In WebSocket mode: OpenAI sends audio as base64 data (response.output_audio.delta)
     * - In WebRTC mode: Audio goes directly to speakers via RTP - NO DATA ACCESS
     *
     * This app uses WebRTC mode, so CUSTOM mode is NOT available.
     * Must use FULL mode (text-based TTS) instead.
     *
     * CUSTOM mode (if available) would support:
     * - Audio chunks (24kHz 16-bit PCM) sent via WebSocket as base64
     * - Format: {type: "agent.speak", event_id: "...", audio: "base64..."}
     * - End signal: {type: "agent.speak_end", event_id: "..."}
     * - Server performs lip sync rendering on the video stream
     * - LOWEST LATENCY option
     *
     * When true: Attempt CUSTOM mode (requires WebSocket mode for OpenAI)
     * When false: Use FULL mode (text-based TTS via LiveKit data channel)
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
     * Session mode (based on LiveAvatar Web SDK):
     *
     * FULL mode:
     * - Complete turnkey avatar interaction
     * - Built-in text-to-speech (voice_id, context_id, language)
     * - Avatar generates voice responses automatically
     * - Use avatar.speak_text for text responses via LiveKit data channel
     * - Higher latency due to TTS generation
     *
     * CUSTOM mode:
     * - Bring your own TTS/audio integration
     * - Stream audio directly via WebSocket with agent.speak
     * - Audio format: PCM 16-bit 24kHz, base64 encoded
     * - LOWEST LATENCY - no TTS generation needed
     * - Requires WebSocket URL from backend
     */
    public static String getSessionMode() {
        // Use CUSTOM mode when audio streaming is enabled (lowest latency)
        // Use FULL mode when using text-based TTS
        return useAudioStreaming() ? "CUSTOM" : "FULL";
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

    /**
     * LiveKit data channel topic for avatar commands
     *
     * LiveAvatar SDK uses "agent-control" for sending commands to the avatar.
     * Responses come on "agent-response" topic.
     */
    public static final String DATA_CHANNEL_TOPIC = "agent-control";

    /**
     * LiveKit data channel topic for receiving avatar responses
     */
    public static final String DATA_CHANNEL_RESPONSE_TOPIC = "agent-response";

    /**
     * HeyGen avatar participant identity in LiveKit room
     */
    public static final String AVATAR_PARTICIPANT_ID = "heygen";

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
