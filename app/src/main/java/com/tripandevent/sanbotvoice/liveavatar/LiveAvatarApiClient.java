package com.tripandevent.sanbotvoice.liveavatar;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.tripandevent.sanbotvoice.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LiveAvatar API Client
 *
 * Handles REST API communication with LiveAvatar backend.
 * Supports both direct LiveAvatar API and backend proxy endpoints.
 *
 * API Endpoints:
 * - POST /v1/sessions/start - Start session, get LiveKit credentials
 * - POST /v1/sessions/stop - Stop session
 * - POST /v1/sessions/keep-alive - Keep session alive
 */
public class LiveAvatarApiClient {
    private static final String TAG = "LiveAvatarApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * LiveAvatar API success code
     */
    private static final int SUCCESS_CODE = 1000;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String backendBaseUrl;

    // Session token for LiveAvatar API authentication
    private String sessionToken;

    // Singleton instance
    private static LiveAvatarApiClient instance;

    /**
     * Get singleton instance
     */
    public static synchronized LiveAvatarApiClient getInstance() {
        if (instance == null) {
            instance = new LiveAvatarApiClient();
        }
        return instance;
    }

    private LiveAvatarApiClient() {
        this.backendBaseUrl = BuildConfig.TRIPANDEVENT_BASE_URL;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Set the session token for LiveAvatar API authentication
     */
    public void setSessionToken(String token) {
        this.sessionToken = token;
    }

    /**
     * Get the current session token
     */
    public String getSessionToken() {
        return sessionToken;
    }

    // ============================================
    // REQUEST/RESPONSE MODELS
    // ============================================

    /**
     * Request to get session token from backend
     */
    public static class GetTokenRequest {
        @SerializedName("mode")
        public String mode;

        @SerializedName("avatar_id")
        public String avatarId;

        @SerializedName("is_sandbox")
        public boolean isSandbox;

        public GetTokenRequest(String mode, String avatarId, boolean isSandbox) {
            this.mode = mode;
            this.avatarId = avatarId;
            this.isSandbox = isSandbox;
        }
    }

    /**
     * Response from backend with session token
     */
    public static class TokenResponse {
        @SerializedName("success")
        public boolean success;

        @SerializedName("session_token")
        public String sessionToken;

        @SerializedName("error")
        public String error;

        @SerializedName("message")
        public String message;
    }

    /**
     * LiveAvatar API response wrapper
     */
    public static class ApiResponse<T> {
        @SerializedName("code")
        public int code;

        @SerializedName("message")
        public String message;

        @SerializedName("data")
        public T data;

        public boolean isSuccess() {
            return code == SUCCESS_CODE;
        }
    }

    /**
     * Session info from LiveAvatar API
     */
    public static class SessionInfo {
        @SerializedName("session_id")
        public String sessionId;

        @SerializedName("max_session_duration")
        public Integer maxSessionDuration;

        @SerializedName("livekit_url")
        public String liveKitUrl;

        @SerializedName("livekit_client_token")
        public String liveKitToken;

        @SerializedName("ws_url")
        public String wsUrl;

        @Override
        public String toString() {
            return "SessionInfo{sessionId=" + sessionId +
                   ", liveKitUrl=" + (liveKitUrl != null ? "set" : "null") +
                   ", wsUrl=" + (wsUrl != null ? "set" : "null") + "}";
        }
    }

    /**
     * Generic action response
     */
    public static class ActionResponse {
        @SerializedName("success")
        public boolean success;

        @SerializedName("message")
        public String message;

        @SerializedName("error")
        public String error;
    }

    // ============================================
    // CALLBACKS
    // ============================================

    public interface TokenCallback {
        void onSuccess(String sessionToken);
        void onError(String error);
    }

    public interface SessionCallback {
        void onSuccess(SessionInfo sessionInfo);
        void onError(String error);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(String error);
    }

    // ============================================
    // BACKEND PROXY METHODS
    // ============================================

    /**
     * Get session token from our backend
     * Backend handles LiveAvatar API key and token generation
     *
     * @param avatarId Avatar ID to use (null for default)
     * @param callback Response callback
     */
    public void getSessionToken(@Nullable String avatarId, @NonNull TokenCallback callback) {
        String url = backendBaseUrl + "/liveavatar/session/token";

        GetTokenRequest request = new GetTokenRequest(
            LiveAvatarConfig.getSessionMode(),
            avatarId != null ? avatarId : LiveAvatarConfig.getAvatarId(),
            false  // Always use production mode - this avatar requires it
        );

        String json = gson.toJson(request);

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        Log.d(TAG, "Getting session token for avatar: " + request.avatarId);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Get token failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Token response (code " + response.code() + "): " +
                          (body.length() > 200 ? body.substring(0, 200) + "..." : body));

                    if (!response.isSuccessful()) {
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    TokenResponse tokenResponse = gson.fromJson(body, TokenResponse.class);
                    if (tokenResponse != null && tokenResponse.success && tokenResponse.sessionToken != null) {
                        sessionToken = tokenResponse.sessionToken;
                        callback.onSuccess(tokenResponse.sessionToken);
                    } else {
                        String error = tokenResponse != null ?
                            (tokenResponse.error != null ? tokenResponse.error : tokenResponse.message) :
                            "Invalid response";
                        callback.onError(error);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse token response failed", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    // ============================================
    // LIVEAVATAR DIRECT API METHODS
    // ============================================

    /**
     * Start a LiveAvatar session
     * Requires sessionToken to be set first
     *
     * @param callback Response callback with session info
     */
    public void startSession(@NonNull SessionCallback callback) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            callback.onError("Session token not set");
            return;
        }

        String url = LiveAvatarConfig.getApiUrl() + "/v1/sessions/start";

        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + sessionToken)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("{}", JSON))
                .build();

        Log.d(TAG, "Starting LiveAvatar session...");

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Start session failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Start session response (code " + response.code() + "): " +
                          (body.length() > 500 ? body.substring(0, 500) + "..." : body));

                    if (!response.isSuccessful()) {
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    // Parse LiveAvatar API response format
                    ApiResponse<SessionInfo> apiResponse = gson.fromJson(body,
                        new com.google.gson.reflect.TypeToken<ApiResponse<SessionInfo>>(){}.getType());

                    if (apiResponse != null && apiResponse.isSuccess() && apiResponse.data != null) {
                        Log.d(TAG, "Session started: " + apiResponse.data);
                        callback.onSuccess(apiResponse.data);
                    } else {
                        String error = apiResponse != null ? apiResponse.message : "Invalid response";
                        callback.onError(error);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse session response failed", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Stop the current LiveAvatar session
     *
     * @param callback Response callback
     */
    public void stopSession(@NonNull SimpleCallback callback) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            callback.onSuccess(); // Already stopped
            return;
        }

        String url = LiveAvatarConfig.getApiUrl() + "/v1/sessions/stop";

        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + sessionToken)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("{}", JSON))
                .build();

        Log.d(TAG, "Stopping LiveAvatar session...");

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Stop session failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Log.d(TAG, "Stop session response: " + response.code());
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    String body = response.body() != null ? response.body().string() : "";
                    callback.onError("HTTP " + response.code() + ": " + body);
                }
            }
        });
    }

    /**
     * Keep the session alive
     *
     * @param callback Response callback
     */
    public void keepAlive(@NonNull SimpleCallback callback) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            callback.onError("Session token not set");
            return;
        }

        String url = LiveAvatarConfig.getApiUrl() + "/v1/sessions/keep-alive";

        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + sessionToken)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("{}", JSON))
                .build();

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Keep-alive failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Keep-alive success");
                    callback.onSuccess();
                } else {
                    String body = response.body() != null ? response.body().string() : "";
                    callback.onError("HTTP " + response.code() + ": " + body);
                }
            }
        });
    }

    /**
     * Clear session state
     */
    public void clearSession() {
        sessionToken = null;
    }
}
