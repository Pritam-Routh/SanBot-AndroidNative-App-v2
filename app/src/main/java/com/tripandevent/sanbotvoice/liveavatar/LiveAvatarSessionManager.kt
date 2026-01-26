package com.tripandevent.sanbotvoice.liveavatar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder

import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.renderer.SurfaceViewRenderer
import livekit.org.webrtc.RendererCommon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

import org.json.JSONException
import org.json.JSONObject

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

import com.tripandevent.sanbotvoice.heygen.TextDeltaBuffer

/**
 * LiveAvatarSessionManager
 *
 * Main orchestrator for LiveAvatar video streaming sessions.
 * Manages LiveKit WebRTC connection and WebSocket event communication.
 *
 * Architecture:
 * - LiveKit Room: Handles video/audio streaming via WebRTC
 * - WebSocket: Real-time event communication (avatar commands/status)
 * - REST API: Session lifecycle (start/stop/keep-alive)
 *
 * Supported operations:
 * - repeat(text): Make avatar speak text directly (uses LiveAvatar TTS)
 * - interrupt(): Stop avatar mid-speech (for barge-in)
 *
 * Uses text-based TTS mode (FULL mode) where text deltas are sent to
 * LiveAvatar which generates audio and lip-sync together.
 *
 * Kotlin implementation for LiveKit Android SDK 2.x compatibility.
 */
class LiveAvatarSessionManager(context: Context) : TextDeltaBuffer.FlushCallback {

    companion object {
        private const val TAG = "LiveAvatarSessionMgr"
    }

    // ============================================
    // STATE DEFINITIONS
    // ============================================

    enum class SessionState {
        IDLE,           // No session
        CONNECTING,     // Connecting to LiveKit/WebSocket
        CONNECTED,      // Session active, ready for commands
        DISCONNECTING,  // Graceful shutdown in progress
        ERROR           // Error state
    }

    enum class ConnectionQuality {
        UNKNOWN,
        GOOD,
        BAD
    }

    // ============================================
    // LISTENER INTERFACE
    // ============================================

    interface SessionListener {
        fun onStateChanged(state: SessionState)
        fun onStreamReady()
        fun onConnectionQualityChanged(quality: ConnectionQuality)
        fun onUserTranscription(text: String)
        fun onAvatarTranscription(text: String)
        fun onAvatarSpeakStarted()
        fun onAvatarSpeakEnded()
        fun onError(error: String)

        // Standalone mode callbacks (HeyGen VAD events)
        fun onUserSpeakStarted() {}   // User started speaking (HeyGen VAD detected speech)
        fun onUserSpeakEnded() {}     // User stopped speaking (HeyGen VAD detected silence)
    }

    // ============================================
    // FIELDS
    // ============================================

    private val appContext: Context = context.applicationContext
    private val apiClient: LiveAvatarApiClient = LiveAvatarApiClient.getInstance()
    private val textDeltaBuffer: TextDeltaBuffer = TextDeltaBuffer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webSocketClient = OkHttpClient.Builder().build()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State
    private val state = AtomicReference(SessionState.IDLE)
    private val isInterrupting = AtomicBoolean(false)

    // Session info
    private var sessionInfo: LiveAvatarApiClient.SessionInfo? = null

    // LiveKit
    private var room: Room? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: RemoteAudioTrack? = null
    private var streamReady = false

    // WebSocket
    private var webSocket: WebSocket? = null
    private var webSocketConnected = false

    // UI
    private var videoRenderer: SurfaceViewRenderer? = null
    private var listener: SessionListener? = null

    // Surface state - for proper video attachment timing
    private var surfaceReady = false
    private var surfaceCallback: SurfaceHolder.Callback? = null

    // Keep-alive
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null

    init {
        textDeltaBuffer.setCallback(this)
    }

    // ============================================
    // PUBLIC METHODS - CONFIGURATION
    // ============================================

    fun setListener(newListener: SessionListener?) {
        this.listener = newListener
    }

    fun setVideoRenderer(renderer: SurfaceViewRenderer) {
        Log.d(TAG, "setVideoRenderer called, room=${room != null}, hasTrack=${remoteVideoTrack != null}")

        // Remove old surface callback
        val oldCallback = surfaceCallback
        val oldRenderer = videoRenderer
        if (oldCallback != null && oldRenderer != null) {
            try {
                oldRenderer.holder.removeCallback(oldCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing old surface callback: ${e.message}")
            }
        }

        // Detach from old renderer
        val currentTrack = remoteVideoTrack
        if (oldRenderer != null && currentTrack != null) {
            try {
                currentTrack.removeRenderer(oldRenderer)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing old renderer: ${e.message}")
            }
        }

        videoRenderer = renderer
        surfaceReady = false

        // Initialize renderer using room's EGL context for proper video rendering
        try {
            val currentRoom = room
            if (currentRoom != null) {
                // Use room's EGL context - required for proper video rendering
                currentRoom.initVideoRenderer(renderer)
                Log.d(TAG, "Renderer initialized with room's EGL context")
            } else {
                // Fallback to manual init if room not ready
                renderer.init(null, null)
                Log.d(TAG, "Renderer initialized without room context (fallback)")
            }
            renderer.setMirror(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            // Bring surface to front to ensure visibility
            renderer.setZOrderMediaOverlay(true)
            Log.d(TAG, "Renderer configuration complete")
        } catch (e: Exception) {
            Log.w(TAG, "Renderer init issue (may be already initialized): ${e.message}")
        }

        // Add surface callback to track when surface is ready
        val newCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "*** SURFACE CREATED ***")
                surfaceReady = true
                // Now try to attach if we have a track waiting
                if (remoteVideoTrack != null) {
                    mainHandler.post { attachToRenderer() }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
                surfaceReady = false
            }
        }
        surfaceCallback = newCallback
        renderer.holder.addCallback(newCallback)

        // Check if surface is already valid (it might already be created)
        if (renderer.holder.surface != null && renderer.holder.surface.isValid) {
            Log.d(TAG, "Surface already valid at setVideoRenderer time")
            surfaceReady = true
        }

        // Attach to new renderer if track exists and surface is ready
        if (remoteVideoTrack != null && surfaceReady) {
            attachToRenderer()
        }
    }

    fun getState(): SessionState = state.get()

    fun isConnected(): Boolean = state.get() == SessionState.CONNECTED

    fun isReady(): Boolean = state.get() == SessionState.CONNECTED && streamReady

    /**
     * Check if session is active (connecting or connected)
     * Used to prevent premature UI hiding during session transitions
     */
    fun isActive(): Boolean {
        val currentState = state.get()
        return currentState == SessionState.CONNECTING || currentState == SessionState.CONNECTED
    }

    // ============================================
    // PUBLIC METHODS - SESSION LIFECYCLE
    // ============================================

    /**
     * Start a new LiveAvatar session
     *
     * @param avatarId Optional avatar ID (null for default)
     */
    fun startSession(avatarId: String?) {
        val currentState = state.get()
        if (currentState != SessionState.IDLE && currentState != SessionState.ERROR) {
            Log.w(TAG, "Cannot start session in state: $currentState")
            return
        }

        if (!LiveAvatarConfig.isEnabled()) {
            Log.d(TAG, "LiveAvatar is disabled")
            return
        }

        Log.d(TAG, "Starting LiveAvatar session...")
        setState(SessionState.CONNECTING)

        // Step 1: Get session token from backend
        apiClient.getSessionToken(avatarId, object : LiveAvatarApiClient.TokenCallback {
            override fun onSuccess(sessionToken: String) {
                Log.d(TAG, "Got session token")
                mainHandler.post { startSessionWithToken() }
            }

            override fun onError(error: String) {
                mainHandler.post { handleError("Failed to get session token: $error") }
            }
        })
    }

    private fun startSessionWithToken() {
        apiClient.startSession(object : LiveAvatarApiClient.SessionCallback {
            override fun onSuccess(info: LiveAvatarApiClient.SessionInfo) {
                sessionInfo = info
                // Store session ID in API client for speak commands
                if (info.sessionId != null) {
                    apiClient.setSessionId(info.sessionId)
                    Log.i(TAG, "Session ID stored: ${info.sessionId}")
                }
                Log.d(TAG, "Session started: $info")
                mainHandler.post { connectToSession(info) }
            }

            override fun onError(error: String) {
                mainHandler.post { handleError("Session start failed: $error") }
            }
        })
    }

    private fun connectToSession(info: LiveAvatarApiClient.SessionInfo) {
        Log.i(TAG, "=== Session Info Details ===")
        Log.i(TAG, "LiveKit URL: ${info.liveKitUrl ?: "NULL"}")
        Log.i(TAG, "LiveKit Token: ${if (info.liveKitToken != null) "SET (${info.liveKitToken.length} chars)" else "NULL"}")
        Log.i(TAG, "WebSocket URL: ${info.wsUrl ?: "NULL"}")

        // Connect to LiveKit if credentials provided
        if (info.liveKitUrl != null && info.liveKitToken != null) {
            Log.d(TAG, "Connecting to LiveKit...")
            connectToLiveKit(info.liveKitUrl, info.liveKitToken)
        } else {
            Log.w(TAG, "No LiveKit credentials - video will not work")
        }

        // Connect to WebSocket if URL provided
        // Note: WebSocket is optional in FULL mode - we can use LiveKit data channel for text commands
        // WebSocket is primarily needed for CUSTOM mode (audio streaming)
        if (info.wsUrl != null) {
            Log.d(TAG, "Connecting to WebSocket for text/audio commands...")
            connectWebSocket(info.wsUrl)
        } else {
            Log.w(TAG, "No WebSocket URL provided - will use LiveKit data channel for text commands")
            // In FULL mode, this is OK - we can send text via LiveKit data channel
            // Don't treat this as an error, just log a warning
        }

        // If no LiveKit or WebSocket, we're still connected via REST
        if (info.liveKitUrl == null && info.wsUrl == null) {
            Log.w(TAG, "No LiveKit or WebSocket URLs - REST only mode")
            setState(SessionState.CONNECTED)
            startKeepAlive()
        }
    }

    /**
     * Stop the current session
     */
    fun stopSession() {
        val currentState = state.get()
        if (currentState == SessionState.IDLE || currentState == SessionState.DISCONNECTING) {
            return
        }

        Log.d(TAG, "Stopping LiveAvatar session...")
        setState(SessionState.DISCONNECTING)

        // Stop keep-alive
        stopKeepAlive()

        // Disconnect WebSocket
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        webSocketConnected = false

        // Disconnect LiveKit
        room?.disconnect()
        room = null

        // Detach video
        detachVideoRenderer()

        // Stop session via API
        apiClient.stopSession(object : LiveAvatarApiClient.SimpleCallback {
            override fun onSuccess() {
                mainHandler.post {
                    sessionInfo = null
                    apiClient.clearSession()
                    setState(SessionState.IDLE)
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Stop session error: $error")
                mainHandler.post {
                    sessionInfo = null
                    apiClient.clearSession()
                    setState(SessionState.IDLE)
                }
            }
        })
    }

    // ============================================
    // PUBLIC METHODS - AVATAR COMMANDS
    // ============================================

    /**
     * Make avatar speak text directly
     * Uses LiveAvatar's TTS for audio generation and lip sync
     *
     * @param text Text for avatar to speak
     */
    fun repeat(text: String) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot repeat: not connected")
            return
        }
        sendTextCommand(text)
    }

    /**
     * Interrupt avatar mid-speech (for barge-in)
     */
    fun interrupt() {
        if (!isConnected()) {
            return
        }

        // Prevent multiple simultaneous interrupts
        if (!isInterrupting.compareAndSet(false, true)) {
            return
        }

        Log.d(TAG, "Interrupting avatar")

        // Clear any pending text buffer (use interrupt() to reset state)
        textDeltaBuffer.interrupt()  // Use interrupt() to reset first flush state

        sendInterrupt()

        // Reset interrupt flag after brief delay
        mainHandler.postDelayed({ isInterrupting.set(false) }, 500)
    }

    /**
     * Start avatar listening mode
     * Based on SDK: Prefers WebSocket with "type" field, falls back to data channel with "event_type"
     */
    fun startListening() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot startListening: not connected")
            return
        }

        try {
            // FULL mode: Commands MUST go via LiveKit data channel with "event_type" field
            // HeyGen docs: "In Full Mode, we process events sent via the LiveKit Protocol"
            if (LiveAvatarConfig.isStandaloneMode() || !webSocketConnected) {
                val command = JSONObject().apply {
                    put("event_type", "avatar.start_listening")
                }
                sendViaDataChannel(command.toString())
                Log.i(TAG, "Start listening sent via LiveKit data channel (FULL mode)")
            } else {
                val command = JSONObject().apply {
                    put("type", "agent.start_listening")
                    put("event_id", UUID.randomUUID().toString())
                }
                sendToLiveAvatarWebSocket(command.toString())
                Log.d(TAG, "Start listening sent via WebSocket")
            }

        } catch (e: JSONException) {
            Log.e(TAG, "Error creating start_listening command", e)
        }
    }

    /**
     * Stop avatar listening mode
     * Based on SDK: Prefers WebSocket with "type" field, falls back to data channel with "event_type"
     */
    fun stopListening() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot stopListening: not connected")
            return
        }

        try {
            // FULL mode: Commands MUST go via LiveKit data channel with "event_type" field
            if (LiveAvatarConfig.isStandaloneMode() || !webSocketConnected) {
                val command = JSONObject().apply {
                    put("event_type", "avatar.stop_listening")
                }
                sendViaDataChannel(command.toString())
                Log.i(TAG, "Stop listening sent via LiveKit data channel (FULL mode)")
            } else {
                val command = JSONObject().apply {
                    put("type", "agent.stop_listening")
                    put("event_id", UUID.randomUUID().toString())
                }
                sendToLiveAvatarWebSocket(command.toString())
                Log.d(TAG, "Stop listening sent via WebSocket")
            }

        } catch (e: JSONException) {
            Log.e(TAG, "Error creating stop_listening command", e)
        }
    }

    /**
     * Send user text to HeyGen's built-in LLM for response generation.
     * In FULL mode, this triggers the avatar to process the text through its LLM,
     * generate a response, and speak it with lip-sync.
     */
    fun speakResponse(text: String) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot speakResponse: not connected")
            return
        }

        try {
            // FULL mode: Commands MUST go via LiveKit data channel with "event_type" field
            if (LiveAvatarConfig.isStandaloneMode() || !webSocketConnected) {
                val command = JSONObject().apply {
                    put("event_type", "avatar.speak_response")
                    put("text", text)
                }
                sendViaDataChannel(command.toString())
                Log.i(TAG, "speak_response sent via LiveKit data channel (FULL mode): $text")
            } else {
                val command = JSONObject().apply {
                    put("type", "agent.speak_response")
                    put("text", text)
                    put("event_id", UUID.randomUUID().toString())
                }
                sendToLiveAvatarWebSocket(command.toString())
                Log.i(TAG, "speak_response sent via WebSocket: $text")
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating speak_response command", e)
        }
    }

    /**
     * Send text for the avatar to speak directly (TTS only, no LLM).
     * In FULL mode, this makes the avatar say the exact text provided.
     */
    fun speakText(text: String) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot speakText: not connected")
            return
        }

        try {
            val command = JSONObject().apply {
                put("event_type", "avatar.speak_text")
                put("text", text)
            }
            sendViaDataChannel(command.toString())
            Log.i(TAG, "speak_text sent via LiveKit data channel: $text")
        } catch (e: JSONException) {
            Log.e(TAG, "Error creating speak_text command", e)
        }
    }

    // ============================================
    // TextDeltaBuffer.FlushCallback
    // ============================================

    /**
     * Called when text buffer is ready to flush.
     * Sends accumulated text to LiveAvatar for TTS and lip sync.
     */
    override fun onFlush(text: String) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot send text: not connected")
            return
        }
        Log.d(TAG, "Text buffer flushed: ${text.length} chars")
        sendTextCommand(text)
    }

    // ============================================
    // PUBLIC METHODS - TEXT DELTA STREAMING
    // ============================================

    /**
     * Add a text delta for the avatar to speak.
     * The buffer will accumulate and batch deltas for smooth speech.
     *
     * Use this when OpenAI is in WebRTC mode (audio deltas not available).
     * LiveAvatar will use TTS to generate audio and lip sync together.
     *
     * @param delta Text delta from OpenAI transcript
     */
    fun addTextDelta(delta: String) {
        if (!isReady()) {
            return
        }
        textDeltaBuffer.addDelta(delta)
    }

    /**
     * Force flush any buffered text.
     * Call this when OpenAI response is complete.
     */
    fun flushTextBuffer() {
        textDeltaBuffer.forceFlush()
    }

    /**
     * Clear text buffer without flushing (for interruption/barge-in).
     */
    fun clearTextBuffer() {
        textDeltaBuffer.clear()
    }

    /**
     * Enable text buffer (call when session becomes active).
     */
    fun enableTextBuffer() {
        textDeltaBuffer.setEnabled(true)
    }

    /**
     * Disable text buffer (call when session ends).
     */
    fun disableTextBuffer() {
        textDeltaBuffer.setEnabled(false)
        textDeltaBuffer.clear()
    }

    // ============================================
    // PRIVATE - LIVEKIT CONNECTION
    // ============================================

    private fun connectToLiveKit(url: String, token: String) {
        Log.d(TAG, "Connecting to LiveKit: $url")

        coroutineScope.launch {
            try {
                // Create room instance
                val newRoom = LiveKit.create(
                    appContext,
                    RoomOptions(),
                    LiveKitOverrides()
                )
                room = newRoom

                // Set up event collection
                setupRoomEvents(newRoom)

                // Connect to the room
                newRoom.connect(url, token, ConnectOptions())

                Log.d(TAG, "Connected to LiveKit room")

                // In standalone mode, publish local microphone to LiveKit room
                // so HeyGen can hear the user for STT processing
                if (LiveAvatarConfig.isStandaloneMode()) {
                    Log.i(TAG, "Standalone mode: Publishing microphone to LiveKit room")
                    newRoom.localParticipant.setMicrophoneEnabled(true)
                    Log.i(TAG, "Microphone enabled and published in LiveKit room")
                }

                if (state.get() == SessionState.CONNECTING) {
                    setState(SessionState.CONNECTED)
                    startKeepAlive()
                }

                // Check for any existing tracks
                checkExistingTracks()

            } catch (e: Exception) {
                Log.e(TAG, "LiveKit connection error", e)
                mainHandler.post {
                    handleError("LiveKit connection failed: ${e.message}")
                }
            }
        }
    }

    private fun setupRoomEvents(currentRoom: Room) {
        coroutineScope.launch {
            currentRoom.events.events.collect { roomEvent: RoomEvent ->
                handleRoomEvent(roomEvent)
            }
        }
    }

    private fun handleRoomEvent(roomEvent: RoomEvent) {
        when (roomEvent) {
            is RoomEvent.DataReceived -> {
                // Handle data from avatar via LiveKit data channel
                val data = roomEvent.data
                val topic = roomEvent.topic
                val sender = roomEvent.participant?.identity?.value ?: "unknown"

                try {
                    val message = String(data, Charsets.UTF_8)
                    Log.i(TAG, "<<< DataChannel [${topic ?: "null"}] from=$sender: ${message.take(200)}")

                    // Process messages from any topic in FULL mode (events may come on various topics)
                    mainHandler.post { handleWebSocketMessage(message) }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing data channel message from $sender", e)
                }
            }

            is RoomEvent.TrackSubscribed -> {
                val subscribedTrack: Track = roomEvent.track
                val subscribedParticipant: RemoteParticipant = roomEvent.participant
                val participantId = subscribedParticipant.identity?.value ?: "unknown"
                Log.i(TAG, "Track subscribed: ${subscribedTrack.javaClass.simpleName} " +
                        "from participant: $participantId")

                if (LiveAvatarConfig.isStandaloneMode()) {
                    // FULL mode: Accept tracks from both "heygen" (video avatar) and "agent-*" (conversation agent)
                    when (subscribedTrack) {
                        is RemoteVideoTrack -> {
                            if (participantId == LiveAvatarConfig.AVATAR_PARTICIPANT_ID) {
                                mainHandler.post { handleVideoTrack(subscribedTrack) }
                            } else {
                                Log.d(TAG, "Ignoring video track from non-avatar participant: $participantId")
                            }
                        }
                        is RemoteAudioTrack -> {
                            // Accept audio from any participant in FULL mode
                            // "heygen" provides lip-sync audio, "agent-*" may provide response audio
                            Log.i(TAG, "FULL mode: Accepting audio track from participant: $participantId")
                            mainHandler.post { handleAudioTrack(subscribedTrack) }
                        }
                    }
                } else {
                    // Legacy mode: Only handle tracks from the "heygen" participant
                    if (participantId != LiveAvatarConfig.AVATAR_PARTICIPANT_ID) {
                        Log.d(TAG, "Ignoring track from non-avatar participant: $participantId")
                        return
                    }
                    when (subscribedTrack) {
                        is RemoteVideoTrack -> {
                            mainHandler.post { handleVideoTrack(subscribedTrack) }
                        }
                        is RemoteAudioTrack -> {
                            mainHandler.post { handleAudioTrack(subscribedTrack) }
                        }
                    }
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                val unsubscribedTrack: Track = roomEvent.track
                Log.d(TAG, "Track unsubscribed: ${unsubscribedTrack.javaClass.simpleName}")

                if (unsubscribedTrack == remoteVideoTrack) {
                    mainHandler.post { detachVideoRenderer() }
                }
            }

            is RoomEvent.Disconnected -> {
                val disconnectError: Exception? = roomEvent.error
                Log.w(TAG, "LiveKit disconnected: ${disconnectError?.message}")

                mainHandler.post {
                    if (state.get() == SessionState.CONNECTED) {
                        handleError("LiveKit disconnected unexpectedly")
                    }
                }
            }

            is RoomEvent.FailedToConnect -> {
                val connectError: Throwable = roomEvent.error
                Log.e(TAG, "Failed to connect: ${connectError.message}")

                mainHandler.post {
                    handleError("Connection failed: ${connectError.message}")
                }
            }

            is RoomEvent.ParticipantConnected -> {
                val p = roomEvent.participant
                Log.i(TAG, "*** Participant CONNECTED: ${p.identity?.value} ***")
            }

            is RoomEvent.ParticipantDisconnected -> {
                val p = roomEvent.participant
                Log.i(TAG, "*** Participant DISCONNECTED: ${p.identity?.value} ***")
            }

            is RoomEvent.ActiveSpeakersChanged -> {
                val speakers = roomEvent.speakers.map { it.identity?.value ?: "?" }
                if (speakers.isNotEmpty()) {
                    Log.i(TAG, "Active speakers: $speakers")
                }
            }

            else -> {
                // Log unhandled events for debugging
                Log.d(TAG, "Room event: ${roomEvent.javaClass.simpleName}")
            }
        }
    }

    private fun checkExistingTracks() {
        val currentRoom = room ?: return

        try {
            for ((_, participant) in currentRoom.remoteParticipants) {
                // Check video tracks
                val videoPublications = participant.videoTrackPublications
                for (pair in videoPublications) {
                    val publication: TrackPublication = pair.first
                    val existingTrack: Track? = pair.second
                    if (existingTrack is RemoteVideoTrack) {
                        Log.d(TAG, "Found existing video track: ${publication.sid}")
                        handleVideoTrack(existingTrack)
                    }
                }

                // Check audio tracks
                val audioPublications = participant.audioTrackPublications
                for (pair in audioPublications) {
                    val existingTrack: Track? = pair.second
                    if (existingTrack is RemoteAudioTrack) {
                        Log.d(TAG, "Found existing audio track")
                        handleAudioTrack(existingTrack)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking existing tracks: ${e.message}")
        }
    }

    private fun handleVideoTrack(track: RemoteVideoTrack) {
        if (track == remoteVideoTrack) {
            return
        }

        remoteVideoTrack = track
        Log.d(TAG, "Video track received")

        if (videoRenderer != null) {
            attachToRenderer()
        }

        checkStreamReady()
    }

    private fun handleAudioTrack(track: RemoteAudioTrack) {
        if (track == remoteAudioTrack) {
            return
        }

        remoteAudioTrack = track

        if (LiveAvatarConfig.isStandaloneMode()) {
            // STANDALONE MODE: HeyGen provides the response audio - leave UNMUTED
            // The user hears HeyGen's TTS response through this audio track
            Log.i(TAG, "Audio track received from LiveAvatar - UNMUTED (standalone mode, HeyGen provides audio)")
        } else {
            // LEGACY MODE: OpenAI provides audio, LiveAvatar only for lip-sync
            // Mute LiveAvatar audio to prevent double audio
            track.setVolume(0.0)
            Log.i(TAG, "Audio track received from LiveAvatar - MUTED (OpenAI audio plays to speaker)")
        }

        checkStreamReady()
    }

    private fun checkStreamReady() {
        if (!streamReady && remoteVideoTrack != null && remoteAudioTrack != null) {
            streamReady = true
            Log.i(TAG, "Stream ready - video and audio tracks available")
            listener?.onStreamReady()

            // In standalone mode, activate HeyGen FULL mode listening
            if (LiveAvatarConfig.isStandaloneMode()) {
                Log.i(TAG, "Standalone mode: Sending start_listening to activate HeyGen VAD")
                // Small delay to ensure LiveKit data channel is fully ready
                mainHandler.postDelayed({
                    startListening()
                }, 500)
            }
        }
    }

    private fun attachToRenderer() {
        val track = remoteVideoTrack
        val renderer = videoRenderer

        if (track == null || renderer == null) {
            Log.d(TAG, "attachToRenderer: track=${track != null}, renderer=${renderer != null}")
            return
        }

        if (!surfaceReady) {
            Log.d(TAG, "attachToRenderer: Surface not ready yet, waiting for surfaceCreated callback")
            return
        }

        Log.i(TAG, "*** ATTACHING VIDEO TRACK TO RENDERER (surface ready) ***")
        try {
            track.addRenderer(renderer)
            Log.i(TAG, "Video track attached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching renderer: ${e.message}", e)
        }
    }

    private fun detachVideoRenderer() {
        val track = remoteVideoTrack
        val renderer = videoRenderer

        // Remove surface callback
        val callback = surfaceCallback
        if (callback != null && renderer != null) {
            try {
                renderer.holder.removeCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing surface callback: ${e.message}")
            }
        }
        surfaceCallback = null
        surfaceReady = false

        // Remove renderer from track
        if (track != null && renderer != null) {
            try {
                track.removeRenderer(renderer)
            } catch (e: Exception) {
                Log.w(TAG, "Error detaching renderer: ${e.message}")
            }
        }

        remoteVideoTrack = null
        remoteAudioTrack = null
        streamReady = false
    }

    // ============================================
    // PRIVATE - WEBSOCKET CONNECTION
    // ============================================

    private fun connectWebSocket(wsUrl: String) {
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                webSocketConnected = true

                // If LiveKit not used, mark as connected now
                mainHandler.post {
                    if (state.get() == SessionState.CONNECTING && room == null) {
                        setState(SessionState.CONNECTED)
                        startKeepAlive()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                webSocketConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                webSocketConnected = false
            }
        })
    }

    private fun handleWebSocketMessage(message: String) {
        mainHandler.post {
            try {
                // Log ALL messages from LiveAvatar for debugging
                val preview = if (message.length > 200) message.substring(0, 200) + "..." else message
                Log.i(TAG, "<<< WebSocket RECEIVED: $preview")

                val json = JSONObject(message)
                val type = json.optString("type", json.optString("event_type", ""))

                when (type) {
                    "user.speak_started", "user_speak_started" -> {
                        Log.i(TAG, "*** User started speaking (HeyGen VAD) ***")
                        listener?.onUserSpeakStarted()
                    }
                    "user.speak_ended", "user_speak_ended" -> {
                        Log.i(TAG, "*** User stopped speaking (HeyGen VAD) ***")
                        listener?.onUserSpeakEnded()
                    }
                    "user.transcription", "user_transcription" -> {
                        val text = json.optString("text", json.optString("transcript", ""))
                        Log.i(TAG, "*** User transcription received: '$text' ***")
                        listener?.onUserTranscription(text)
                    }
                    "avatar.transcription", "avatar_transcription" -> {
                        val text = json.optString("text", json.optString("transcript", ""))
                        listener?.onAvatarTranscription(text)
                    }
                    "avatar.speak_started", "avatar_speak_started", "speak_started" -> {
                        Log.i(TAG, "*** Avatar started speaking! ***")
                        listener?.onAvatarSpeakStarted()
                    }
                    "avatar.speak_ended", "avatar_speak_ended", "speak_ended" -> {
                        Log.i(TAG, "*** Avatar finished speaking ***")
                        listener?.onAvatarSpeakEnded()
                    }
                    "task.started", "task_started" -> {
                        Log.i(TAG, "*** Task started ***")
                        listener?.onAvatarSpeakStarted()
                    }
                    "task.completed", "task_completed" -> {
                        Log.i(TAG, "*** Task completed ***")
                        listener?.onAvatarSpeakEnded()
                    }
                    "error" -> {
                        val errorMsg = json.optString("message", json.optString("error", "Unknown error"))
                        Log.e(TAG, "*** WebSocket ERROR: $errorMsg ***")
                    }
                    "" -> {
                        // No type field - log the entire message
                        Log.w(TAG, "WebSocket message without type: $message")
                    }
                    else -> {
                        // Unknown type - log it
                        Log.d(TAG, "WebSocket message type '$type' (unhandled)")
                    }
                }
            } catch (e: JSONException) {
                // Not JSON - might be binary or other format
                Log.w(TAG, "WebSocket message (non-JSON): ${message.take(100)}")
            }
        }
    }

    // ============================================
    // PRIVATE - COMMAND SENDING
    // ============================================

    /**
     * Send interrupt signal to LiveAvatar
     *
     * Stops avatar mid-speech for barge-in support.
     * Based on SDK: Prefers WebSocket with "type" field, falls back to data channel with "event_type"
     */
    private fun sendInterrupt() {
        Log.d(TAG, "Sending interrupt...")

        try {
            // FULL mode: Commands MUST go via LiveKit data channel with "event_type" field
            if (LiveAvatarConfig.isStandaloneMode() || !webSocketConnected) {
                val command = JSONObject().apply {
                    put("event_type", "avatar.interrupt")
                }
                sendViaDataChannel(command.toString())
                Log.i(TAG, "Interrupt sent via LiveKit data channel (FULL mode)")
            } else {
                val command = JSONObject().apply {
                    put("type", "agent.interrupt")
                    put("event_id", UUID.randomUUID().toString())
                }
                sendToLiveAvatarWebSocket(command.toString())
                Log.d(TAG, "Interrupt sent via WebSocket")
            }

        } catch (e: JSONException) {
            Log.e(TAG, "Error creating interrupt command", e)
        }
    }

    /**
     * Send command to LiveAvatar via its dedicated WebSocket
     * Used for interrupt commands when WebSocket is available
     */
    private fun sendToLiveAvatarWebSocket(json: String) {
        val ws = webSocket
        if (ws != null && webSocketConnected) {
            ws.send(json)
            // Log preview (first 100 chars) to avoid flooding logs with base64
            val preview = if (json.length > 100) json.substring(0, 100) + "..." else json
            Log.d(TAG, "WebSocket SENT: $preview")
        } else {
            Log.e(TAG, "*** CRITICAL: Cannot send - LiveAvatar WebSocket not connected! ***")
        }
    }

    /**
     * Send command to LiveAvatar (via WebSocket or LiveKit data channel)
     */
    private fun sendToWebSocket(json: String) {
        val ws = webSocket
        if (ws != null && webSocketConnected) {
            // Use WebSocket if available
            ws.send(json)
            // Log first 100 chars of message for debugging
            val preview = if (json.length > 100) json.substring(0, 100) + "..." else json
            Log.d(TAG, "WebSocket SENT: $preview")
        } else {
            // Fallback to LiveKit data channel if WebSocket not available
            val currentRoom = room
            if (currentRoom != null) {
                coroutineScope.launch {
                    try {
                        val data = json.toByteArray(Charsets.UTF_8)
                        currentRoom.localParticipant.publishData(
                            data,
                            DataPublishReliability.RELIABLE,
                            topic = "agent-control"  // Must match LiveAvatar SDK topic
                        )
                        val preview = if (json.length > 100) json.substring(0, 100) + "..." else json
                        Log.d(TAG, "LiveKit Data SENT: $preview")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send via LiveKit data channel", e)
                    }
                }
            } else {
                Log.w(TAG, "No WebSocket or LiveKit connection available")
            }
        }
    }

    /**
     * Send text command to LiveAvatar for TTS + lip sync
     *
     * Based on official LiveAvatar Web SDK (LiveAvatarSession.ts):
     * - Text commands go via LiveKit data channel with "event_type": "avatar.speak_text"
     * - Topic: "agent-control"
     *
     * This matches the SDK's repeat() method which uses sendCommandEvent() -> publishData()
     */
    private fun sendTextCommand(text: String) {
        if (text.isBlank()) {
            return
        }

        Log.i(TAG, "=== SENDING TEXT FOR LIP SYNC === text: ${text.take(50)}...")

        // Per SDK: Text commands ALWAYS go via LiveKit data channel (not WebSocket)
        sendSpeakViaDataChannel(text)
    }

    /**
     * Send speak command via LiveKit data channel
     *
     * Based on SDK analysis (LiveAvatarSession.ts):
     * - Text commands use LiveKit Data Channel with "event_type" field
     * - Format: {event_type: "avatar.speak_text", text: "..."}
     */
    private fun sendSpeakViaDataChannel(text: String) {
        try {
            // Format based on SDK's CommandEventsEnum.AVATAR_SPEAK_TEXT
            // CRITICAL: SDK uses "event_type" NOT "type" for data channel commands!
            val command = JSONObject().apply {
                put("event_type", "avatar.speak_text")  // Must be "event_type" not "type"!
                put("text", text)
            }

            // SDK sends text commands via LiveKit data channel, NOT WebSocket
            sendViaDataChannel(command.toString())
            Log.i(TAG, ">>> DataChannel SENT [avatar.speak_text]: ${text.take(40)}...")

        } catch (e: Exception) {
            Log.e(TAG, "DataChannel speak failed", e)
        }
    }

    /**
     * Send command via LiveKit data channel (primary transport for avatar commands)
     *
     * LiveAvatar expects commands on a specific topic for avatar control.
     * This includes text commands and interrupts.
     *
     * Topic is configurable via LiveAvatarConfig.DATA_CHANNEL_TOPIC
     */
    private fun sendViaDataChannel(json: String) {
        val currentRoom = room
        if (currentRoom == null) {
            Log.e(TAG, "*** CRITICAL: Cannot send via data channel - room is NULL! ***")
            return
        }

        // Verify room is connected and log participants
        val connectionState = currentRoom.state
        val participantCount = currentRoom.remoteParticipants.size
        val participantIds = currentRoom.remoteParticipants.keys.map { it.value }.joinToString(", ")
        Log.i(TAG, "DataChannel: Room state=$connectionState, participants=$participantCount [$participantIds]")

        // Check if avatar participant is in the room
        val hasAvatarParticipant = currentRoom.remoteParticipants.keys.any {
            it.value == LiveAvatarConfig.AVATAR_PARTICIPANT_ID
        }
        if (!hasAvatarParticipant && participantCount > 0) {
            Log.w(TAG, "Avatar participant '${LiveAvatarConfig.AVATAR_PARTICIPANT_ID}' not found in room. Available: [$participantIds]")
        }

        val topic = LiveAvatarConfig.DATA_CHANNEL_TOPIC

        coroutineScope.launch {
            try {
                val data = json.toByteArray(Charsets.UTF_8)

                currentRoom.localParticipant.publishData(
                    data,
                    DataPublishReliability.RELIABLE,
                    topic = topic
                )

                val preview = if (json.length > 100) json.substring(0, 100) + "..." else json
                Log.i(TAG, "✓ DataChannel SENT [$topic] (${data.size} bytes): $preview")
            } catch (e: Exception) {
                Log.e(TAG, "*** FAILED to send via data channel: ${e.message}", e)
            }
        }
    }

    // ============================================
    // PRIVATE - KEEP ALIVE
    // ============================================

    private fun startKeepAlive() {
        stopKeepAlive()

        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (state.get() == SessionState.CONNECTED) {
                    apiClient.keepAlive(object : LiveAvatarApiClient.SimpleCallback {
                        override fun onSuccess() {
                            Log.d(TAG, "Keep-alive success")
                        }

                        override fun onError(error: String) {
                            Log.w(TAG, "Keep-alive failed: $error")
                        }
                    })
                    keepAliveHandler.postDelayed(this, LiveAvatarConfig.KEEP_ALIVE_INTERVAL_MS)
                }
            }
        }

        keepAliveHandler.postDelayed(keepAliveRunnable!!, LiveAvatarConfig.KEEP_ALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
        keepAliveRunnable = null
    }

    // ============================================
    // PRIVATE - STATE MANAGEMENT
    // ============================================

    private fun setState(newState: SessionState) {
        val oldState = state.getAndSet(newState)
        if (oldState != newState) {
            Log.d(TAG, "State: $oldState -> $newState")
            listener?.onStateChanged(newState)
        }
    }

    private fun handleError(error: String) {
        Log.e(TAG, "Session error: $error")
        setState(SessionState.ERROR)

        // Cleanup
        stopKeepAlive()

        webSocket?.close(1000, "Error cleanup")
        webSocket = null
        webSocketConnected = false

        room?.disconnect()
        room = null

        listener?.onError(error)
    }

    // ============================================
    // CLEANUP
    // ============================================

    /**
     * Cleanup all resources
     * Call this when done with the session manager
     */
    fun destroy() {
        stopSession()
        textDeltaBuffer.destroy()
        coroutineScope.cancel()

        // Clean up surface callback
        val callback = surfaceCallback
        val renderer = videoRenderer
        if (callback != null && renderer != null) {
            try {
                renderer.holder.removeCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing surface callback in destroy: ${e.message}")
            }
        }
        surfaceCallback = null
        surfaceReady = false
        videoRenderer = null

        listener = null
    }
}
