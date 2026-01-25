package com.tripandevent.sanbotvoice.heygen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
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

/**
 * HeyGenVideoManager
 *
 * Manages LiveKit room connection for HeyGen avatar video streaming.
 * Receives remote video track from HeyGen and renders it to a SurfaceViewRenderer.
 *
 * Kotlin implementation for LiveKit Android SDK 2.x compatibility.
 * Uses Kotlin Flows/coroutines for event handling.
 *
 * @see <a href="https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.events/-room-event/index.html">RoomEvent Documentation</a>
 */
class HeyGenVideoManager(context: Context) {

    companion object {
        private const val TAG = "HeyGenVideoManager"
    }

    interface VideoListener {
        fun onConnected()
        fun onVideoTrackReceived()
        fun onError(error: String)
        fun onDisconnected()
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var room: Room? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoRenderer: SurfaceViewRenderer? = null
    private var listener: VideoListener? = null

    // Connection state
    private var isConnectedState = false
    private var isConnectingState = false

    /**
     * Set the video listener
     */
    fun setListener(newListener: VideoListener?) {
        this.listener = newListener
    }

    /**
     * Connect to LiveKit room
     *
     * @param url   LiveKit server URL
     * @param token Access token for the room
     */
    fun connect(url: String, token: String) {
        if (isConnectedState || isConnectingState) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        isConnectingState = true
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
                isConnectedState = true
                isConnectingState = false

                mainHandler.post {
                    listener?.onConnected()
                }

                // Check for any existing tracks
                checkExistingTracks()

            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                isConnectingState = false

                mainHandler.post {
                    listener?.onError("Connection error: ${e.message}")
                }
            }
        }
    }

    /**
     * Set up room event collection using Kotlin Flow
     * @see <a href="https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.events/-room-event/-track-subscribed/index.html">TrackSubscribed</a>
     */
    private fun setupRoomEvents(currentRoom: Room) {
        coroutineScope.launch {
            currentRoom.events.events.collect { roomEvent: RoomEvent ->
                handleRoomEvent(roomEvent)
            }
        }
    }

    /**
     * Handle room events
     */
    private fun handleRoomEvent(roomEvent: RoomEvent) {
        when (roomEvent) {
            is RoomEvent.TrackSubscribed -> {
                // TrackSubscribed has: room, track, publication, participant
                val subscribedTrack: Track = roomEvent.track
                val subscribedParticipant: RemoteParticipant = roomEvent.participant
                Log.d(TAG, "Track subscribed: ${subscribedTrack.javaClass.simpleName} " +
                        "from participant: ${subscribedParticipant.identity}")

                if (subscribedTrack is RemoteVideoTrack) {
                    mainHandler.post {
                        handleVideoTrack(subscribedTrack)
                    }
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                val unsubscribedTrack: Track = roomEvent.track
                Log.d(TAG, "Track unsubscribed: ${unsubscribedTrack.javaClass.simpleName}")

                if (unsubscribedTrack == remoteVideoTrack) {
                    mainHandler.post {
                        detachVideoRenderer()
                    }
                }
            }

            is RoomEvent.Disconnected -> {
                val disconnectError: Exception? = roomEvent.error
                val errorMessage = disconnectError?.message ?: ""
                // Log the full stack trace to understand disconnect reason
                Log.w(TAG, "*** LIVEKIT DISCONNECTED ***")
                Log.w(TAG, "Disconnect error: $errorMessage")
                Log.w(TAG, "isConnectedState was: $isConnectedState")
                Log.w(TAG, "hasVideoTrack: ${remoteVideoTrack != null}")
                if (disconnectError != null) {
                    Log.w(TAG, "Disconnect exception:", disconnectError)
                }

                mainHandler.post {
                    isConnectedState = false
                    listener?.onDisconnected()
                }
            }

            is RoomEvent.FailedToConnect -> {
                val connectError: Throwable = roomEvent.error
                Log.e(TAG, "Failed to connect: ${connectError.message}")

                mainHandler.post {
                    isConnectingState = false
                    listener?.onError("Connection failed: ${connectError.message}")
                }
            }

            is RoomEvent.ParticipantConnected -> {
                val connectedParticipant: RemoteParticipant = roomEvent.participant
                Log.d(TAG, "Participant connected: ${connectedParticipant.identity}")
            }

            is RoomEvent.ParticipantDisconnected -> {
                val disconnectedParticipant: RemoteParticipant = roomEvent.participant
                Log.d(TAG, "Participant disconnected: ${disconnectedParticipant.identity}")
            }

            else -> {
                // Other events we don't need to handle
            }
        }
    }

    /**
     * Check for any existing video tracks from remote participants
     * Note: videoTrackPublications is List<Pair<TrackPublication, Track?>>
     * @see <a href="https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.room.participant/-remote-participant/index.html">RemoteParticipant</a>
     */
    private fun checkExistingTracks() {
        val currentRoom = room ?: return

        try {
            for ((_, participant) in currentRoom.remoteParticipants) {
                // videoTrackPublications is List<Pair<TrackPublication, Track?>>
                val publications = participant.videoTrackPublications
                for (pair in publications) {
                    val publication: TrackPublication = pair.first
                    val existingTrack: Track? = pair.second
                    if (existingTrack is RemoteVideoTrack) {
                        Log.d(TAG, "Found existing video track: ${publication.sid}")
                        handleVideoTrack(existingTrack)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking existing tracks: ${e.message}")
        }
    }

    /**
     * Handle received video track
     */
    private fun handleVideoTrack(track: RemoteVideoTrack) {
        if (track == remoteVideoTrack) {
            return // Already handling this track
        }

        remoteVideoTrack = track
        Log.d(TAG, "Video track received")

        // Attach to renderer if available
        val renderer = videoRenderer
        if (renderer != null) {
            attachToRenderer()
        }

        listener?.onVideoTrackReceived()
    }

    /**
     * Set the video renderer
     *
     * @param renderer SurfaceViewRenderer to display video
     */
    fun setVideoRenderer(renderer: SurfaceViewRenderer) {
        Log.d(TAG, "setVideoRenderer called, room=${room != null}, hasTrack=${remoteVideoTrack != null}")

        // Detach from old renderer
        val oldRenderer = videoRenderer
        val currentTrack = remoteVideoTrack
        if (oldRenderer != null && currentTrack != null) {
            try {
                currentTrack.removeRenderer(oldRenderer)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing old renderer: ${e.message}")
            }
        }

        videoRenderer = renderer

        // Initialize renderer for video rendering
        // Note: SurfaceViewRenderer may already be initialized by the layout system
        try {
            // Initialize renderer - LiveKit SDK handles EGL context internally
            // The renderer will use the shared EGL context from WebRTC
            renderer.init(null, null)
            renderer.setMirror(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            Log.d(TAG, "Renderer initialized successfully")
        } catch (e: Exception) {
            // Renderer might already be initialized - that's OK
            Log.w(TAG, "Renderer init issue (may be already initialized): ${e.message}")
        }

        // Attach to new renderer if track exists
        if (remoteVideoTrack != null) {
            attachToRenderer()
        }
    }

    /**
     * Attach video track to renderer
     */
    private fun attachToRenderer() {
        val track = remoteVideoTrack
        val renderer = videoRenderer

        if (track != null && renderer != null) {
            Log.i(TAG, "*** ATTACHING VIDEO TRACK TO RENDERER ***")
            Log.d(TAG, "Track enabled: ${track.enabled}, sid: ${track.sid}")
            try {
                track.addRenderer(renderer)
                Log.i(TAG, "Video track attached successfully - video should now be visible")
            } catch (e: Exception) {
                Log.e(TAG, "Error attaching renderer: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Cannot attach: track=$track, renderer=$renderer")
        }
    }

    /**
     * Detach from video renderer
     */
    private fun detachVideoRenderer() {
        val track = remoteVideoTrack
        val renderer = videoRenderer
        if (track != null && renderer != null) {
            try {
                track.removeRenderer(renderer)
            } catch (e: Exception) {
                Log.w(TAG, "Error detaching renderer: ${e.message}")
            }
        }
        remoteVideoTrack = null
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnectedState

    /**
     * Check if video track is available
     */
    fun hasVideoTrack(): Boolean = remoteVideoTrack != null

    /**
     * Disconnect from the room
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from LiveKit")

        detachVideoRenderer()

        val currentRoom = room
        if (currentRoom != null) {
            try {
                currentRoom.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting room: ${e.message}")
            }
        }
        room = null

        isConnectedState = false
        isConnectingState = false

        // Release renderer
        val renderer = videoRenderer
        if (renderer != null) {
            try {
                renderer.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing renderer: ${e.message}")
            }
        }
        videoRenderer = null

        // Cancel coroutine scope
        coroutineScope.cancel()

        listener?.onDisconnected()
    }

    /**
     * Get the current video track
     */
    fun getVideoTrack(): VideoTrack? = remoteVideoTrack
}
