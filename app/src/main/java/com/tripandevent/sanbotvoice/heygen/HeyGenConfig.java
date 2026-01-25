package com.tripandevent.sanbotvoice.heygen;

import com.tripandevent.sanbotvoice.BuildConfig;

/**
 * HeyGen Configuration
 *
 * Centralized configuration for HeyGen LiveAvatar integration.
 */
public class HeyGenConfig {

    /**
     * Whether HeyGen avatar feature is enabled
     */
    public static boolean isEnabled() {
        return BuildConfig.ENABLE_HEYGEN_AVATAR;
    }

    /**
     * Default avatar ID to use
     */
    public static String getAvatarId() {
        String avatarId = BuildConfig.HEYGEN_AVATAR_ID;
        return (avatarId != null && !avatarId.isEmpty()) ? avatarId : null;
    }

    /**
     * Text delta batching delay in milliseconds
     * Default: 300ms
     */
    public static int getBatchDelayMs() {
        return BuildConfig.TEXT_DELTA_BATCH_DELAY_MS;
    }

    /**
     * Minimum words before flushing text buffer
     * Default: 3 words
     */
    public static int getMinWords() {
        return BuildConfig.TEXT_DELTA_MIN_WORDS;
    }

    /**
     * Maximum text buffer size before forcing flush (chars)
     * Set high enough to hold complete sentences
     * LiveAvatar works best with full sentences for smooth TTS
     */
    public static final int MAX_BUFFER_SIZE = 400;

    /**
     * Maximum time to wait before forcing buffer flush (ms)
     * Only used for subsequent flushes (first flush has its own timeout)
     * Balance: Higher = smoother speech, Lower = faster response
     */
    public static final int MAX_BUFFER_DELAY_MS = 1500;

    /**
     * Retry count for failed API calls
     */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * Retry delay between attempts (ms)
     */
    public static final int RETRY_DELAY_MS = 500;

    /**
     * Session creation timeout (ms)
     */
    public static final int SESSION_TIMEOUT_MS = 30000;

    /**
     * LiveKit video quality settings
     */
    public static class VideoQuality {
        public static final int WIDTH = 1280;
        public static final int HEIGHT = 720;
        public static final int FPS = 30;
    }

    /**
     * Robot to Avatar emotion mapping
     */
    public static class EmotionMapping {
        public static String mapRobotToAvatar(String robotFunction) {
            if (robotFunction == null) return null;

            switch (robotFunction.toLowerCase()) {
                case "robot_greet":
                case "greet":
                    return "wave";
                case "robot_nod":
                case "nod":
                    return "nod";
                case "robot_think":
                case "think":
                    return "thinking";
                case "robot_happy":
                case "happy":
                    return "happy";
                case "robot_curious":
                case "curious":
                    return "raised_eyebrow";
                case "robot_sad":
                case "sad":
                    return "sad";
                case "robot_surprised":
                case "surprised":
                    return "surprised";
                default:
                    return null;
            }
        }
    }
}
