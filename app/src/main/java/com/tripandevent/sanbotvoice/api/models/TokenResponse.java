package com.tripandevent.sanbotvoice.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * Response model for ephemeral token.
 * Supports both test and production backend formats.
 */
public class TokenResponse {
    
    // Test backend format (direct fields)
    @SerializedName("value")
    private String value;
    
    @SerializedName("expires_at")
    private long expiresAt;
    
    // Production backend format (tRPC wrapper)
    @SerializedName("result")
    private Result result;
    
    public String getToken() {
        // Try direct format first (test backend)
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // Fall back to tRPC format (production backend)
        if (result != null && result.data != null && result.data.clientSecret != null) {
            return result.data.clientSecret.value;
        }
        return null;
    }
    
    public long getExpiresAt() {
        // Try direct format first (test backend)
        if (expiresAt > 0) {
            return expiresAt;
        }
        // Fall back to tRPC format (production backend)
        if (result != null && result.data != null && result.data.clientSecret != null) {
            return result.data.clientSecret.expiresAt;
        }
        return 0;
    }
    
    public boolean isValid() {
        String token = getToken();
        return token != null && !token.isEmpty();
    }
    
    public static class Result {
        @SerializedName("data")
        private Data data;
    }
    
    public static class Data {
        @SerializedName("client_secret")
        private ClientSecret clientSecret;
    }
    
    public static class ClientSecret {
        @SerializedName("value")
        private String value;
        
        @SerializedName("expires_at")
        private long expiresAt;
    }
}