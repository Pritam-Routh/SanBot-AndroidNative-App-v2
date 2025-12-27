package com.tripandevent.sanbotvoice.api;

import android.os.Handler;
import android.os.Looper;

import com.tripandevent.sanbotvoice.api.models.TokenResponse;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Call;
import retrofit2.Response;

/**
 * Manages ephemeral tokens for OpenAI Realtime API.
 * 
 * SECURITY NOTE: This class ensures API keys are never exposed in the app.
 * All tokens are fetched from the TripAndEvent backend which has the actual OpenAI key.
 */
public class TokenManager {
    
    private static volatile TokenManager instance;
    
    private final ApiClient apiClient;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    private String currentToken;
    private long tokenExpiresAt;
    private final AtomicBoolean isFetching = new AtomicBoolean(false);
    
    /**
     * Callback interface for token operations
     */
    public interface TokenCallback {
        void onTokenReceived(String token);
        void onError(Exception e);
    }
    
    private TokenManager() {
        this.apiClient = ApiClient.getInstance();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Get singleton instance
     */
    public static TokenManager getInstance() {
        if (instance == null) {
            synchronized (TokenManager.class) {
                if (instance == null) {
                    instance = new TokenManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get a valid ephemeral token.
     * If cached token is still valid, returns it immediately.
     * Otherwise fetches a new token from the backend.
     * 
     * @param callback Called with the token or error
     */
    public void getToken(TokenCallback callback) {
        // Check if we have a valid cached token
        if (isTokenValid()) {
            Logger.d("TokenManager", "Using cached token (expires in %d seconds)",
                    (tokenExpiresAt - System.currentTimeMillis()) / 1000);
            callback.onTokenReceived(currentToken);
            return;
        }
        
        // Fetch new token
        fetchToken(callback);
    }
    
    /**
     * Force fetch a new token, ignoring cache
     */
    public void refreshToken(TokenCallback callback) {
        invalidateToken();
        fetchToken(callback);
    }
    
    /**
     * Invalidate the current token
     */
    public void invalidateToken() {
        currentToken = null;
        tokenExpiresAt = 0;
        Logger.d("TokenManager", "Token invalidated");
    }
    
    /**
     * Check if current token is still valid
     */
    private boolean isTokenValid() {
        if (currentToken == null || currentToken.isEmpty()) {
            return false;
        }
        
        // Add margin to prevent using token that's about to expire
        long now = System.currentTimeMillis();
        return tokenExpiresAt > (now + Constants.TOKEN_REFRESH_MARGIN_MS);
    }
    
    /**
     * Fetch token from backend
     */
    private void fetchToken(TokenCallback callback) {
        // Prevent multiple simultaneous fetches
        if (!isFetching.compareAndSet(false, true)) {
            Logger.d("TokenManager", "Token fetch already in progress, waiting...");
            // Wait for current fetch to complete
            waitForToken(callback);
            return;
        }
        
        Logger.d("TokenManager", "Fetching new ephemeral token...");
        
        executor.execute(() -> {
            try {
                Call<TokenResponse> call;
                
                // Use appropriate endpoint based on backend mode
                if (Constants.USE_TEST_BACKEND) {
                    // Test backend: GET /token (no auth)
                    call = apiClient.getApi().getTokenTest();
                    Logger.d("TokenManager", "Using test backend (GET /token)");
                } else {
                    // Production backend: POST with auth
                    call = apiClient.getApi().getTokenProd(apiClient.getAuthHeader());
                    Logger.d("TokenManager", "Using production backend");
                }
                
                Response<TokenResponse> response = call.execute();
                
                if (response.isSuccessful() && response.body() != null) {
                    TokenResponse tokenResponse = response.body();
                    
                    if (tokenResponse.isValid()) {
                        currentToken = tokenResponse.getToken();
                        tokenExpiresAt = tokenResponse.getExpiresAt();
                        
                        Logger.d("TokenManager", "Token received, expires at %d", tokenExpiresAt);
                        
                        isFetching.set(false);
                        notifySuccess(callback, currentToken);
                    } else {
                        Logger.e("TokenManager", "Invalid token response");
                        isFetching.set(false);
                        notifyError(callback, new Exception("Invalid token response"));
                    }
                } else {
                    String errorBody = response.errorBody() != null ? 
                                      response.errorBody().string() : "Unknown error";
                    Logger.e("TokenManager", "Token fetch failed: %d - %s", 
                            response.code(), errorBody);
                    isFetching.set(false);
                    notifyError(callback, new Exception("Token fetch failed: " + response.code()));
                }
                
            } catch (IOException e) {
                Logger.e(e, "TokenManager: Network error fetching token");
                isFetching.set(false);
                notifyError(callback, e);
            } catch (Exception e) {
                Logger.e(e, "TokenManager: Unexpected error");
                isFetching.set(false);
                notifyError(callback, e);
            }
        });
    }
    
    /**
     * Wait for ongoing fetch and use that token
     */
    private void waitForToken(TokenCallback callback) {
        executor.execute(() -> {
            // Simple polling - in production consider using CountDownLatch
            int maxWaitMs = 10000;
            int waitedMs = 0;
            int pollIntervalMs = 100;
            
            while (isFetching.get() && waitedMs < maxWaitMs) {
                try {
                    Thread.sleep(pollIntervalMs);
                    waitedMs += pollIntervalMs;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    notifyError(callback, e);
                    return;
                }
            }
            
            if (isTokenValid()) {
                notifySuccess(callback, currentToken);
            } else {
                notifyError(callback, new Exception("Token fetch timeout"));
            }
        });
    }
    
    /**
     * Notify callback of success on main thread
     */
    private void notifySuccess(TokenCallback callback, String token) {
        mainHandler.post(() -> callback.onTokenReceived(token));
    }
    
    /**
     * Notify callback of error on main thread
     */
    private void notifyError(TokenCallback callback, Exception e) {
        mainHandler.post(() -> callback.onError(e));
    }
    
    /**
     * Cleanup resources
     */
    public void shutdown() {
        executor.shutdown();
        invalidateToken();
    }
}
