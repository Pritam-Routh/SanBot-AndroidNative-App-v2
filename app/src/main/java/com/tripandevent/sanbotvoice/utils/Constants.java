package com.tripandevent.sanbotvoice.utils;

import com.tripandevent.sanbotvoice.BuildConfig;

/**
 * Application-wide constants.
 * 
 * IMPORTANT: Do not hardcode any API keys here.
 * Use BuildConfig fields which are injected at build time.
 */
public final class Constants {
    
    private Constants() {
        // Prevent instantiation
    }
    
    // ============================================
    // API ENDPOINTS
    // ============================================
    // CRM Configuration
    public static final String CRM_BASE_URL = "https://crm.tripandevent.com";
    public static final String CRM_API_KEY = "YOUR_CRM_API_KEY_HERE"; // Get from CRM admin
    public static final int CRM_COMPANY_ID = 1; // Your company ID in CRM

    // Voice Agent Source Tracking
    public static final String LEAD_SOURCE = "Voice Agent";
    public static final String LEAD_SOURCE_TYPE = "voice_agent";
    /**
     * Backend base URL (configured in gradle.properties)
     * Test backend: https://openai-realtime-backend-v1.onrender.com
     * Production: https://bot.tripandevent.com
     */
    public static final String BACKEND_BASE_URL = BuildConfig.TRIPANDEVENT_BASE_URL;
    public static final String TRIPANDEVENT_API_TOKEN = BuildConfig.TRIPANDEVENT_API_TOKEN;
    
    /**
     * Flag to use test backend mode (simpler API, no auth required)
     * Set to false when switching to production backend
     */
    public static final boolean USE_TEST_BACKEND = true;
    
    // Test backend endpoints (GET /token, POST /session)
    public static final String ENDPOINT_GET_TOKEN_TEST = "/token";
    public static final String ENDPOINT_SESSION_TEST = "/session";
    
    // Production tRPC endpoints
    public static final String ENDPOINT_GET_TOKEN_PROD = "/api/trpc/voice.getToken";
    public static final String ENDPOINT_SAVE_LEAD = "/api/trpc/sanbot.saveCustomerLead";
    public static final String ENDPOINT_CREATE_QUOTE = "/api/trpc/sanbot.createQuote";
    public static final String ENDPOINT_LOG_DISCONNECT = "/api/trpc/sanbot.logDisconnect";
    
    // ============================================
    // OPENAI REALTIME API
    // ============================================
    
    public static final String OPENAI_REALTIME_URL = "https://api.openai.com/v1/realtime/calls";
    public static final String OPENAI_REALTIME_MODEL = BuildConfig.OPENAI_REALTIME_MODEL;
    public static final String OPENAI_VOICE = BuildConfig.OPENAI_VOICE;
    
    // ============================================
    // WEBRTC CONFIGURATION
    // ============================================
    
    public static final String WEBRTC_DATA_CHANNEL_NAME = "oai-events";
    public static final int WEBRTC_CONNECTION_TIMEOUT_MS = 30000;
    public static final int WEBRTC_ICE_TIMEOUT_MS = 10000;
    
    // ============================================
    // AUDIO CONFIGURATION
    // ============================================
    
    public static final int AUDIO_SAMPLE_RATE = 24000;  // Required by OpenAI Realtime
    public static final int AUDIO_CHANNELS = 1;          // Mono
    public static final int AUDIO_BITS_PER_SAMPLE = 16;  // PCM16
    public static final String AUDIO_FORMAT = "audio/pcm";
    
    // ============================================
    // SESSION CONFIGURATION
    // ============================================
    
    public static final int SESSION_MAX_DURATION_MS = 60 * 60 * 1000;  // 60 minutes max
    public static final int RECONNECT_MAX_ATTEMPTS = 3;
    public static final int RECONNECT_DELAY_MS = 2000;
    public static final int TOKEN_REFRESH_MARGIN_MS = 60 * 1000;  // Refresh 1 minute before expiry
    
    // ============================================
    // FUNCTION NAMES
    // ============================================
    
    public static final String FUNCTION_SAVE_CUSTOMER_LEAD = "save_customer_lead";
    public static final String FUNCTION_CREATE_QUOTE = "create_quote";
    public static final String FUNCTION_DISCONNECT_CALL = "disconnect_call";
    
    // ============================================
    // NOTIFICATION
    // ============================================
    
    public static final String NOTIFICATION_CHANNEL_ID = "voice_agent_channel";
    public static final String NOTIFICATION_CHANNEL_NAME = "Voice Agent";
    public static final int NOTIFICATION_ID = 1001;
    
    // ============================================
    // INTENT EXTRAS
    // ============================================
    
    public static final String EXTRA_SESSION_ID = "extra_session_id";
    public static final String EXTRA_CUSTOMER_NAME = "extra_customer_name";
    
    // ============================================
    // SHARED PREFERENCES
    // ============================================
    
    public static final String PREFS_NAME = "sanbot_voice_prefs";
    public static final String PREF_VOICE_SELECTION = "voice_selection";
    public static final String PREF_VAD_ENABLED = "vad_enabled";
    public static final String PREF_DEBUG_MODE = "debug_mode";
    
    // ============================================
    // LOGGING TAGS
    // ============================================
    
    public static final String TAG_WEBRTC = "WebRTC";
    public static final String TAG_OPENAI = "OpenAI";
    public static final String TAG_SANBOT = "Sanbot";
    public static final String TAG_AUDIO = "Audio";
    public static final String TAG_SERVICE = "VoiceService";
    public static final String TAG_FUNCTION = "Function";


    // AI Instructions
    public static final String DEFAULT_AI_INSTRUCTIONS = 
        "You are a helpful, friendly travel assistant for Trip & Event. " +
        "Help customers plan their perfect vacation. Be warm, enthusiastic, and knowledgeable. " +
        "Ask about their travel preferences, suggest destinations, and offer to create quotes. " +
        "Always try to collect customer contact information for follow-up.";

    // Robot Motion Settings
    public static final boolean ENABLE_ROBOT_MOTION = true;
    public static final int MOTION_DELAY_MS = 100; // Delay before executing motion for sync
}
