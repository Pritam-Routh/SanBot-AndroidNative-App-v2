package com.tripandevent.sanbotvoice.openai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.tripandevent.sanbotvoice.api.TokenManager;
import com.tripandevent.sanbotvoice.openai.events.ClientEvents;
import com.tripandevent.sanbotvoice.openai.events.ServerEvents;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * OpenAI Realtime API WebSocket Manager with Low-Latency Audio Streaming
 *
 * Key optimizations for minimal latency:
 * 1. Dedicated audio playback thread - doesn't block WebSocket thread
 * 2. Concurrent queue for audio chunks - smooth buffering
 * 3. Minimal buffer size with low-latency AudioTrack mode
 * 4. Non-blocking message handling
 *
 * Audio Format: PCM 24kHz, 16-bit signed, mono (48 bytes per ms)
 */
public class OpenAIWebSocketManager {

    private static final String TAG = Constants.TAG_OPENAI;
    private static final String OPENAI_REALTIME_WS_URL = "wss://api.openai.com/v1/realtime";

    // Audio format constants (OpenAI output format)
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_MS = 48;  // 24000 samples/sec * 2 bytes/sample / 1000

    private final Context context;
    private final Handler mainHandler;
    private final OpenAICallback callback;

    private OkHttpClient client;
    private WebSocket webSocket;
    private AudioTrack audioTrack;

    // Thread pools for non-blocking operations
    private ExecutorService audioPlaybackExecutor;
    private ExecutorService audioInputExecutor;

    // Audio playback queue for smooth streaming
    private final ConcurrentLinkedQueue<byte[]> audioQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isPlaybackRunning = new AtomicBoolean(false);

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean isMuted = new AtomicBoolean(false);

    // Audio playback tracking
    private volatile String currentAudioItemId = null;
    private final AtomicLong audioPlayedBytes = new AtomicLong(0);

    /**
     * Callback interface for OpenAI events
     */
    public interface OpenAICallback {
        void onConnected();
        void onDisconnected(String reason);
        void onError(String error);
        void onServerEvent(ServerEvents.ParsedEvent event);
        void onSpeechStarted();
        void onSpeechStopped();
        /**
         * Called when audio data is received (for CUSTOM mode avatar streaming)
         * @param pcmData Raw PCM audio bytes (24kHz, 16-bit, mono)
         */
        void onAudioData(byte[] pcmData);
    }

    public OpenAIWebSocketManager(Context context, OpenAICallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Create OkHttp client optimized for real-time
        this.client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Initialize audio playback with low-latency configuration
     */
    public void initialize() {
        Logger.d(TAG, "Initializing OpenAI WebSocket manager with low-latency audio...");

        // Create thread pools
        audioPlaybackExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AudioPlayback");
            t.setPriority(Thread.MAX_PRIORITY);  // High priority for audio
            return t;
        });
        audioInputExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AudioInput");
            t.setPriority(Thread.MAX_PRIORITY - 1);
            return t;
        });

        // Create AudioTrack with minimal buffer for lowest latency
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

        // Use minimum viable buffer - just enough to avoid underruns
        // On most devices, minBufferSize is around 1920-3840 bytes (40-80ms)
        int bufferSize = minBufferSize;

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

        audioTrack.play();

        // Start the audio playback loop
        startAudioPlaybackLoop();

        Logger.i(TAG, "AudioTrack initialized: buffer=%d bytes (%dms), minBuffer=%d",
            bufferSize, bufferSize / BYTES_PER_MS, minBufferSize);
    }

    /**
     * Start the dedicated audio playback thread
     * This thread continuously reads from the queue and writes to AudioTrack
     * Optimized for lowest latency audio playback
     */
    private void startAudioPlaybackLoop() {
        isPlaybackRunning.set(true);
        audioPlaybackExecutor.execute(() -> {
            Logger.d(TAG, "Audio playback loop started (low-latency mode)");

            while (isPlaybackRunning.get()) {
                byte[] chunk = audioQueue.poll();

                if (chunk != null) {
                    // Write to AudioTrack (blocking, but on dedicated thread)
                    if (!isMuted.get() && audioTrack != null &&
                        audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        int written = audioTrack.write(chunk, 0, chunk.length);
                        if (written > 0) {
                            audioPlayedBytes.addAndGet(written);
                        }
                    }
                } else {
                    // No data available - minimal sleep to reduce latency
                    // 1ms is the smallest practical sleep on most Android devices
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            Logger.d(TAG, "Audio playback loop ended");
        });
    }

    /**
     * Connect to OpenAI Realtime API via WebSocket
     */
    public void connect() {
        if (isConnected.get()) {
            Logger.w(TAG, "Already connected");
            return;
        }

        if (!isConnecting.compareAndSet(false, true)) {
            Logger.w(TAG, "Connection already in progress");
            return;
        }

        Logger.d(TAG, "Connecting to OpenAI Realtime API via WebSocket...");

        // Get ephemeral token
        TokenManager.getInstance().getToken(new TokenManager.TokenCallback() {
            @Override
            public void onTokenReceived(String token) {
                Logger.d(TAG, "Token received, establishing WebSocket connection...");
                connectWithToken(token);
            }

            @Override
            public void onError(Exception e) {
                Logger.e(e, "Failed to get ephemeral token");
                isConnecting.set(false);
                notifyError("Failed to get authentication token: " + e.getMessage());
            }
        });
    }

    private void connectWithToken(String token) {
        String url = OPENAI_REALTIME_WS_URL + "?model=" + Constants.OPENAI_REALTIME_MODEL;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Logger.i(TAG, "WebSocket connected to OpenAI");
                isConnecting.set(false);
                isConnected.set(true);

                // Send initial session configuration
                configureSession();

                notifyConnected();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // Handle message without blocking - all heavy work is async
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Logger.e(TAG, "WebSocket failure: " + t.getMessage());
                isConnecting.set(false);
                isConnected.set(false);
                notifyError("WebSocket connection failed: " + t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Logger.d(TAG, "WebSocket closed: %s (code: %d)", reason, code);
                isConnected.set(false);
                notifyDisconnected(reason);
            }
        });
    }

    /**
     * Configure OpenAI session (GA WebSocket API format)
     *
     * NOTE: This sends a minimal session update to configure audio format and VAD.
     * The full configuration with tools and instructions is sent separately from
     * VoiceAgentService after the session is established.
     */
    private void configureSession() {
        // Skip initial configuration - let VoiceAgentService send the full config
        // This avoids duplicate session.update events which can cause errors
        Logger.d(TAG, "Skipping initial session config - will be configured from VoiceAgentService");
    }

    /**
     * Handle incoming WebSocket message
     *
     * Simple approach: Let OpenAI's semantic_vad handle all turn-taking.
     * We just play audio when received and forward events.
     */
    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "");

            // Fast path for audio delta - most frequent event, just play it
            if ("response.output_audio.delta".equals(type) || "response.audio.delta".equals(type)) {
                handleAudioDelta(json);
                return;
            }

            // Log errors
            if ("error".equals(type)) {
                Logger.e(TAG, "OpenAI error: %s", message);
            }

            // Parse and forward event to callback
            ServerEvents.ParsedEvent event = ServerEvents.parse(message);

            // Simple logging for key events
            if (event.isSpeechStarted()) {
                Logger.d(TAG, "User speech started");
                clearLocalAudio();  // Clear audio buffer when user speaks
                notifySpeechStarted();
            } else if (event.isSpeechStopped()) {
                Logger.d(TAG, "User speech stopped");
                notifySpeechStopped();
            } else if (event.isResponseCreated()) {
                Logger.d(TAG, "AI response started");
            } else if (event.isResponseDone()) {
                Logger.d(TAG, "AI response done");
            } else if (event.isResponseCancelled()) {
                Logger.d(TAG, "AI response cancelled (barge-in)");
                clearLocalAudio();
            }

            // Forward all events to callback
            if (event.isValid()) {
                notifyServerEvent(event);
            }

        } catch (Exception e) {
            Logger.w(TAG, "Failed to parse message: " + e.getMessage());
        }
    }

    /**
     * Handle audio delta - just decode and play
     */
    private void handleAudioDelta(JSONObject json) {
        try {
            String audioBase64 = json.optString("delta", null);
            if (audioBase64 == null || audioBase64.isEmpty()) {
                return;
            }

            // Decode audio
            byte[] pcmData = Base64.decode(audioBase64, Base64.NO_WRAP);

            // Track for debugging
            String itemId = json.optString("item_id", null);
            if (itemId != null && !itemId.equals(currentAudioItemId)) {
                currentAudioItemId = itemId;
                audioPlayedBytes.set(0);
                Logger.d(TAG, "New audio: item_id=%s", itemId);
            }
            audioPlayedBytes.addAndGet(pcmData.length);

            // Add to playback queue
            audioQueue.offer(pcmData);

            // Forward to callback for avatar
            if (callback != null) {
                final byte[] audioData = pcmData;
                mainHandler.post(() -> callback.onAudioData(audioData));
            }

        } catch (Exception e) {
            Logger.w(TAG, "Error handling audio delta: " + e.getMessage());
        }
    }

    /**
     * Send event to OpenAI
     */
    public void sendEvent(String eventJson) {
        if (webSocket == null || !isConnected.get()) {
            Logger.w(TAG, "Cannot send event - not connected");
            return;
        }

        webSocket.send(eventJson);
    }

    /**
     * Send audio input to OpenAI - non-blocking
     * @param pcmData Raw PCM audio bytes (24kHz, 16-bit, mono)
     */
    public void sendAudioInput(byte[] pcmData) {
        if (!isConnected.get() || audioInputExecutor == null) {
            return;
        }

        // Send on dedicated thread to avoid blocking caller
        final byte[] audioData = pcmData;
        audioInputExecutor.execute(() -> {
            try {
                String base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP);

                JSONObject event = new JSONObject();
                event.put("type", "input_audio_buffer.append");
                event.put("audio", base64Audio);

                if (webSocket != null && isConnected.get()) {
                    webSocket.send(event.toString());
                }
            } catch (JSONException e) {
                Logger.e(e, "Failed to send audio input");
            }
        });
    }

    /**
     * Commit audio buffer (triggers response)
     */
    public void commitAudioBuffer() {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "input_audio_buffer.commit");
            sendEvent(event.toString());
        } catch (JSONException e) {
            Logger.e(e, "Failed to commit audio buffer");
        }
    }

    /**
     * Clear output audio buffer and local playback
     */
    public void clearOutputAudioBuffer() {
        sendEvent(ClientEvents.outputAudioBufferClear());
        clearLocalAudio();
    }

    /**
     * Clear local audio queue and flush AudioTrack
     */
    private void clearLocalAudio() {
        // Clear the queue
        audioQueue.clear();

        // Flush AudioTrack
        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.play();
        }
    }

    /**
     * Trigger a response from the model
     */
    public void createResponse() {
        sendEvent(ClientEvents.responseCreate());
    }

    /**
     * Cancel the current response
     */
    public void cancelResponse() {
        sendEvent(ClientEvents.responseCancel());
    }

    /**
     * Update session configuration
     */
    public void updateSession(ClientEvents.SessionConfig config) {
        String event = ClientEvents.sessionUpdate(config);
        sendEvent(event);
    }

    /**
     * Send function call output
     */
    public void sendFunctionCallOutput(String callId, String output) {
        String event = ClientEvents.conversationItemCreateFunctionOutput(callId, output);
        sendEvent(event);
        createResponse();
    }

    /**
     * Mute/unmute local audio playback
     */
    public void setLocalAudioMuted(boolean muted) {
        isMuted.set(muted);
        Logger.d(TAG, "Local audio %s", muted ? "muted" : "unmuted");
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Disconnect from OpenAI
     */
    public void disconnect() {
        Logger.d(TAG, "Disconnecting...");

        isConnected.set(false);
        isConnecting.set(false);

        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }

        notifyDisconnected("User initiated disconnect");
    }

    /**
     * Release all resources
     */
    public void release() {
        disconnect();

        // Stop playback loop
        isPlaybackRunning.set(false);

        // Shutdown thread pools
        if (audioPlaybackExecutor != null) {
            audioPlaybackExecutor.shutdownNow();
            audioPlaybackExecutor = null;
        }
        if (audioInputExecutor != null) {
            audioInputExecutor.shutdownNow();
            audioInputExecutor = null;
        }

        // Release AudioTrack
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        // Clear queue
        audioQueue.clear();

        Logger.d(TAG, "OpenAI WebSocket manager released");
    }

    // ============================================
    // NOTIFICATION HELPERS - Non-blocking
    // ============================================

    private void notifyConnected() {
        mainHandler.post(() -> {
            if (callback != null) callback.onConnected();
        });
    }

    private void notifyDisconnected(String reason) {
        mainHandler.post(() -> {
            if (callback != null) callback.onDisconnected(reason);
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    private void notifySpeechStarted() {
        mainHandler.post(() -> {
            if (callback != null) callback.onSpeechStarted();
        });
    }

    private void notifySpeechStopped() {
        mainHandler.post(() -> {
            if (callback != null) callback.onSpeechStopped();
        });
    }

    private void notifyServerEvent(ServerEvents.ParsedEvent event) {
        mainHandler.post(() -> {
            if (callback != null) callback.onServerEvent(event);
        });
    }
}
