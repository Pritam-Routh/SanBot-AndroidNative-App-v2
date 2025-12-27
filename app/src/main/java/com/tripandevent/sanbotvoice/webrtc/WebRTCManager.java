package com.tripandevent.sanbotvoice.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tripandevent.sanbotvoice.api.ApiClient;
import com.tripandevent.sanbotvoice.api.TokenManager;
import com.tripandevent.sanbotvoice.openai.events.ClientEvents;
import com.tripandevent.sanbotvoice.openai.events.ServerEvents;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages WebRTC connection to OpenAI Realtime API.
 * 
 * This is the core component that:
 * 1. Establishes WebRTC peer connection
 * 2. Manages audio tracks (microphone input, speaker output)
 * 3. Handles data channel for events
 * 4. Manages connection lifecycle
 */
public class WebRTCManager {
    
    private static final String TAG = Constants.TAG_WEBRTC;
    
    private final Context context;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final WebRTCCallback callback;
    
    // WebRTC components
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    
    // State
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private String currentSessionId;
    
    /**
     * Callback interface for WebRTC events
     */
    public interface WebRTCCallback {
        void onConnected();
        void onDisconnected(String reason);
        void onError(String error);
        void onServerEvent(ServerEvents.ParsedEvent event);
        void onSpeechStarted();
        void onSpeechStopped();
        void onRemoteAudioTrack(AudioTrack track);
    }
    
    public WebRTCManager(Context context, WebRTCCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Initialize WebRTC components.
     * Must be called before connect().
     */
    public void initialize() {
        Logger.d(TAG, "Initializing WebRTC...");
        
        executor.execute(() -> {
            try {
                // Initialize PeerConnectionFactory
                PeerConnectionFactory.InitializationOptions initOptions = 
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions();
                PeerConnectionFactory.initialize(initOptions);
                
                // Create audio device module
                AudioDeviceModule audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(WebRTCConfig.AudioConstraints.ECHO_CANCELLATION)
                    .setUseHardwareNoiseSuppressor(WebRTCConfig.AudioConstraints.NOISE_SUPPRESSION)
                    .createAudioDeviceModule();
                
                // Create PeerConnectionFactory
                PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory();
                
                Logger.d(TAG, "WebRTC initialized successfully");
                
            } catch (Exception e) {
                Logger.e(e, "Failed to initialize WebRTC");
                notifyError("Failed to initialize WebRTC: " + e.getMessage());
            }
        });
    }
    
    /**
     * Connect to OpenAI Realtime API.
     * This will:
     * 1. Get ephemeral token
     * 2. Create peer connection
     * 3. Create and send SDP offer
     * 4. Receive and set SDP answer
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
        
        Logger.d(TAG, "Starting connection to OpenAI Realtime API...");
        
        // Step 1: Get ephemeral token
        TokenManager.getInstance().getToken(new TokenManager.TokenCallback() {
            @Override
            public void onTokenReceived(String token) {
                Logger.d(TAG, "Ephemeral token received");
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
    
    /**
     * Connect with the provided ephemeral token
     */
    private void connectWithToken(String token) {
        executor.execute(() -> {
            try {
                // Step 2: Create peer connection
                createPeerConnection();
                
                // Step 3: Create local audio track
                createLocalAudioTrack();
                
                // Step 4: Create data channel
                createDataChannel();
                
                // Step 5: Create and send offer
                createAndSendOffer(token);
                
            } catch (Exception e) {
                Logger.e(e, "Failed to connect");
                isConnecting.set(false);
                notifyError("Connection failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Create WebRTC peer connection
     */
    private void createPeerConnection() {
        Logger.d(TAG, "Creating peer connection...");
        
        PeerConnection.RTCConfiguration config = WebRTCConfig.getRTCConfiguration();
        
        peerConnection = peerConnectionFactory.createPeerConnection(
            config,
            new PeerConnectionObserver()
        );
        
        if (peerConnection == null) {
            throw new RuntimeException("Failed to create PeerConnection");
        }
        
        Logger.d(TAG, "Peer connection created");
    }
    
    /**
     * Create local audio track for microphone input
     */
    private void createLocalAudioTrack() {
        Logger.d(TAG, "Creating local audio track...");
        
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
            "googEchoCancellation", String.valueOf(WebRTCConfig.AudioConstraints.ECHO_CANCELLATION)));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
            "googAutoGainControl", String.valueOf(WebRTCConfig.AudioConstraints.AUTO_GAIN_CONTROL)));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
            "googNoiseSuppression", String.valueOf(WebRTCConfig.AudioConstraints.NOISE_SUPPRESSION)));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
            "googHighpassFilter", String.valueOf(WebRTCConfig.AudioConstraints.HIGH_PASS_FILTER)));
        
        audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource);
        localAudioTrack.setEnabled(true);
        
        // Add track to peer connection
        peerConnection.addTrack(localAudioTrack);
        
        Logger.d(TAG, "Local audio track created and added");
    }
    
    /**
     * Create data channel for events
     */
    private void createDataChannel() {
        Logger.d(TAG, "Creating data channel...");
        
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        
        dataChannel = peerConnection.createDataChannel(Constants.WEBRTC_DATA_CHANNEL_NAME, init);
        dataChannel.registerObserver(new DataChannelObserver());
        
        Logger.d(TAG, "Data channel created: %s", Constants.WEBRTC_DATA_CHANNEL_NAME);
    }
    
    /**
     * Create SDP offer and send to OpenAI
     */
    private void createAndSendOffer(String token) {
        Logger.d(TAG, "Creating SDP offer...");
        
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        
        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Logger.d(TAG, "SDP offer created");
                
                // Set local description
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    
                    @Override
                    public void onSetSuccess() {
                        Logger.d(TAG, "Local description set");
                        // Send offer to OpenAI
                        sendOfferToOpenAI(sdp.description, token);
                    }
                    
                    @Override
                    public void onCreateFailure(String error) {}
                    
                    @Override
                    public void onSetFailure(String error) {
                        Logger.e(TAG, "Failed to set local description: %s", error);
                        isConnecting.set(false);
                        notifyError("Failed to set local description: " + error);
                    }
                }, sdp);
            }
            
            @Override
            public void onSetSuccess() {}
            
            @Override
            public void onCreateFailure(String error) {
                Logger.e(TAG, "Failed to create offer: %s", error);
                isConnecting.set(false);
                notifyError("Failed to create offer: " + error);
            }
            
            @Override
            public void onSetFailure(String error) {}
        }, constraints);
    }
    
    /**
     * Send SDP offer to OpenAI Realtime API
     * Uses either direct OpenAI connection or backend proxy based on mode
     */
    private void sendOfferToOpenAI(String sdp, String token) {

            sendOfferDirectToOpenAI(sdp, token);
    }
    
    /**
     * Send SDP offer via test backend /session endpoint
     */
    /**
     * Send SDP offer via test backend /session endpoint
     */
    private void sendOfferViaBackend(String sdp) {
        Logger.d(TAG, "Sending SDP offer via backend /session...");
        Logger.d(TAG, "SDP Offer starts with: %s", sdp.substring(0, Math.min(50, sdp.length())));

        executor.execute(() -> {
            try {
                OkHttpClient client = ApiClient.getInstance().getHttpClient();

                RequestBody body = RequestBody.create(
                        sdp,
                        MediaType.parse("text/plain; charset=utf-8")
                );

                Request request = new Request.Builder()
                        .url(Constants.BACKEND_BASE_URL + "/session")
                        .post(body)
                        .addHeader("Content-Type", "text/plain")
                        .build();

                Response response = client.newCall(request).execute();

                if (response.isSuccessful() && response.body() != null) {
                    String answerSdp = response.body().string();
                    Logger.d(TAG, "Received SDP answer from backend");
                    Logger.d(TAG, "SDP Answer length: %d", answerSdp.length());
                    Logger.d(TAG, "SDP Answer starts with: %s",
                            answerSdp.substring(0, Math.min(50, answerSdp.length())));
                    setRemoteDescription(answerSdp);
                } else {
                    String errorBody = response.body() != null ?
                            response.body().string() : "No body";
                    Logger.e(TAG, "Backend SDP request failed: %d - %s",
                            response.code(), errorBody);
                    isConnecting.set(false);
                    notifyError("Backend connection failed: " + response.code());
                }

            } catch (IOException e) {
                Logger.e(e, "Network error sending SDP via backend");
                isConnecting.set(false);
                notifyError("Network error: " + e.getMessage());
            }
        });
    }
    
    /**
     * Send SDP offer directly to OpenAI Realtime API
     */
    private void sendOfferDirectToOpenAI(String sdp, String token) {
        Logger.d(TAG, "Sending SDP offer directly to OpenAI...");

        executor.execute(() -> {
            try {
                OkHttpClient client = ApiClient.getInstance().getHttpClient();

                RequestBody body = RequestBody.create(
                        sdp,
                        MediaType.parse("application/sdp")
                );
                // Use the same URL format as the web app
                String url = "https://api.openai.com/v1/realtime/calls?model=" + Constants.OPENAI_REALTIME_MODEL;

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Authorization", "Bearer " + token)
                        .addHeader("Content-Type", "application/sdp")
                        .build();

                Response response = client.newCall(request).execute();

                if (response.isSuccessful() && response.body() != null) {
                    String answerSdp = response.body().string();
                    Logger.d(TAG, "Received SDP answer from OpenAI");
                    setRemoteDescription(answerSdp);
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "No body";
                    Logger.e(TAG, "OpenAI SDP request failed: %d - %s", response.code(), errorBody);
                    isConnecting.set(false);
                    notifyError("OpenAI connection failed: " + response.code());
                }

            } catch (IOException e) {
                Logger.e(e, "Network error sending SDP to OpenAI");
                isConnecting.set(false);
                notifyError("Network error: " + e.getMessage());
            }
        });
    }
    
    /**
     * Set remote description (SDP answer from OpenAI)
     */
    private void setRemoteDescription(String sdp) {
        SessionDescription answer = new SessionDescription(
            SessionDescription.Type.ANSWER, 
            sdp
        );
        
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {}
            
            @Override
            public void onSetSuccess() {
                Logger.d(TAG, "Remote description set - connection established");
                isConnecting.set(false);
                isConnected.set(true);
                notifyConnected();
            }
            
            @Override
            public void onCreateFailure(String error) {}
            
            @Override
            public void onSetFailure(String error) {
                Logger.e(TAG, "Failed to set remote description: %s", error);
                isConnecting.set(false);
                notifyError("Failed to set remote description: " + error);
            }
        }, answer);
    }

    public void sendFunctionResult(String callId, String result) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) {
            Logger.e(TAG, "Cannot send function result - data channel not open");
            return;
        }
        
        try {
            // Create conversation.item.create event with function_call_output
            String event = String.format(
                "{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"function_call_output\",\"call_id\":\"%s\",\"output\":\"%s\"}}",
                callId,
                result.replace("\"", "\\\"")
            );
            
            ByteBuffer buffer = ByteBuffer.wrap(event.getBytes(StandardCharsets.UTF_8));
            dataChannel.send(new DataChannel.Buffer(buffer, false));
            
            Logger.d(TAG, "Sent function result for call: %s", callId);
            
            // Trigger response generation
            String responseEvent = "{\"type\":\"response.create\"}";
            ByteBuffer responseBuffer = ByteBuffer.wrap(responseEvent.getBytes(StandardCharsets.UTF_8));
            dataChannel.send(new DataChannel.Buffer(responseBuffer, false));
            
        } catch (Exception e) {
            Logger.e(e, "Failed to send function result");
        }
    }
    
    /**
     * Send event via data channel
     */
    public void sendEvent(String eventJson) {
        if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) {
            Logger.w(TAG, "Data channel not open, cannot send event");
            return;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(eventJson.getBytes(StandardCharsets.UTF_8));
        DataChannel.Buffer data = new DataChannel.Buffer(buffer, false);
        
        boolean sent = dataChannel.send(data);
        if (sent) {
            Logger.d(TAG, "Event sent: %s", eventJson.substring(0, Math.min(100, eventJson.length())));
        } else {
            Logger.w(TAG, "Failed to send event");
        }
    }
    
    /**
     * Update session configuration
     */
    public void updateSession(ClientEvents.SessionConfig config) {
        String event = ClientEvents.sessionUpdate(config);
        sendEvent(event);
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
     * Clear output audio buffer (for interruption handling)
     */
    public void clearOutputAudioBuffer() {
        sendEvent(ClientEvents.outputAudioBufferClear());
    }
    
    /**
     * Send function call output
     */
    public void sendFunctionCallOutput(String callId, String output) {
        String event = ClientEvents.conversationItemCreateFunctionOutput(callId, output);
        sendEvent(event);
        // Trigger response after providing function output
        createResponse();
    }
    
    /**
     * Mute/unmute microphone
     */
    public void setMicrophoneMuted(boolean muted) {
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
            Logger.d(TAG, "Microphone %s", muted ? "muted" : "unmuted");
        }
    }
    
    /**
     * Check if connected
     */
    public boolean isConnected() {
        return isConnected.get();
    }
    
    /**
     * Disconnect and cleanup
     */
    public void disconnect() {
        Logger.d(TAG, "Disconnecting...");
        
        isConnected.set(false);
        isConnecting.set(false);
        
        executor.execute(() -> {
            if (dataChannel != null) {
                dataChannel.close();
                dataChannel = null;
            }
            
            if (localAudioTrack != null) {
                localAudioTrack.setEnabled(false);
                localAudioTrack.dispose();
                localAudioTrack = null;
            }
            
            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }
            
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
            }
            
            Logger.d(TAG, "Disconnected");
            notifyDisconnected("User initiated disconnect");
        });
    }
    
    /**
     * Release all resources
     */
    public void release() {
        disconnect();
        
        executor.execute(() -> {
            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
                peerConnectionFactory = null;
            }
            
            PeerConnectionFactory.shutdownInternalTracer();
            Logger.d(TAG, "WebRTC released");
        });
        
        executor.shutdown();
    }
    
    // ============================================
    // OBSERVERS
    // ============================================
    
    /**
     * PeerConnection observer
     */
    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
            Logger.d(TAG, "Signaling state: %s", state);
        }
        
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Logger.d(TAG, "ICE connection state: %s", state);
            
            switch (state) {
                case CONNECTED:
                case COMPLETED:
                    // Connection established
                    break;
                case DISCONNECTED:
                    Logger.w(TAG, "ICE disconnected");
                    break;
                case FAILED:
                    Logger.e(TAG, "ICE connection failed");
                    isConnected.set(false);
                    notifyError("ICE connection failed");
                    break;
                case CLOSED:
                    isConnected.set(false);
                    notifyDisconnected("Connection closed");
                    break;
            }
        }
        
        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Logger.d(TAG, "ICE receiving: %s", receiving);
        }
        
        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            Logger.d(TAG, "ICE gathering state: %s", state);
        }
        
        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Logger.d(TAG, "ICE candidate: %s", candidate.sdp);
        }
        
        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        
        @Override
        public void onAddStream(MediaStream stream) {
            Logger.d(TAG, "Remote stream added");
        }
        
        @Override
        public void onRemoveStream(MediaStream stream) {
            Logger.d(TAG, "Remote stream removed");
        }
        
        @Override
        public void onDataChannel(DataChannel channel) {
            Logger.d(TAG, "Data channel received: %s", channel.label());
        }
        
        @Override
        public void onRenegotiationNeeded() {
            Logger.d(TAG, "Renegotiation needed");
        }
        
        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            Logger.d(TAG, "Remote track added");
            
            if (receiver.track() instanceof AudioTrack) {
                AudioTrack remoteAudioTrack = (AudioTrack) receiver.track();
                remoteAudioTrack.setEnabled(true);
                
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onRemoteAudioTrack(remoteAudioTrack);
                    }
                });
            }
        }
        
        @Override
        public void onTrack(RtpTransceiver transceiver) {
            Logger.d(TAG, "Track transceiver: %s", transceiver.getMediaType());
        }
    }
    
    /**
     * DataChannel observer
     */
    private class DataChannelObserver implements DataChannel.Observer {
        @Override
        public void onBufferedAmountChange(long previousAmount) {}
        
        @Override
        public void onStateChange() {
            if (dataChannel != null) {
                Logger.d(TAG, "Data channel state: %s", dataChannel.state());
                
                if (dataChannel.state() == DataChannel.State.OPEN) {
                    Logger.i(TAG, "Data channel open - ready to send/receive events");
                }
            }
        }
        
        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            String message = new String(bytes, StandardCharsets.UTF_8);
            
            Logger.d(TAG, "Received event: %s", message.substring(0, Math.min(200, message.length())));
            
            // Parse and dispatch event
            ServerEvents.ParsedEvent event = ServerEvents.parse(message);
            
            if (event.isValid()) {
                handleServerEvent(event);
            }
        }
    }
    
    /**
     * Handle server event
     */
    private void handleServerEvent(ServerEvents.ParsedEvent event) {
        // Handle speech events locally for quick response
        if (event.isSpeechStarted()) {
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSpeechStarted();
                }
            });
        } else if (event.isSpeechStopped()) {
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSpeechStopped();
                }
            });
        }
        
        // Forward all events to callback
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onServerEvent(event);
            }
        });
    }
    
    // ============================================
    // NOTIFICATION HELPERS
    // ============================================
    
    private void notifyConnected() {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onConnected();
            }
        });
    }
    
    private void notifyDisconnected(String reason) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDisconnected(reason);
            }
        });
    }
    
    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onError(error);
            }
        });
    }
}
