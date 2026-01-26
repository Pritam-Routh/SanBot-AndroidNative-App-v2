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

    // Session ID (needed for speak commands)
    private String sessionId;

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

    /**
     * Set the session ID (extracted from session info after startSession)
     */
    public void setSessionId(String id) {
        this.sessionId = id;
    }

    /**
     * Get the current session ID
     */
    public String getSessionId() {
        return sessionId;
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

        @SerializedName("avatar_persona")
        public AvatarPersona avatarPersona;

        public GetTokenRequest(String mode, String avatarId, boolean isSandbox, AvatarPersona persona) {
            this.mode = mode;
            this.avatarId = avatarId;
            this.isSandbox = isSandbox;
            this.avatarPersona = persona;
        }
    }

    /**
     * Avatar persona configuration for FULL mode
     */
    public static class AvatarPersona {
        @SerializedName("voice_id")
        public String voiceId;

        @SerializedName("language")
        public String language;

        @SerializedName("context_id")
        public String contextId;

        public AvatarPersona(String voiceId, String language, String contextId) {
            this.voiceId = voiceId;
            this.language = language;
            this.contextId = contextId;
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

        String mode = LiveAvatarConfig.getSessionMode();
        AvatarPersona persona = null;

        // FULL mode requires avatar_persona with voice and LLM configuration
        if ("FULL".equals(mode)) {
            String contextId = LiveAvatarConfig.getContextId();
            persona = new AvatarPersona(null, "en", contextId);
            Log.d(TAG, "FULL mode persona: language=en, context_id=" + contextId);
        }

        GetTokenRequest request = new GetTokenRequest(
            mode,
            avatarId != null ? avatarId : LiveAvatarConfig.getAvatarId(),
            false,  // Always use production mode - this avatar requires it
            persona
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
        sessionId = null;
    }

    // ============================================
    // SPEAK COMMANDS (FOR LIP-SYNC)
    // ============================================

    /**
     * Request body for speak command
     */
    public static class SpeakRequest {
        @SerializedName("session_id")
        public String sessionId;

        @SerializedName("text")
        public String text;

        @SerializedName("task_type")
        public String taskType;  // "repeat" or "talk"

        public SpeakRequest(String sessionId, String text, String taskType) {
            this.sessionId = sessionId;
            this.text = text;
            this.taskType = taskType;
        }
    }

    /**
     * Response from speak command
     */
    public static class SpeakResponse {
        @SerializedName("task_id")
        public String taskId;
    }

    /**
     * Make avatar speak text with lip sync
     *
     * Uses backend proxy endpoint which has the API key and fallback logic.
     * The backend will try multiple endpoints (LiveAvatar and HeyGen).
     *
     * @param text Text for avatar to speak
     * @param callback Response callback
     */
    public void speak(@NonNull String text, @NonNull SimpleCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onSuccess();  // Nothing to speak
            return;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            Log.e(TAG, "*** SPEAK FAILED: Session ID not set! ***");
            callback.onError("Session ID not set");
            return;
        }

        // Use backend proxy endpoint (has API key and fallback logic)
        String url = backendBaseUrl + "/liveavatar/speak";

        SpeakRequest request = new SpeakRequest(sessionId, text.trim(), "repeat");
        String json = gson.toJson(request);

        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();

        Log.i(TAG, "=== SPEAK REQUEST (Backend Proxy) ===");
        Log.i(TAG, "URL: " + url);
        Log.i(TAG, "Body: " + json);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "*** SPEAK NETWORK ERROR ***", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";

                Log.i(TAG, "=== SPEAK RESPONSE ===");
                Log.i(TAG, "HTTP " + response.code());
                Log.i(TAG, "Body: " + body);

                if (response.isSuccessful()) {
                    try {
                        // Check if response is HTML (Vite fallback) instead of JSON
                        if (body.startsWith("<!DOCTYPE") || body.startsWith("<html")) {
                            Log.e(TAG, "*** SPEAK FAILED: Got HTML instead of JSON (backend route not found) ***");
                            callback.onError("Backend route not configured");
                            return;
                        }

                        ActionResponse actionResponse = gson.fromJson(body, ActionResponse.class);
                        if (actionResponse != null && actionResponse.success) {
                            Log.i(TAG, "✓ SPEAK SUCCESS");
                            callback.onSuccess();
                        } else {
                            String error = actionResponse != null ? actionResponse.error : "Unknown error";
                            Log.e(TAG, "Speak failed: " + error);
                            callback.onError(error);
                        }
                    } catch (Exception e) {
                        // Parse failure means response wasn't valid JSON - treat as error
                        Log.e(TAG, "*** SPEAK FAILED: Response not valid JSON ***");
                        callback.onError("Invalid response format");
                    }
                } else {
                    Log.e(TAG, "*** SPEAK FAILED *** HTTP " + response.code());
                    callback.onError("HTTP " + response.code() + ": " + body);
                }
            }
        });
    }

    /**
     * Interrupt avatar speech (for barge-in)
     *
     * Uses backend proxy endpoint for interrupt.
     *
     * @param callback Response callback
     */
    public void interrupt(@NonNull SimpleCallback callback) {
        if (sessionId == null || sessionId.isEmpty()) {
            callback.onSuccess();  // No session to interrupt
            return;
        }

        // Use backend proxy endpoint
        String url = backendBaseUrl + "/liveavatar/interrupt";

        String json = "{\"session_id\":\"" + sessionId + "\"}";

        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();

        Log.d(TAG, "Interrupt request: " + url);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Interrupt request failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "Interrupt response: HTTP " + response.code() + " - " + body);

                if (response.isSuccessful()) {
                    Log.d(TAG, "Interrupt success");
                    callback.onSuccess();
                } else {
                    callback.onError("HTTP " + response.code() + ": " + body);
                }
            }
        });
    }
}
