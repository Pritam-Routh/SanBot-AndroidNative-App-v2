package com.tripandevent.sanbotvoice.liveavatar;

import com.tripandevent.sanbotvoice.BuildConfig;

/**
 * LiveAvatar Configuration
 *
 * Centralized configuration for LiveAvatar integration.
 * Uses text-based TTS mode (FULL mode) where text deltas are sent to
 * LiveAvatar which generates audio and lip-sync together.
 */
public class LiveAvatarConfig {

    /**
     * Whether LiveAvatar feature is enabled
     */
    public static boolean isEnabled() {
        return BuildConfig.ENABLE_LIVEAVATAR;
    }

    /**
     * Whether OpenAI WebRTC is enabled.
     * When false, LiveAvatar operates in standalone FULL mode.
     */
    public static boolean isOpenAIWebRTCEnabled() {
        return BuildConfig.ENABLE_OPENAI_WEBRTC;
    }

    /**
     * Whether LiveAvatar is in standalone mode (no OpenAI).
     * In standalone mode:
     * - LiveAvatar handles STT, LLM, TTS, video, and audio
     * - Local mic audio is published to LiveKit room
     * - Avatar audio is NOT muted
     * - State is driven by HeyGen server events
     */
    public static boolean isStandaloneMode() {
        return isEnabled() && !isOpenAIWebRTCEnabled();
    }

    /**
     * Whether Orchestrated LiveKit mode is enabled.
     * In orchestrated mode:
     * - A single LiveKit room hosts User + OpenAI Agent + HeyGen Avatar
     * - Agent handles STT+LLM+TTS via LiveKit Agents framework (server-side)
     * - HeyGen provides audio-driven lip-sync video via BYOLI
     * - Agent sends robot commands via LiveKit data channel
     * - Android app is a simple room participant (publish mic, subscribe to audio+video)
     * - NO local WebRTCManager, AudioProcessor, or text delta batching needed
     */
    public static boolean isOrchestratedMode() {
        return isEnabled() && isOpenAIWebRTCEnabled() && BuildConfig.ENABLE_ORCHESTRATED_MODE;
    }

    /**
     * Participant identity prefix for the OpenAI Agent in orchestrated mode
     */
    public static final String AGENT_PARTICIPANT_PREFIX = "agent-";

    /**
     * Data channel topic for receiving robot commands from the Agent
     */
    public static final String ROBOT_COMMAND_TOPIC = "robot-commands";

    /**
     * Data channel topic for receiving transcripts from the Agent
     */
    public static final String TRANSCRIPT_TOPIC = "transcripts";

    /**
     * Default avatar ID to use
     */
    public static String getAvatarId() {
        String avatarId = BuildConfig.LIVEAVATAR_AVATAR_ID;
        return (avatarId != null && !avatarId.isEmpty()) ? avatarId : null;
    }

    /**
     * HeyGen context ID for FULL mode LLM (system prompt / knowledge base)
     */
    public static String getContextId() {
        String contextId = BuildConfig.LIVEAVATAR_CONTEXT_ID;
        return (contextId != null && !contextId.isEmpty()) ? contextId : null;
    }

    /**
     * LiveAvatar API base URL
     */
    public static String getApiUrl() {
        return "https://api.liveavatar.com";
    }

    /**
     * Session mode - uses FULL mode for text-based TTS
     *
     * FULL mode:
     * - Complete turnkey avatar interaction
     * - Built-in text-to-speech (voice_id, context_id, language)
     * - Avatar generates voice responses automatically
     * - Text commands sent via LiveKit data channel
     * - Server generates audio and lip-sync together
     */
    public static String getSessionMode() {
        return "FULL";
    }

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
