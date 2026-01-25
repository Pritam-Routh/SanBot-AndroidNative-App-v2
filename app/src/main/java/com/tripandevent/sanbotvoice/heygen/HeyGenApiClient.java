package com.tripandevent.sanbotvoice.heygen;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.tripandevent.sanbotvoice.BuildConfig;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HeyGen API Client
 *
 * Communicates with the backend server's HeyGen endpoints
 * to manage avatar streaming sessions.
 */
public class HeyGenApiClient {
    private static final String TAG = "HeyGenApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String baseUrl;

    // Singleton instance
    private static HeyGenApiClient instance;

    /**
     * Get singleton instance
     */
    public static synchronized HeyGenApiClient getInstance() {
        if (instance == null) {
            instance = new HeyGenApiClient();
        }
        return instance;
    }

    private HeyGenApiClient() {
        this.baseUrl = BuildConfig.TRIPANDEVENT_BASE_URL;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ============================================
    // REQUEST/RESPONSE MODELS
    // ============================================

    /**
     * Request to create a HeyGen session
     */
    public static class CreateSessionRequest {
        @SerializedName("clientId")
        public String clientId;

        @SerializedName("avatarId")
        public String avatarId;

        @SerializedName("voiceId")
        public String voiceId;

        public CreateSessionRequest(String clientId, String avatarId) {
            this.clientId = clientId;
            this.avatarId = avatarId;
        }
    }

    /**
     * Response from creating a HeyGen session
     */
    public static class SessionResponse {
        @SerializedName("success")
        public boolean success;

        @SerializedName("sessionId")
        public String sessionId;

        @SerializedName("liveKitUrl")
        public String liveKitUrl;

        @SerializedName("liveKitToken")
        public String liveKitToken;

        @SerializedName("iceServers")
        public List<IceServer> iceServers;

        @SerializedName("existing")
        public boolean existing;

        @SerializedName("error")
        public String error;

        @SerializedName("message")
        public String message;
    }

    /**
     * ICE server configuration
     */
    public static class IceServer {
        @SerializedName("urls")
        public List<String> urls;

        @SerializedName("username")
        public String username;

        @SerializedName("credential")
        public String credential;
    }

    /**
     * Request to stream text to avatar
     */
    public static class StreamTextRequest {
        @SerializedName("sessionId")
        public String sessionId;

        @SerializedName("text")
        public String text;

        @SerializedName("taskType")
        public String taskType = "repeat";

        public StreamTextRequest(String sessionId, String text) {
            this.sessionId = sessionId;
            this.text = text;
        }
    }

    /**
     * Response from streaming text
     */
    public static class StreamResponse {
        @SerializedName("success")
        public boolean success;

        @SerializedName("taskId")
        public String taskId;

        @SerializedName("skipped")
        public boolean skipped;

        @SerializedName("error")
        public String error;
    }

    /**
     * Request to interrupt or stop session
     */
    public static class SessionActionRequest {
        @SerializedName("sessionId")
        public String sessionId;

        @SerializedName("clientId")
        public String clientId;

        public SessionActionRequest(String sessionId) {
            this.sessionId = sessionId;
        }

        public SessionActionRequest(String sessionId, String clientId) {
            this.sessionId = sessionId;
            this.clientId = clientId;
        }
    }

    /**
     * Generic success response
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

    public interface SessionCallback {
        void onSuccess(SessionResponse response);
        void onError(String error);
    }

    public interface StreamCallback {
        void onSuccess(StreamResponse response);
        void onError(String error);
    }

    public interface ActionCallback {
        void onSuccess(ActionResponse response);
        void onError(String error);
    }

    // ============================================
    // API METHODS
    // ============================================

    /**
     * Create a new HeyGen streaming session
     *
     * @param clientId Unique client identifier
     * @param avatarId HeyGen avatar ID to use (null for default)
     * @param callback Response callback
     */
    public void createSession(@NonNull String clientId, @Nullable String avatarId, @NonNull SessionCallback callback) {
        String url = baseUrl + "/heygen/session";

        CreateSessionRequest request = new CreateSessionRequest(clientId, avatarId);
        String json = gson.toJson(request);

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        Log.d(TAG, "Creating HeyGen session for client: " + clientId);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Create session failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";

                    // Debug: Log raw response to help diagnose API issues
                    Log.d(TAG, "Raw response (code " + response.code() + "): " +
                          (body.length() > 500 ? body.substring(0, 500) + "..." : body));

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Create session HTTP error: " + response.code());
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    // Check if response is valid JSON before parsing
                    String trimmedBody = body.trim();
                    if (trimmedBody.isEmpty()) {
                        Log.e(TAG, "Empty response from server");
                        callback.onError("Server returned empty response");
                        return;
                    }

                    if (!trimmedBody.startsWith("{")) {
                        Log.e(TAG, "Server returned non-JSON response: " + trimmedBody);
                        callback.onError("Server returned invalid response: " +
                            (trimmedBody.length() > 100 ? trimmedBody.substring(0, 100) + "..." : trimmedBody));
                        return;
                    }

                    SessionResponse sessionResponse = gson.fromJson(body, SessionResponse.class);
                    if (sessionResponse == null) {
                        callback.onError("Failed to parse session response");
                        return;
                    }

                    if (sessionResponse.success) {
                        Log.d(TAG, "Session created: " + sessionResponse.sessionId);
                        callback.onSuccess(sessionResponse);
                    } else {
                        String errorMsg = sessionResponse.error != null ? sessionResponse.error :
                                         (sessionResponse.message != null ? sessionResponse.message : "Unknown error");
                        callback.onError(errorMsg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse session response failed", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Stream text to the avatar for speech
     *
     * @param sessionId Active session ID
     * @param text Text for avatar to speak
     * @param callback Response callback
     */
    public void streamText(@NonNull String sessionId, @NonNull String text, @NonNull StreamCallback callback) {
        String url = baseUrl + "/heygen/stream";

        StreamTextRequest request = new StreamTextRequest(sessionId, text);
        String json = gson.toJson(request);

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        Log.d(TAG, "Streaming text (" + text.length() + " chars) to session: " + sessionId);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Stream text failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Stream response (code " + response.code() + "): " +
                          (body.length() > 200 ? body.substring(0, 200) + "..." : body));

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Stream text HTTP error: " + response.code());
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    String trimmedBody = body.trim();
                    if (trimmedBody.isEmpty() || !trimmedBody.startsWith("{")) {
                        Log.e(TAG, "Invalid stream response: " + trimmedBody);
                        callback.onError("Invalid server response");
                        return;
                    }

                    StreamResponse streamResponse = gson.fromJson(body, StreamResponse.class);
                    if (streamResponse != null) {
                        callback.onSuccess(streamResponse);
                    } else {
                        callback.onError("Failed to parse stream response");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse stream response failed", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Interrupt the avatar's current speech
     *
     * @param sessionId Active session ID
     * @param callback Response callback
     */
    public void interrupt(@NonNull String sessionId, @NonNull ActionCallback callback) {
        String url = baseUrl + "/heygen/interrupt";

        SessionActionRequest request = new SessionActionRequest(sessionId);
        String json = gson.toJson(request);

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        Log.d(TAG, "Interrupting avatar: " + sessionId);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Interrupt failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Interrupt response (code " + response.code() + "): " + body);

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Interrupt HTTP error: " + response.code());
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    String trimmedBody = body.trim();
                    if (trimmedBody.isEmpty() || !trimmedBody.startsWith("{")) {
                        // For interrupt, treat non-JSON as success since the main goal is to stop speech
                        Log.w(TAG, "Non-JSON interrupt response, treating as success");
                        ActionResponse fallback = new ActionResponse();
                        fallback.success = true;
                        callback.onSuccess(fallback);
                        return;
                    }

                    ActionResponse actionResponse = gson.fromJson(body, ActionResponse.class);
                    callback.onSuccess(actionResponse != null ? actionResponse : new ActionResponse());
                } catch (Exception e) {
                    Log.e(TAG, "Parse interrupt response failed", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Stop a HeyGen session
     *
     * @param sessionId Session ID to stop
     * @param clientId Optional client ID
     * @param callback Response callback
     */
    public void stopSession(@Nullable String sessionId, @Nullable String clientId, @NonNull ActionCallback callback) {
        String url = baseUrl + "/heygen/stop";

        SessionActionRequest request = new SessionActionRequest(sessionId, clientId);
        String json = gson.toJson(request);

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .build();

        Log.d(TAG, "Stopping session: " + sessionId);

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Stop session failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Stop response (code " + response.code() + "): " + body);

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Stop session HTTP error: " + response.code());
                        callback.onError("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    String trimmedBody = body.trim();
                    if (trimmedBody.isEmpty() || !trimmedBody.startsWith("{")) {
                        // For stop, treat non-JSON as success since session cleanup is the goal
                        Log.w(TAG, "Non-JSON stop response, treating as success");
                        ActionResponse fallback = new ActionResponse();
                        fallback.success = true;
                        callback.onSuccess(fallback);
                        return;
                    }

                    ActionResponse actionResponse = gson.fromJson(body, ActionResponse.class);
                    callback.onSuccess(actionResponse != null ? actionResponse : new ActionResponse());
                } catch (Exception e) {
                    Log.e(TAG, "Parse stop response failed", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }
}
