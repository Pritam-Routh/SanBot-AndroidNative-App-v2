# LiveAvatar Integration Plan - SanBot Android App

## Executive Summary

This document provides a comprehensive implementation plan for integrating LiveAvatar video streaming with the SanBot Android voice agent app. The goal is to replace the current HeyGen avatar implementation with LiveAvatar while achieving the **lowest possible latency** for real-time video streaming.

---

## Current Architecture vs Target Architecture

### Current Flow (HeyGen)
```
User Speech → Microphone → WebRTC → OpenAI Realtime API
                                          ↓
                              Text Transcript Delta
                                          ↓
                              TextDeltaBuffer (batching)
                                          ↓
                              Backend → HeyGen API
                                          ↓
                              LiveKit → Video Display
```
**Latency: 400-1200ms**

### Target Flow (LiveAvatar - Option A: Text-Based, Lowest Integration Effort)
```
User Speech → Microphone → WebRTC → OpenAI Realtime API
                                          ↓
                              Text Transcript Delta
                                          ↓
                       Direct SDK Message (no batching)
                                          ↓
                              LiveAvatar SDK → Video
```
**Expected Latency: 300-800ms**

### Target Flow (LiveAvatar - Option B: Audio-Based, Lowest Latency)
```
User Speech → Microphone → WebRTC → OpenAI Realtime API
                                          ↓
                              Audio PCM Delta (24kHz)
                                          ↓
                              LiveAvatar repeatAudio()
                                          ↓
                              LiveAvatar SDK → Video
```
**Expected Latency: 200-500ms** (eliminates TTS latency)

---

## LiveAvatar SDK Architecture Summary

### Core Components
| Component | Purpose |
|-----------|---------|
| `LiveAvatarSession` | Main orchestrator - manages connection, streams, events |
| `SessionAPIClient` | REST API for session start/stop/keep-alive |
| `VoiceChat` | Microphone capture and transmission |
| `ConnectionQualityIndicator` | Network quality monitoring |

### Communication Protocols
1. **REST API** - Session management (`/v1/sessions/start`, `/stop`, `/keep-alive`)
2. **LiveKit WebRTC** - Video/audio streaming + data channels
3. **WebSocket** (optional) - Real-time event streaming

### Key Methods
```
session.start()           → Connect to LiveAvatar
session.stop()            → Disconnect
session.message(text)     → Send text for AI response
session.repeat(text)      → Make avatar speak text directly
session.repeatAudio(pcm)  → Make avatar speak PCM audio
session.interrupt()       → Stop avatar mid-speech
session.startListening()  → Enable voice input mode
session.stopListening()   → Disable voice input mode
```

### Events (Server → Client)
```
USER_SPEAK_STARTED       → User began speaking
USER_SPEAK_ENDED         → User stopped speaking
USER_TRANSCRIPTION       → User speech transcribed
AVATAR_TRANSCRIPTION     → Avatar response text
AVATAR_SPEAK_STARTED     → Avatar began speaking
AVATAR_SPEAK_ENDED       → Avatar finished speaking
SESSION_STATE_CHANGED    → Connection state changed
SESSION_STREAM_READY     → Video/audio ready
```

### Audio Format
- **PCM 24kHz, 16-bit signed mono** (matches OpenAI format!)
- 20ms chunks (960 bytes per chunk)

---

## Implementation Plan

### Phase 1: Backend Updates (Node.js)

#### 1.1 Add LiveAvatar Session Endpoints

**File: `/routes/liveavatar.js` (NEW)**

```javascript
const express = require('express');
const router = express.Router();
const axios = require('axios');

const LIVEAVATAR_API_URL = 'https://api.liveavatar.com';
const LIVEAVATAR_API_KEY = process.env.LIVEAVATAR_API_KEY;

// Start LiveAvatar session
router.post('/session/start', async (req, res) => {
  try {
    const { mode, avatar_id, voice_id, context_id, language } = req.body;

    const response = await axios.post(
      `${LIVEAVATAR_API_URL}/v1/sessions/token`,
      {
        mode: mode || 'CUSTOM', // CUSTOM for external AI, FULL for built-in
        avatar_id,
        avatar_persona: mode === 'FULL' ? {
          voice_id,
          context_id,
          language
        } : undefined,
        is_sandbox: process.env.NODE_ENV !== 'production'
      },
      {
        headers: {
          'Authorization': `Bearer ${LIVEAVATAR_API_KEY}`,
          'Content-Type': 'application/json'
        }
      }
    );

    res.json({
      success: true,
      data: {
        session_token: response.data.data.session_token,
        session_id: response.data.data.session_id
      }
    });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Stop LiveAvatar session
router.post('/session/stop', async (req, res) => {
  try {
    const { session_token } = req.body;

    await axios.post(
      `${LIVEAVATAR_API_URL}/v1/sessions/stop`,
      {},
      {
        headers: {
          'Authorization': `Bearer ${session_token}`,
          'Content-Type': 'application/json'
        }
      }
    );

    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Keep session alive
router.post('/session/keep-alive', async (req, res) => {
  try {
    const { session_token } = req.body;

    await axios.post(
      `${LIVEAVATAR_API_URL}/v1/sessions/keep-alive`,
      {},
      {
        headers: {
          'Authorization': `Bearer ${session_token}`,
          'Content-Type': 'application/json'
        }
      }
    );

    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

module.exports = router;
```

#### 1.2 Environment Variables

Add to `.env`:
```
LIVEAVATAR_API_KEY=your_api_key_here
LIVEAVATAR_AVATAR_ID=default_avatar_id
LIVEAVATAR_MODE=CUSTOM
```

---

### Phase 2: Android App - Core Classes

#### 2.1 LiveAvatar API Client

**File: `app/src/main/java/com/tripandevent/sanbotvoice/liveavatar/LiveAvatarApiClient.java`**

```java
package com.tripandevent.sanbotvoice.liveavatar;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.gson.Gson;
import okhttp3.*;
import java.io.IOException;

public class LiveAvatarApiClient {
    private static final String TAG = "LiveAvatarApiClient";
    private static final String LIVEAVATAR_API_URL = "https://api.liveavatar.com";

    private final OkHttpClient client;
    private final Gson gson;
    private String sessionToken;

    public interface SessionCallback {
        void onSuccess(SessionInfo sessionInfo);
        void onError(String error);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(String error);
    }

    public LiveAvatarApiClient() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.gson = new Gson();
    }

    public void setSessionToken(String token) {
        this.sessionToken = token;
    }

    public void startSession(@NonNull SessionCallback callback) {
        Request request = new Request.Builder()
            .url(LIVEAVATAR_API_URL + "/v1/sessions/start")
            .addHeader("Authorization", "Bearer " + sessionToken)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("{}", MediaType.parse("application/json")))
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    StartSessionResponse resp = gson.fromJson(json, StartSessionResponse.class);
                    if (resp.code == 1000) {
                        callback.onSuccess(resp.data);
                    } else {
                        callback.onError("API error: " + resp.message);
                    }
                } else {
                    callback.onError("HTTP " + response.code());
                }
            }
        });
    }

    public void stopSession(@NonNull SimpleCallback callback) {
        Request request = new Request.Builder()
            .url(LIVEAVATAR_API_URL + "/v1/sessions/stop")
            .addHeader("Authorization", "Bearer " + sessionToken)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("{}", MediaType.parse("application/json")))
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    callback.onError("HTTP " + response.code());
                }
            }
        });
    }

    public void keepAlive(@NonNull SimpleCallback callback) {
        Request request = new Request.Builder()
            .url(LIVEAVATAR_API_URL + "/v1/sessions/keep-alive")
            .addHeader("Authorization", "Bearer " + sessionToken)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("{}", MediaType.parse("application/json")))
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful()) {
                    callback.onSuccess();
                } else {
                    callback.onError("HTTP " + response.code());
                }
            }
        });
    }

    // Response models
    public static class StartSessionResponse {
        public int code;
        public String message;
        public SessionInfo data;
    }

    public static class SessionInfo {
        public String session_id;
        public Integer max_session_duration;
        public String livekit_url;
        public String livekit_client_token;
        public String ws_url;
    }
}
```

#### 2.2 LiveAvatar Session Manager

**File: `app/src/main/java/com/tripandevent/sanbotvoice/liveavatar/LiveAvatarSessionManager.java`**

```java
package com.tripandevent.sanbotvoice.liveavatar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.livekit.android.LiveKit;
import io.livekit.android.room.Room;
import io.livekit.android.room.RoomException;
import io.livekit.android.room.participant.RemoteParticipant;
import io.livekit.android.room.track.RemoteAudioTrack;
import io.livekit.android.room.track.RemoteVideoTrack;
import io.livekit.android.room.track.Track;

import com.google.gson.Gson;

import okhttp3.*;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class LiveAvatarSessionManager {
    private static final String TAG = "LiveAvatarSessionMgr";

    public enum SessionState {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED
    }

    public interface SessionListener {
        void onStateChanged(SessionState state);
        void onStreamReady();
        void onConnectionQualityChanged(String quality);
        void onUserTranscription(String text);
        void onAvatarTranscription(String text);
        void onAvatarSpeakStarted();
        void onAvatarSpeakEnded();
        void onError(String error);
    }

    private final Context context;
    private final LiveAvatarApiClient apiClient;
    private final Handler mainHandler;
    private final Gson gson;

    private Room room;
    private WebSocket webSocket;
    private String sessionToken;
    private LiveAvatarApiClient.SessionInfo sessionInfo;

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);
    private SessionListener listener;
    private SurfaceView videoView;

    private RemoteVideoTrack remoteVideoTrack;
    private RemoteAudioTrack remoteAudioTrack;
    private boolean streamReady = false;

    // Keep-alive timer
    private final Handler keepAliveHandler = new Handler(Looper.getMainLooper());
    private static final long KEEP_ALIVE_INTERVAL_MS = 30000; // 30 seconds

    public LiveAvatarSessionManager(@NonNull Context context) {
        this.context = context;
        this.apiClient = new LiveAvatarApiClient();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
    }

    public void setListener(@Nullable SessionListener listener) {
        this.listener = listener;
    }

    public void setVideoView(@NonNull SurfaceView view) {
        this.videoView = view;
    }

    public SessionState getState() {
        return state.get();
    }

    public void start(@NonNull String sessionToken) {
        if (state.get() != SessionState.IDLE) {
            Log.w(TAG, "Cannot start: state is " + state.get());
            return;
        }

        this.sessionToken = sessionToken;
        this.apiClient.setSessionToken(sessionToken);

        setState(SessionState.CONNECTING);

        apiClient.startSession(new LiveAvatarApiClient.SessionCallback() {
            @Override
            public void onSuccess(LiveAvatarApiClient.SessionInfo info) {
                sessionInfo = info;
                mainHandler.post(() -> connectToLiveKit(info));
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    notifyError("Session start failed: " + error);
                    setState(SessionState.DISCONNECTED);
                });
            }
        });
    }

    private void connectToLiveKit(LiveAvatarApiClient.SessionInfo info) {
        if (info.livekit_url == null || info.livekit_client_token == null) {
            Log.w(TAG, "No LiveKit credentials, using WebSocket only");
            connectWebSocket(info.ws_url);
            return;
        }

        try {
            room = LiveKit.create(context);

            // Set up room event listeners
            room.getEvents().collect(event -> {
                handleRoomEvent(event);
                return null;
            });

            // Connect to LiveKit room
            room.connect(info.livekit_url, info.livekit_client_token, null);

            // Also connect WebSocket if available
            if (info.ws_url != null) {
                connectWebSocket(info.ws_url);
            }

            setState(SessionState.CONNECTED);
            startKeepAlive();

        } catch (Exception e) {
            Log.e(TAG, "LiveKit connection failed", e);
            notifyError("Connection failed: " + e.getMessage());
            setState(SessionState.DISCONNECTED);
        }
    }

    private void connectWebSocket(String wsUrl) {
        if (wsUrl == null) return;

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(wsUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket connected");
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                handleWebSocketMessage(text);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                                  @Nullable Response response) {
                Log.e(TAG, "WebSocket failure", t);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "WebSocket closed: " + reason);
            }
        });
    }

    private void handleRoomEvent(Object event) {
        // Handle LiveKit room events
        // Track subscription, data channel messages, etc.
    }

    private void handleWebSocketMessage(String message) {
        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject(message);
                String type = json.optString("type", "");

                switch (type) {
                    case "user.transcription":
                        if (listener != null) {
                            listener.onUserTranscription(json.optString("text", ""));
                        }
                        break;
                    case "avatar.transcription":
                        if (listener != null) {
                            listener.onAvatarTranscription(json.optString("text", ""));
                        }
                        break;
                    case "avatar.speak_started":
                        if (listener != null) {
                            listener.onAvatarSpeakStarted();
                        }
                        break;
                    case "avatar.speak_ended":
                        if (listener != null) {
                            listener.onAvatarSpeakEnded();
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing WebSocket message", e);
            }
        });
    }

    // ========== COMMANDS ==========

    /**
     * Send text message for AI response (FULL mode)
     */
    public void message(@NonNull String text) {
        sendCommand("avatar.speak_response", text, null);
    }

    /**
     * Make avatar repeat text directly (CUSTOM mode)
     * LOWEST LATENCY for text-based streaming
     */
    public void repeat(@NonNull String text) {
        sendCommand("avatar.speak_text", text, null);
    }

    /**
     * Make avatar speak PCM audio directly (CUSTOM mode)
     * LOWEST LATENCY OPTION - bypasses TTS completely
     * @param pcmAudio Base64 encoded PCM 24kHz 16-bit mono audio
     */
    public void repeatAudio(@NonNull String pcmAudio) {
        sendCommand("avatar.speak_audio", null, pcmAudio);
    }

    /**
     * Interrupt avatar mid-speech
     */
    public void interrupt() {
        sendCommand("avatar.interrupt", null, null);
    }

    /**
     * Enable listening mode (avatar listens to user)
     */
    public void startListening() {
        sendCommand("avatar.start_listening", null, null);
    }

    /**
     * Disable listening mode
     */
    public void stopListening() {
        sendCommand("avatar.stop_listening", null, null);
    }

    private void sendCommand(String eventType, @Nullable String text, @Nullable String audio) {
        if (state.get() != SessionState.CONNECTED) {
            Log.w(TAG, "Cannot send command: not connected");
            return;
        }

        try {
            JSONObject command = new JSONObject();
            command.put("event_type", eventType);
            command.put("event_id", UUID.randomUUID().toString());
            if (text != null) command.put("text", text);
            if (audio != null) command.put("audio", audio);

            // Prefer WebSocket for lowest latency
            if (webSocket != null) {
                webSocket.send(command.toString());
            } else if (room != null) {
                // Fallback to LiveKit data channel
                room.getLocalParticipant().publishData(
                    command.toString().getBytes(),
                    Room.DataPublishReliability.RELIABLE,
                    "agent-control"
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending command", e);
        }
    }

    // ========== LIFECYCLE ==========

    public void stop() {
        if (state.get() == SessionState.IDLE || state.get() == SessionState.DISCONNECTED) {
            return;
        }

        setState(SessionState.DISCONNECTING);
        stopKeepAlive();

        // Close WebSocket
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }

        // Disconnect LiveKit room
        if (room != null) {
            room.disconnect();
            room = null;
        }

        // Stop session on server
        apiClient.stopSession(new LiveAvatarApiClient.SimpleCallback() {
            @Override
            public void onSuccess() {
                mainHandler.post(() -> setState(SessionState.DISCONNECTED));
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Stop session error: " + error);
                mainHandler.post(() -> setState(SessionState.DISCONNECTED));
            }
        });

        cleanup();
    }

    private void cleanup() {
        remoteVideoTrack = null;
        remoteAudioTrack = null;
        streamReady = false;
        sessionInfo = null;
    }

    private void startKeepAlive() {
        keepAliveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (state.get() == SessionState.CONNECTED) {
                    apiClient.keepAlive(new LiveAvatarApiClient.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "Keep-alive success");
                        }

                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Keep-alive failed: " + error);
                        }
                    });
                    keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS);
                }
            }
        }, KEEP_ALIVE_INTERVAL_MS);
    }

    private void stopKeepAlive() {
        keepAliveHandler.removeCallbacksAndMessages(null);
    }

    private void setState(SessionState newState) {
        SessionState oldState = state.getAndSet(newState);
        if (oldState != newState && listener != null) {
            listener.onStateChanged(newState);
        }
    }

    private void notifyError(String error) {
        Log.e(TAG, error);
        if (listener != null) {
            listener.onError(error);
        }
    }
}
```

#### 2.3 Audio Delta Buffer for Lowest Latency

**File: `app/src/main/java/com/tripandevent/sanbotvoice/liveavatar/AudioDeltaBuffer.java`**

```java
package com.tripandevent.sanbotvoice.liveavatar;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Buffer for accumulating PCM audio deltas from OpenAI and streaming to LiveAvatar.
 *
 * For LOWEST LATENCY: Streams 20ms audio chunks directly without batching.
 * Audio format: PCM 24kHz, 16-bit signed mono
 */
public class AudioDeltaBuffer {
    private static final String TAG = "AudioDeltaBuffer";

    // Audio format constants (matching OpenAI Realtime output)
    private static final int SAMPLE_RATE = 24000;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit
    private static final int CHUNK_DURATION_MS = 20;
    private static final int BYTES_PER_CHUNK = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000) * BYTES_PER_SAMPLE; // 960 bytes

    public interface AudioFlushListener {
        void onAudioChunk(String base64Audio);
        void onAudioComplete();
    }

    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);

    private AudioFlushListener listener;

    public void setListener(AudioFlushListener listener) {
        this.listener = listener;
    }

    /**
     * Add audio delta from OpenAI response.
     * Immediately streams to LiveAvatar for lowest latency.
     *
     * @param base64Audio Base64 encoded PCM audio chunk
     */
    public void addAudioDelta(String base64Audio) {
        if (base64Audio == null || base64Audio.isEmpty()) return;

        isStreaming.set(true);

        try {
            byte[] audioBytes = Base64.decode(base64Audio, Base64.DEFAULT);
            audioBuffer.write(audioBytes);

            // Stream immediately when we have enough for a chunk
            while (audioBuffer.size() >= BYTES_PER_CHUNK) {
                byte[] allBytes = audioBuffer.toByteArray();
                byte[] chunk = new byte[BYTES_PER_CHUNK];
                System.arraycopy(allBytes, 0, chunk, 0, BYTES_PER_CHUNK);

                // Keep remaining bytes
                audioBuffer.reset();
                if (allBytes.length > BYTES_PER_CHUNK) {
                    audioBuffer.write(allBytes, BYTES_PER_CHUNK, allBytes.length - BYTES_PER_CHUNK);
                }

                // Stream chunk immediately
                if (listener != null) {
                    String chunkBase64 = Base64.encodeToString(chunk, Base64.NO_WRAP);
                    listener.onAudioChunk(chunkBase64);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing audio delta", e);
        }
    }

    /**
     * Called when OpenAI response is complete.
     * Flushes any remaining audio.
     */
    public void complete() {
        if (!isStreaming.get()) return;

        // Flush remaining audio
        if (audioBuffer.size() > 0 && listener != null) {
            byte[] remaining = audioBuffer.toByteArray();
            String remainingBase64 = Base64.encodeToString(remaining, Base64.NO_WRAP);
            listener.onAudioChunk(remainingBase64);
        }

        audioBuffer.reset();
        isStreaming.set(false);

        if (listener != null) {
            listener.onAudioComplete();
        }
    }

    /**
     * Clear buffer and cancel streaming.
     */
    public void clear() {
        audioBuffer.reset();
        isStreaming.set(false);
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }
}
```

---

### Phase 3: VoiceAgentService Integration

#### 3.1 Add LiveAvatar Support to VoiceAgentService

**Modifications to `VoiceAgentService.java`:**

```java
// Add imports
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarSessionManager;
import com.tripandevent.sanbotvoice.liveavatar.AudioDeltaBuffer;

// Add fields
private LiveAvatarSessionManager liveAvatarSessionManager;
private AudioDeltaBuffer audioDeltaBuffer;
private boolean useLiveAvatar = false; // Feature flag
private boolean useAudioStreaming = true; // Lowest latency mode

// In onCreate or startConversation
private void initializeLiveAvatar() {
    if (!useLiveAvatar) return;

    liveAvatarSessionManager = new LiveAvatarSessionManager(this);
    audioDeltaBuffer = new AudioDeltaBuffer();

    liveAvatarSessionManager.setListener(new LiveAvatarSessionManager.SessionListener() {
        @Override
        public void onStateChanged(LiveAvatarSessionManager.SessionState state) {
            Log.d(TAG, "LiveAvatar state: " + state);
        }

        @Override
        public void onStreamReady() {
            if (listener != null) {
                listener.onAvatarVideoReady();
            }
        }

        @Override
        public void onAvatarSpeakStarted() {
            // Avatar started speaking
        }

        @Override
        public void onAvatarSpeakEnded() {
            // Avatar finished speaking
        }

        @Override
        public void onError(String error) {
            Log.e(TAG, "LiveAvatar error: " + error);
            if (listener != null) {
                listener.onAvatarError(error);
            }
        }

        // ... other callbacks
    });

    // Set up audio streaming (lowest latency mode)
    if (useAudioStreaming) {
        audioDeltaBuffer.setListener(new AudioDeltaBuffer.AudioFlushListener() {
            @Override
            public void onAudioChunk(String base64Audio) {
                if (liveAvatarSessionManager != null) {
                    liveAvatarSessionManager.repeatAudio(base64Audio);
                }
            }

            @Override
            public void onAudioComplete() {
                Log.d(TAG, "Audio streaming complete");
            }
        });
    }
}

// Modify onServerEvent to route audio deltas
@Override
public void onServerEvent(ServerEvents.ParsedEvent event) {
    // ... existing handling ...

    if (useLiveAvatar) {
        switch (event.type) {
            case "response.audio.delta":
                // LOWEST LATENCY: Stream audio directly
                if (useAudioStreaming && event.delta != null) {
                    audioDeltaBuffer.addAudioDelta(event.delta);
                }
                break;

            case "response.audio.done":
                if (useAudioStreaming) {
                    audioDeltaBuffer.complete();
                }
                break;

            case "response.text.delta":
                // TEXT MODE: Forward transcript to avatar
                if (!useAudioStreaming && event.delta != null) {
                    // Buffer and flush text (similar to HeyGen)
                    textDeltaBuffer.addDelta(event.delta);
                }
                break;

            case "response.done":
                if (!useAudioStreaming) {
                    textDeltaBuffer.flush();
                }
                break;
        }
    }
}

// Add method to start LiveAvatar with OpenAI session
public void startWithLiveAvatar(String liveAvatarSessionToken) {
    this.useLiveAvatar = true;

    // Start LiveAvatar connection
    initializeLiveAvatar();
    liveAvatarSessionManager.start(liveAvatarSessionToken);

    // Start OpenAI connection (existing flow)
    startConversation();
}
```

---

### Phase 4: Configuration & Build

#### 4.1 Update BuildConfig

**File: `app/build.gradle` additions:**

```gradle
android {
    defaultConfig {
        buildConfigField "boolean", "ENABLE_LIVEAVATAR", "true"
        buildConfigField "boolean", "LIVEAVATAR_AUDIO_STREAMING", "true" // Lowest latency
        buildConfigField "String", "LIVEAVATAR_API_URL", "\"https://api.liveavatar.com\""
    }

    productFlavors {
        heygen {
            buildConfigField "boolean", "ENABLE_LIVEAVATAR", "false"
        }
        liveavatar {
            buildConfigField "boolean", "ENABLE_LIVEAVATAR", "true"
        }
    }
}

dependencies {
    // LiveKit (already present for HeyGen)
    implementation 'io.livekit:livekit-android:2.5.0'

    // OkHttp WebSocket support
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

#### 4.2 ProGuard Rules

**File: `app/proguard-rules.pro` additions:**

```proguard
# LiveAvatar
-keep class com.tripandevent.sanbotvoice.liveavatar.** { *; }
-keep class io.livekit.** { *; }
```

---

### Phase 5: UI Integration

#### 5.1 Update AvatarViewController

**Modifications to support LiveAvatar video rendering:**

```java
// In AvatarViewController.java
public void initializeLiveAvatar(LiveAvatarSessionManager sessionManager) {
    sessionManager.setVideoView(avatarSurfaceView);
}
```

---

## Latency Optimization Summary

### Option A: Text-Based Streaming (Easier Integration)
```
OpenAI Text Delta → TextDeltaBuffer (batch) → repeat(text) → LiveAvatar TTS → Video
Latency: ~500-800ms
```

### Option B: Audio-Based Streaming (Lowest Latency) ⭐ RECOMMENDED
```
OpenAI Audio Delta → AudioDeltaBuffer (stream) → repeatAudio(pcm) → Video
Latency: ~200-400ms
```

### Key Optimizations Applied:
1. **Direct audio streaming** - Bypasses LiveAvatar TTS entirely
2. **20ms chunk streaming** - Matches WebRTC frame size
3. **WebSocket preference** - Lower latency than LiveKit data channel
4. **No batching for audio** - Immediate forwarding
5. **Keep-alive timer** - Prevents session timeout
6. **Connection quality monitoring** - Adaptive behavior

---

## Migration Checklist

### Backend
- [ ] Add LiveAvatar API routes
- [ ] Add environment variables
- [ ] Test session start/stop endpoints
- [ ] Implement keep-alive endpoint

### Android App
- [ ] Create `LiveAvatarApiClient.java`
- [ ] Create `LiveAvatarSessionManager.java`
- [ ] Create `AudioDeltaBuffer.java`
- [ ] Update `VoiceAgentService.java`
- [ ] Update `build.gradle` with feature flags
- [ ] Update `AvatarViewController.java`
- [ ] Test audio streaming path
- [ ] Test text streaming fallback
- [ ] Measure end-to-end latency

### Testing
- [ ] Unit tests for AudioDeltaBuffer
- [ ] Integration test: OpenAI → LiveAvatar
- [ ] Latency measurement under various network conditions
- [ ] Stress test with long conversations
- [ ] Error recovery testing

---

## File Structure After Implementation

```
app/src/main/java/com/tripandevent/sanbotvoice/
├── liveavatar/                    ← NEW PACKAGE
│   ├── LiveAvatarApiClient.java
│   ├── LiveAvatarSessionManager.java
│   ├── AudioDeltaBuffer.java
│   ├── LiveAvatarConfig.java
│   └── LiveAvatarEvents.java
├── heygen/                        ← EXISTING (can coexist)
│   └── ...
├── core/
│   └── VoiceAgentService.java     ← MODIFIED
└── ui/
    └── AvatarViewController.java  ← MODIFIED
```

---

## API Reference Quick Guide

### LiveAvatar Commands (Android → Server)
| Method | When to Use | Latency |
|--------|-------------|---------|
| `message(text)` | FULL mode - AI responds | Higher |
| `repeat(text)` | CUSTOM mode - Avatar speaks text | Medium |
| `repeatAudio(pcm)` | CUSTOM mode - Avatar speaks audio | **Lowest** |
| `interrupt()` | Stop avatar mid-speech | Immediate |
| `startListening()` | Enable voice input | Immediate |
| `stopListening()` | Disable voice input | Immediate |

### Audio Format Requirements
- **Format:** PCM 24kHz, 16-bit signed, mono
- **Chunk Size:** 20ms (960 bytes)
- **Encoding:** Base64 for transmission

---

## Conclusion

This implementation plan provides two integration paths:

1. **Quick Integration (Text-based)**: Uses existing TextDeltaBuffer pattern, routes to `repeat()` instead of HeyGen
2. **Optimal Integration (Audio-based)**: Streams OpenAI audio deltas directly to LiveAvatar via `repeatAudio()` for lowest latency

The audio-based approach is recommended for production as it:
- Eliminates TTS latency on LiveAvatar side
- Maintains audio fidelity from OpenAI
- Reduces API calls (streaming vs text batching)
- Achieves ~200-400ms end-to-end latency
