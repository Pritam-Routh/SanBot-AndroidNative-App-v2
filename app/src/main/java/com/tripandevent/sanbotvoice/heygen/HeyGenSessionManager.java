package com.tripandevent.sanbotvoice.heygen;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HeyGenSessionManager
 *
 * Manages the lifecycle of HeyGen avatar sessions.
 * Coordinates between the API client, text buffer, and video manager.
 *
 * State machine:
 * IDLE -> CREATING -> CONNECTING -> ACTIVE -> STOPPING -> IDLE
 *                                          \-> ERROR
 */
public class HeyGenSessionManager implements TextDeltaBuffer.FlushCallback {
    private static final String TAG = "HeyGenSessionManager";

    // Session states
    public enum State {
        IDLE,           // No session
        CREATING,       // Creating session via API
        CONNECTING,     // Connecting LiveKit video
        ACTIVE,         // Session active, accepting text
        STOPPING,       // Stopping session
        ERROR           // Error state
    }

    // Current state
    private State state = State.IDLE;

    // Session data
    private String clientId;
    private String sessionId;
    private String liveKitUrl;
    private String liveKitToken;

    // Components
    private final HeyGenApiClient apiClient;
    private final TextDeltaBuffer textBuffer;
    private HeyGenVideoManager videoManager;

    // Handler for main thread callbacks
    private final Handler mainHandler;

    // Callbacks
    private SessionListener listener;

    // Atomic flags
    private final AtomicBoolean isInterrupting = new AtomicBoolean(false);

    // Context for video manager
    private Context context;

    public interface SessionListener {
        void onSessionCreated(String sessionId);
        void onSessionConnected();
        void onSessionError(String error);
        void onSessionStopped();
        void onVideoTrackReady();
    }

    public HeyGenSessionManager() {
        this.apiClient = HeyGenApiClient.getInstance();
        this.textBuffer = new TextDeltaBuffer();
        this.textBuffer.setCallback(this);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.clientId = generateClientId();
    }

    /**
     * Set the context (required for video manager)
     */
    public void setContext(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Set the session listener
     */
    public void setListener(SessionListener listener) {
        this.listener = listener;
    }

    /**
     * Get current state
     */
    public State getState() {
        return state;
    }

    /**
     * Check if session is active
     */
    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /**
     * Check if session is ready to receive text
     */
    public boolean isReady() {
        return state == State.ACTIVE && sessionId != null;
    }

    /**
     * Get the video manager for UI binding
     */
    @Nullable
    public HeyGenVideoManager getVideoManager() {
        return videoManager;
    }

    /**
     * Start a new HeyGen session
     *
     * @param avatarId Optional avatar ID (uses default if null)
     */
    public void startSession(@Nullable String avatarId) {
        if (state != State.IDLE && state != State.ERROR) {
            Log.w(TAG, "Cannot start session in state: " + state);
            return;
        }

        if (!HeyGenConfig.isEnabled()) {
            Log.d(TAG, "HeyGen avatar is disabled");
            return;
        }

        Log.d(TAG, "Starting HeyGen session...");
        setState(State.CREATING);

        String avatar = avatarId != null ? avatarId : HeyGenConfig.getAvatarId();

        apiClient.createSession(clientId, avatar, new HeyGenApiClient.SessionCallback() {
            @Override
            public void onSuccess(HeyGenApiClient.SessionResponse response) {
                mainHandler.post(() -> handleSessionCreated(response));
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> handleError("Session creation failed: " + error));
            }
        });
    }

    /**
     * Handle successful session creation
     */
    private void handleSessionCreated(HeyGenApiClient.SessionResponse response) {
        if (state != State.CREATING) {
            Log.w(TAG, "Unexpected session created in state: " + state);
            return;
        }

        sessionId = response.sessionId;
        liveKitUrl = response.liveKitUrl;
        liveKitToken = response.liveKitToken;

        Log.d(TAG, "Session created: " + sessionId);

        if (listener != null) {
            listener.onSessionCreated(sessionId);
        }

        // Connect video stream
        connectVideo();
    }

    /**
     * Connect to LiveKit video stream
     */
    private void connectVideo() {
        if (context == null) {
            handleError("Context not set for video manager");
            return;
        }

        setState(State.CONNECTING);

        videoManager = new HeyGenVideoManager(context);
        videoManager.setListener(new HeyGenVideoManager.VideoListener() {
            @Override
            public void onConnected() {
                mainHandler.post(() -> handleVideoConnected());
            }

            @Override
            public void onVideoTrackReceived() {
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onVideoTrackReady();
                    }
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> handleError("Video connection failed: " + error));
            }

            @Override
            public void onDisconnected() {
                mainHandler.post(() -> {
                    Log.w(TAG, "*** VIDEO DISCONNECTED *** Current state: " + state);
                    Log.w(TAG, "sessionId: " + sessionId);
                    // Only treat as error if we were in ACTIVE state
                    // and the disconnect was unexpected
                    if (state == State.ACTIVE) {
                        Log.w(TAG, "Treating as error - was in ACTIVE state");
                        handleError("Video disconnected unexpectedly");
                    } else {
                        Log.d(TAG, "Not treating as error - state was: " + state);
                    }
                });
            }
        });

        videoManager.connect(liveKitUrl, liveKitToken);
    }

    /**
     * Handle video connection success
     */
    private void handleVideoConnected() {
        if (state != State.CONNECTING) {
            Log.w(TAG, "Unexpected video connected in state: " + state);
            return;
        }

        Log.d(TAG, "Video connected");
        setState(State.ACTIVE);
        textBuffer.setEnabled(true);

        if (listener != null) {
            listener.onSessionConnected();
        }
    }

    /**
     * Add a text delta for the avatar to speak
     * The buffer will accumulate and batch deltas for smooth speech.
     *
     * @param delta Text delta from OpenAI transcript
     */
    public void addTextDelta(String delta) {
        if (!isReady()) {
            return;
        }
        textBuffer.addDelta(delta);
    }

    /**
     * Force flush any buffered text (call when response complete)
     */
    public void flushBuffer() {
        textBuffer.forceFlush();
    }

    /**
     * Callback from TextDeltaBuffer when text should be sent
     */
    @Override
    public void onFlush(String text) {
        if (!isReady() || text == null || text.isEmpty()) {
            return;
        }

        Log.d(TAG, "Sending text to avatar: " + text.substring(0, Math.min(50, text.length())) + "...");

        apiClient.streamText(sessionId, text, new HeyGenApiClient.StreamCallback() {
            @Override
            public void onSuccess(HeyGenApiClient.StreamResponse response) {
                Log.d(TAG, "Text streamed, taskId: " + response.taskId);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Stream text error: " + error);
            }
        });
    }

    /**
     * Interrupt the avatar's current speech (for barge-in)
     */
    public void interrupt() {
        if (!isReady() || sessionId == null) {
            return;
        }

        // Prevent multiple simultaneous interrupts
        if (!isInterrupting.compareAndSet(false, true)) {
            return;
        }

        Log.d(TAG, "Interrupting avatar");

        // Clear pending text
        textBuffer.clear();

        apiClient.interrupt(sessionId, new HeyGenApiClient.ActionCallback() {
            @Override
            public void onSuccess(HeyGenApiClient.ActionResponse response) {
                isInterrupting.set(false);
                Log.d(TAG, "Avatar interrupted");
            }

            @Override
            public void onError(String error) {
                isInterrupting.set(false);
                Log.e(TAG, "Interrupt failed: " + error);
            }
        });
    }

    /**
     * Stop the current session
     */
    public void stopSession() {
        if (state == State.IDLE || state == State.STOPPING) {
            return;
        }

        Log.d(TAG, "Stopping HeyGen session");
        setState(State.STOPPING);

        // Disable and clear buffer
        textBuffer.setEnabled(false);
        textBuffer.clear();

        // Disconnect video
        if (videoManager != null) {
            videoManager.disconnect();
            videoManager = null;
        }

        // Stop session via API
        if (sessionId != null) {
            apiClient.stopSession(sessionId, clientId, new HeyGenApiClient.ActionCallback() {
                @Override
                public void onSuccess(HeyGenApiClient.ActionResponse response) {
                    mainHandler.post(() -> handleSessionStopped());
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Stop session error: " + error);
                    mainHandler.post(() -> handleSessionStopped());
                }
            });
        } else {
            handleSessionStopped();
        }
    }

    /**
     * Handle session stop completion
     */
    private void handleSessionStopped() {
        sessionId = null;
        liveKitUrl = null;
        liveKitToken = null;

        setState(State.IDLE);

        if (listener != null) {
            listener.onSessionStopped();
        }
    }

    /**
     * Handle errors
     */
    private void handleError(String error) {
        Log.e(TAG, "Session error: " + error);
        setState(State.ERROR);

        // Cleanup
        textBuffer.setEnabled(false);
        textBuffer.clear();

        if (videoManager != null) {
            videoManager.disconnect();
            videoManager = null;
        }

        if (listener != null) {
            listener.onSessionError(error);
        }
    }

    /**
     * Set state and log transition
     */
    private void setState(State newState) {
        if (state != newState) {
            Log.d(TAG, "State: " + state + " -> " + newState);
            state = newState;
        }
    }

    /**
     * Generate a unique client ID for this device
     */
    private String generateClientId() {
        return "android-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Cleanup resources
     */
    public void destroy() {
        stopSession();
        textBuffer.destroy();
        listener = null;
    }
}
