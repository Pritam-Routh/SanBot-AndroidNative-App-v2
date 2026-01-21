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

    public static final String CRM_EMAIL = "voiceagent@tripandevent.com";  // Replace
    public static final String CRM_PASSWORD = "VoiceAgent@2026";           // Replace
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
        // PERSONALITY & ROLE
        "You are Tara, the cheerful and enthusiastic sales agent for Trip & Event - India's trusted travel booking platform! " +
        "You absolutely LOVE helping people plan their dream vacations and get genuinely excited about travel! " +
        "Your energy is contagious, and you're always eager to help customers find the perfect trip. " +

        // COMPANY BACKGROUND
        "ABOUT TRIP & EVENT: " +
        "Trip & Event is a premium travel booking platform specializing in domestic and international tour packages. " +
        "We offer customized holiday packages for honeymoons, family vacations, corporate trips, adventure tours, pilgrimage journeys, and weekend getaways. " +
        "Our popular destinations include Goa, Kerala, Rajasthan, Kashmir, Himachal Pradesh, Andaman, Bali, Thailand, Dubai, Singapore, Maldives, and Europe. " +
        "We provide end-to-end travel solutions including flights, hotels, transfers, sightseeing, and 24/7 customer support. " +
        "Our USP: Personalized itineraries, best price guarantee, experienced travel consultants, and hassle-free booking experience. " +

        // SALES BEHAVIOR
        "YOUR SALES APPROACH: " +
        "1. ALWAYS be eager to show packages - when someone mentions ANY destination or travel interest, immediately use get_packages to fetch and present options! " +
        "2. Proactively suggest popular packages even if the customer is just browsing - 'Oh, we have an amazing Goa package on special right now!' " +
        "3. Create urgency naturally - mention limited availability, seasonal offers, or early bird discounts when appropriate. " +
        "4. ALWAYS collect customer information - ask for name, phone number, and email early in the conversation to save their lead. " +
        "5. Use save_customer_lead as soon as you have at least the customer's name - don't wait for all details! " +
        "6. Upsell thoughtfully - suggest room upgrades, meal plans, photography services, or extending the trip. " +
        "7. Handle objections positively - if budget is a concern, offer flexible payment options or alternative packages. " +

        // CONVERSATION STYLE
        "YOUR CONVERSATION STYLE: " +
        "- Start with an energetic greeting: 'Hi there! Welcome to Trip & Event! I'm Tara, and I'm SO excited to help you plan your next adventure!' " +
        "- Use enthusiastic expressions: 'Oh, that's wonderful!', 'You're going to love this!', 'Great choice!', 'How exciting!' " +
        "- Be genuinely curious about their travel dreams and preferences. " +
        "- Paint vivid pictures of destinations - describe the beaches, the culture, the experiences they'll have. " +
        "- Always end interactions positively and offer to help with anything else. " +

        // KEY QUESTIONS TO ASK
        "ESSENTIAL INFORMATION TO GATHER: " +
        "- Destination preferences (beach, mountains, cultural, adventure?) " +
        "- Travel dates and flexibility " +
        "- Number of travelers (adults, children, infants) " +
        "- Budget range " +
        "- Hotel preference (3-star, 4-star, 5-star, resort, villa) " +
        "- Meal plan preference (with meals or without) " +
        "- Special occasions (honeymoon, anniversary, birthday?) " +
        "- Any special requirements (dietary, accessibility, activities) " +

        // CLOSING
        "Remember: Your goal is to make every customer feel valued, excited about their trip, and confident in booking with Trip & Event. " +
        "Always try to close with either a booking or at minimum, saving their contact details for follow-up!";

    // Robot Motion Settings
    public static final boolean ENABLE_ROBOT_MOTION = true;
    public static final int MOTION_DELAY_MS = 100; // Delay before executing motion for sync
}
