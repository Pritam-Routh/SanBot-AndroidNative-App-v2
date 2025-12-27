package com.tripandevent.sanbotvoice.api;

import com.tripandevent.sanbotvoice.api.models.ApiResponse;
import com.tripandevent.sanbotvoice.api.models.CustomerLead;
import com.tripandevent.sanbotvoice.api.models.QuoteRequest;
import com.tripandevent.sanbotvoice.api.models.TokenResponse;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

/**
 * Retrofit interface for backend API calls.
 * 
 * Supports two modes:
 * 1. Test backend (https://openai-realtime-backend-v1.onrender.com)
 *    - GET /token - Get ephemeral token (no auth required)
 *    - POST /session - SDP exchange (no auth required)
 * 
 * 2. Production backend (https://bot.tripandevent.com)
 *    - POST /api/trpc/voice.getToken - Get ephemeral token
 *    - POST /api/trpc/sanbot.* - CRM functions
 */
public interface TripAndEventApi {
    
    // ============================================
    // TEST BACKEND ENDPOINTS
    // ============================================
    
    /**
     * Get ephemeral token from test backend
     * GET /token
     * 
     * Response format:
     * {
     *   "client_secret": {
     *     "value": "ek_xxx...",
     *     "expires_at": 1234567890
     *   }
     * }
     */
    @GET("/token")
    Call<TokenResponse> getTokenTest();
    
    /**
     * Exchange SDP with test backend
     * POST /session
     * 
     * The backend handles the OpenAI authentication.
     * Request: SDP offer as text/plain
     * Response: SDP answer as text/plain
     */
    @Headers("Content-Type: text/plain")
    @POST("/session")
    Call<ResponseBody> exchangeSdpViaBackend(@Body RequestBody sdpOffer);
    
    // ============================================
    // PRODUCTION BACKEND ENDPOINTS
    // ============================================
    
    /**
     * Get ephemeral token from production backend
     * POST /api/trpc/voice.getToken
     */
    @POST("/api/trpc/voice.getToken")
    Call<TokenResponse> getTokenProd(@Header("Authorization") String authHeader);
    
    /**
     * Save customer lead to CRM.
     * Called when AI collects customer information.
     */
    @POST("/api/trpc/sanbot.saveCustomerLead")
    Call<ApiResponse<SaveLeadResponse>> saveCustomerLead(
        @Header("Authorization") String authToken,
        @Body CustomerLead lead
    );
    
    /**
     * Create and save a quote.
     * Called when AI generates a quote for the customer.
     */
    @POST("/api/trpc/sanbot.createQuote")
    Call<ApiResponse<CreateQuoteResponse>> createQuote(
        @Header("Authorization") String authToken,
        @Body QuoteRequest quoteRequest
    );
    
    /**
     * Log conversation disconnect.
     * Called when conversation ends (normal or abnormal).
     */
    @POST("/api/trpc/sanbot.logDisconnect")
    Call<ApiResponse<Void>> logDisconnect(
        @Header("Authorization") String authToken,
        @Body DisconnectLog disconnectLog
    );
    
    // ============================================
    // RESPONSE MODELS
    // ============================================
    
    class SaveLeadResponse {
        public String leadId;
        public boolean success;
        public String message;
    }
    
    class CreateQuoteResponse {
        public String quoteId;
        public String quoteNumber;
        public boolean success;
        public String pdfUrl;
    }
    
    class DisconnectLog {
        public String sessionId;
        public String reason;
        public long durationMs;
        public int messageCount;
        public String transcript;
        
        public DisconnectLog(String sessionId, String reason, long durationMs, 
                            int messageCount, String transcript) {
            this.sessionId = sessionId;
            this.reason = reason;
            this.durationMs = durationMs;
            this.messageCount = messageCount;
            this.transcript = transcript;
        }
    }
}
