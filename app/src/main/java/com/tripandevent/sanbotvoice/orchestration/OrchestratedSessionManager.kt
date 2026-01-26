package com.tripandevent.sanbotvoice.orchestration

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.RoomOptions
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.ConnectOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import io.livekit.android.renderer.SurfaceViewRenderer
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarApiClient
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarConfig
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrated Session Manager
 *
 * Manages a single LiveKit room connection for the orchestrated architecture:
 * - User (this Android app): publishes mic, subscribes to video + audio
 * - OpenAI Agent (server-side): handles STT+LLM+TTS, publishes response audio
 * - HeyGen Avatar (BYOLI): lip-syncs to Agent audio, publishes video
 *
 * Audio routing:
 * - Agent audio → played to user (TTS response)
 * - HeyGen audio → MUTED (prevent double audio)
 * - User mic → published to room (Agent subscribes for STT)
 *
 * Data channel:
 * - Receives robot commands from Agent on "robot-commands" topic
 * - Receives transcripts from Agent on "transcripts" topic
 */
class OrchestratedSessionManager(private val appContext: Context) {

    companion object {
        private const val TAG = "OrchestratedSession"
    }

    // ============================================
    // SESSION STATE
    // ============================================

    enum class SessionState {
        IDLE,
        CONNECTING,
        CONNECTED,
        STREAM_READY,
        ERROR
    }

    interface SessionListener {
        fun onStateChanged(state: SessionState)
        fun onStreamReady()
        fun onAgentSpeakStarted()
        fun onAgentSpeakEnded()
        fun onUserTranscript(text: String)
        fun onAiTranscript(text: String)
        fun onRobotCommand(command: String, arguments: String)
        fun onError(error: String)
    }

    // ============================================
    // FIELDS
    // ============================================

    private var listener: SessionListener? = null
    private val state = AtomicReference(SessionState.IDLE)

    // LiveKit room
    private var room: Room? = null
    private var remoteVideoTrack: RemoteVideoTrack? = null
    private var videoRenderer: SurfaceViewRenderer? = null
    private var surfaceReady = false

    // Session info
    private var roomName: String? = null

    // Coroutine scope
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var roomEventsJob: Job? = null

    // Track participant detection
    private var agentParticipantFound = false
    private var avatarParticipantFound = false
    private var agentIsSpeaking = false

    /** Check if a participant identity belongs to the avatar (HeyGen or LiveAvatar plugin). */
    private fun isAvatarParticipant(identity: String): Boolean {
        return identity.startsWith("heygen") || identity.startsWith("liveavatar")
    }

    // API client
    private val apiClient = LiveAvatarApiClient.getInstance()

    // ============================================
    // PUBLIC API
    // ============================================

    fun setListener(listener: SessionListener?) {
        this.listener = listener
    }

    fun setVideoRenderer(renderer: SurfaceViewRenderer?) {
        this.videoRenderer = renderer

        // If we already have a video track waiting, attach it
        if (renderer != null && remoteVideoTrack != null) {
            attachVideoTrack()
        }

        renderer?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (remoteVideoTrack != null) {
                    attachVideoTrack()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })
    }

    /**
     * Start an orchestrated session.
     * 1. Calls backend POST /orchestrated/session/start
     * 2. Gets LiveKit room URL + user token
     * 3. Connects to LiveKit room
     * 4. Publishes microphone
     * 5. Subscribes to Agent audio + HeyGen video
     */
    fun startSession(avatarId: String?) {
        if (state.get() != SessionState.IDLE && state.get() != SessionState.ERROR) {
            Log.w(TAG, "Cannot start session in state: ${state.get()}")
            return
        }

        setState(SessionState.CONNECTING)
        Log.i(TAG, "Starting orchestrated session (avatar: $avatarId)")

        apiClient.startOrchestratedSession(avatarId, object : LiveAvatarApiClient.OrchestratedSessionCallback {
            override fun onSuccess(info: LiveAvatarApiClient.OrchestratedSessionInfo) {
                Log.i(TAG, "Orchestrated session created: room=${info.roomName}")
                roomName = info.roomName
                connectToLiveKit(info.url, info.userToken)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Orchestrated session creation failed: $error")
                setState(SessionState.ERROR)
                listener?.onError("Session creation failed: $error")
            }
        })
    }

    /**
     * Stop the orchestrated session.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping orchestrated session")

        roomEventsJob?.cancel()
        roomEventsJob = null

        // Disconnect from LiveKit room
        room?.disconnect()
        room = null

        // Detach video
        detachVideoTrack()

        // Reset state
        agentParticipantFound = false
        avatarParticipantFound = false
        agentIsSpeaking = false
        remoteVideoTrack = null

        // Delete room via backend (agent and LiveAvatar plugin clean up automatically)
        val name = roomName
        if (name != null) {
            apiClient.stopOrchestratedSession(null, name, object : LiveAvatarApiClient.SimpleCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Backend orchestrated session stopped")
                }

                override fun onError(error: String) {
                    Log.w(TAG, "Backend session stop failed: $error")
                }
            })
        }

        roomName = null

        setState(SessionState.IDLE)
    }

    fun destroy() {
        stopSession()
        coroutineScope.cancel()
    }

    // ============================================
    // LIVEKIT CONNECTION
    // ============================================

    private fun connectToLiveKit(url: String, token: String) {
        coroutineScope.launch {
            try {
                Log.i(TAG, "Connecting to LiveKit room: $url")

                val newRoom = LiveKit.create(
                    appContext,
                    RoomOptions(
                        audioTrackCaptureDefaults = LocalAudioTrackOptions(
                            noiseSuppression = true,
                            echoCancellation = true,
                            autoGainControl = true,
                        )
                    ),
                    LiveKitOverrides()
                )
                room = newRoom

                // Set up room event listener
                setupRoomEvents(newRoom)

                // Connect to room
                newRoom.connect(url, token, ConnectOptions())

                Log.i(TAG, "Connected to LiveKit room: ${newRoom.name}")
                setState(SessionState.CONNECTED)

                // Publish microphone - the Agent will subscribe to this
                newRoom.localParticipant.setMicrophoneEnabled(true)
                Log.i(TAG, "Microphone published to room")

            } catch (e: Exception) {
                Log.e(TAG, "LiveKit connection failed", e)
                setState(SessionState.ERROR)
                listener?.onError("LiveKit connection failed: ${e.message}")
            }
        }
    }

    private fun setupRoomEvents(room: Room) {
        roomEventsJob?.cancel()
        roomEventsJob = coroutineScope.launch {
            room.events.collect { event ->
                handleRoomEvent(event)
            }
        }
    }

    // ============================================
    // ROOM EVENT HANDLING
    // ============================================

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                val participant = event.participant
                val identity = participant.identity?.value ?: "unknown"

                Log.i(TAG, "Track subscribed: identity=$identity, kind=${track.kind}, sid=${track.sid}")

                when {
                    // Video from Avatar (HeyGen BYOLI) → display to user
                    track is RemoteVideoTrack && isAvatarParticipant(identity) -> {
                        Log.i(TAG, "Avatar VIDEO track received (identity=$identity)")
                        avatarParticipantFound = true
                        remoteVideoTrack = track
                        attachVideoTrack()
                        checkStreamReady()
                    }

                    // Audio from Avatar (HeyGen BYOLI) → PLAY (boosted volume)
                    // Avatar audio is lip-synced with the video — play it for perfect sync.
                    track is RemoteAudioTrack && isAvatarParticipant(identity) -> {
                        Log.i(TAG, "Avatar AUDIO track received - playing (lip-synced) identity=$identity")
                        avatarParticipantFound = true
                        // Boost avatar audio volume (HeyGen output tends to be quiet)
                        try {
                            track.setVolume(3.0)
                            Log.i(TAG, "Avatar audio volume boosted to 8.0x")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set avatar audio volume: ${e.message}")
                        }
                        checkStreamReady()
                    }

                    // Audio from Agent (OpenAI Realtime) → MUTE
                    // The avatar produces lip-synced audio+video together.
                    // Mute the raw agent audio to avoid double audio / out-of-sync playback.
                    track is RemoteAudioTrack && identity.startsWith("agent") -> {
                        agentParticipantFound = true
                        Log.i(TAG, "Agent AUDIO track MUTED (avatar provides lip-synced audio)")
                        try {
                            track.setVolume(0.0)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to mute agent audio: ${e.message}")
                        }
                        checkStreamReady()
                    }

                    // Video from unknown → ignore
                    track is RemoteVideoTrack -> {
                        Log.d(TAG, "Ignoring video from non-avatar participant: $identity")
                    }

                    // Unknown audio → log
                    track is RemoteAudioTrack -> {
                        Log.d(TAG, "Audio track from unknown participant: $identity")
                    }
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                val track = event.track
                if (track == remoteVideoTrack) {
                    Log.i(TAG, "HeyGen video track unsubscribed")
                    detachVideoTrack()
                    remoteVideoTrack = null
                }
            }

            is RoomEvent.ParticipantConnected -> {
                val identity = event.participant.identity?.value ?: "unknown"
                Log.i(TAG, "Participant connected: $identity")
            }

            is RoomEvent.ParticipantDisconnected -> {
                val identity = event.participant.identity?.value ?: "unknown"
                Log.i(TAG, "Participant disconnected: $identity")

                if (identity.startsWith("agent")) {
                    Log.w(TAG, "OpenAI Agent disconnected!")
                    agentParticipantFound = false
                }
            }

            is RoomEvent.ActiveSpeakersChanged -> {
                val speakers = event.speakers.map { it.identity?.value ?: "?" }
                val agentNowSpeaking = speakers.any { it.startsWith("agent") }

                if (agentNowSpeaking && !agentIsSpeaking) {
                    agentIsSpeaking = true
                    Log.d(TAG, "Agent started speaking")
                    listener?.onAgentSpeakStarted()
                } else if (!agentNowSpeaking && agentIsSpeaking) {
                    agentIsSpeaking = false
                    Log.d(TAG, "Agent stopped speaking")
                    listener?.onAgentSpeakEnded()
                }
            }

            is RoomEvent.DataReceived -> {
                handleDataMessage(event)
            }

            is RoomEvent.Disconnected -> {
                Log.w(TAG, "Disconnected from LiveKit room")
                setState(SessionState.IDLE)
            }

            is RoomEvent.Reconnecting -> {
                Log.w(TAG, "Reconnecting to LiveKit room...")
            }

            is RoomEvent.Reconnected -> {
                Log.i(TAG, "Reconnected to LiveKit room")
            }

            else -> {
                // Log other events at debug level
                Log.d(TAG, "Room event: ${event.javaClass.simpleName}")
            }
        }
    }

    // ============================================
    // DATA CHANNEL HANDLING
    // ============================================

    private fun handleDataMessage(event: RoomEvent.DataReceived) {
        try {
            val senderIdentity = event.participant?.identity?.value ?: "unknown"
            val topic = event.topic ?: ""
            val data = String(event.data, Charsets.UTF_8)

            // Only process messages from the Agent
            if (!senderIdentity.startsWith("agent")) {
                Log.d(TAG, "Ignoring data from non-agent: $senderIdentity")
                return
            }

            Log.d(TAG, "Data from Agent (topic=$topic): ${data.take(200)}")

            val json = JSONObject(data)
            val type = json.optString("type", "")

            when (type) {
                "robot_command" -> {
                    val command = json.getString("command")
                    val arguments = json.optString("arguments", "{}")
                    Log.i(TAG, "Robot command: $command ($arguments)")
                    listener?.onRobotCommand(command, arguments)
                }

                "transcript" -> {
                    val text = json.getString("text")
                    val speaker = json.optString("speaker", "agent")
                    if (speaker == "user") {
                        Log.d(TAG, "User transcript: ${text.take(80)}")
                        listener?.onUserTranscript(text)
                    } else {
                        Log.d(TAG, "Agent transcript: ${text.take(80)}")
                        listener?.onAiTranscript(text)
                    }
                }

                else -> {
                    Log.d(TAG, "Unknown data message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling data message", e)
        }
    }

    // ============================================
    // VIDEO RENDERING
    // ============================================

    private fun attachVideoTrack() {
        val track = remoteVideoTrack ?: return
        val renderer = videoRenderer ?: return

        if (!surfaceReady) {
            Log.d(TAG, "Surface not ready, deferring video attachment")
            return
        }

        try {
            room?.initVideoRenderer(renderer)
            track.addRenderer(renderer)
            Log.i(TAG, "Video track attached to renderer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach video track", e)
        }
    }

    private fun detachVideoTrack() {
        val renderer = videoRenderer

        // Remove track from renderer
        try {
            remoteVideoTrack?.removeRenderer(renderer ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove renderer from track", e)
        }

        // Release the EGL context so renderer can be re-initialized on next session.
        // Without this, reconnecting throws IllegalStateException("Already initialized").
        try {
            renderer?.release()
            Log.d(TAG, "Video renderer released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release video renderer", e)
        }
    }

    // ============================================
    // HELPERS
    // ============================================

    private fun checkStreamReady() {
        // Stream is ready when we have at least one agent or heygen participant
        if (state.get() == SessionState.CONNECTED &&
            (agentParticipantFound || avatarParticipantFound)) {
            setState(SessionState.STREAM_READY)
            Log.i(TAG, "Stream ready! Agent: $agentParticipantFound, Avatar: $avatarParticipantFound")
            listener?.onStreamReady()
        }
    }

    private fun setState(newState: SessionState) {
        val oldState = state.getAndSet(newState)
        if (oldState != newState) {
            Log.d(TAG, "State: $oldState -> $newState")
            listener?.onStateChanged(newState)
        }
    }
}
