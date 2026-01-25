package com.tripandevent.sanbotvoice.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tripandevent.sanbotvoice.MainActivity;
import com.tripandevent.sanbotvoice.R;
import com.tripandevent.sanbotvoice.config.AgentConfig;
import com.tripandevent.sanbotvoice.audio.AudioBooster;
import com.tripandevent.sanbotvoice.audio.AudioProcessor;
import com.tripandevent.sanbotvoice.audio.SpeakerIdentifier;
import com.tripandevent.sanbotvoice.analytics.ConversationAnalytics;
import com.tripandevent.sanbotvoice.functions.FunctionExecutor;
import com.tripandevent.sanbotvoice.openai.events.ClientEvents;
import com.tripandevent.sanbotvoice.openai.events.ServerEvents;
import com.tripandevent.sanbotvoice.sanbot.SanbotMotionManager;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;
import com.tripandevent.sanbotvoice.webrtc.WebRTCManager;
import com.tripandevent.sanbotvoice.heygen.HeyGenConfig;
import com.tripandevent.sanbotvoice.heygen.HeyGenSessionManager;
import com.tripandevent.sanbotvoice.heygen.HeyGenVideoManager;
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarConfig;
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarSessionManager;
import com.tripandevent.sanbotvoice.liveavatar.AudioDeltaBuffer;

import org.webrtc.AudioTrack;

/**
 * Foreground service that manages the voice conversation lifecycle.
 * Integrates with Sanbot robot motion for interactive responses.
 */
public class VoiceAgentService extends Service implements WebRTCManager.WebRTCCallback {
    
    private static final String TAG = "VoiceService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "voice_agent_channel";
    
    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Core components
    private WebRTCManager webRTCManager;
    private FunctionExecutor functionExecutor;
    private AudioProcessor audioProcessor;
    private AudioBooster audioBooster;
    private SpeakerIdentifier speakerIdentifier;
    private ConversationAnalytics analytics;
    private SanbotMotionManager sanbotMotionManager;

    // HeyGen Avatar components
    private HeyGenSessionManager heyGenSessionManager;
    private boolean heyGenEnabled = false;

    // LiveAvatar components (Audio-based streaming for lowest latency)
    private LiveAvatarSessionManager liveAvatarSessionManager;
    private boolean liveAvatarEnabled = false;

    // State
    private ConversationState currentState = ConversationState.IDLE;
    private VoiceAgentListener listener;
    private String currentSessionId;
    private long conversationStartTime;
    private int messageCount;
    private boolean sessionConfigured = false;
    
    // Transcript accumulation
    private StringBuilder currentTranscript = new StringBuilder();
    private StringBuilder userTranscript = new StringBuilder();
    private StringBuilder aiTranscript = new StringBuilder();

    // Agent configuration (can be customized from client side)
    private AgentConfig agentConfig = AgentConfig.getDefault();

    // AI instructions (built from agentConfig or set directly)
    private String aiInstructions = null;
    
    /**
     * Listener interface for UI updates
     */
    public interface VoiceAgentListener {
        void onStateChanged(ConversationState state);
        void onTranscript(String text, boolean isUser);
        void onError(String error);
        void onAudioLevel(float level);
        void onSpeakerIdentified(String speakerName, float confidence);

        // HeyGen Avatar callbacks
        default void onAvatarVideoReady() {}
        default void onAvatarError(String error) {}
    }
    
    public class LocalBinder extends Binder {
        public VoiceAgentService getService() {
            return VoiceAgentService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Logger.i(TAG, "VoiceAgentService created");
        
        // Initialize WebRTC manager
        webRTCManager = new WebRTCManager(this, this);
        webRTCManager.initialize();
        
        // Initialize function executor with context for robot motion
        functionExecutor = new FunctionExecutor();
        functionExecutor.setContext(this);
        
        // Initialize audio booster
        audioBooster = new AudioBooster(this);
        
        // Initialize Sanbot motion manager
        sanbotMotionManager = SanbotMotionManager.getInstance(this);
        
        // Initialize audio processor
        audioProcessor = new AudioProcessor(this);
        audioProcessor.setCallback(new AudioProcessor.AudioProcessorCallback() {
            @Override
            public void onSpeechStart() {
                Logger.d(TAG, "Local speech started");
                if (currentState == ConversationState.READY) {
                    setState(ConversationState.LISTENING);
                    // Show listening gesture
                    if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
                        sanbotMotionManager.showListening();
                    }
                }

                // Interrupt avatar when user starts speaking (barge-in)
                if (heyGenEnabled && heyGenSessionManager != null && heyGenSessionManager.isReady()) {
                    heyGenSessionManager.interrupt();
                }
                if (liveAvatarEnabled && liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected()) {
                    liveAvatarSessionManager.interrupt();
                }
            }
            
            @Override
            public void onSpeechEnd() {
                Logger.d(TAG, "Local speech ended");
                if (currentState == ConversationState.LISTENING) {
                    setState(ConversationState.PROCESSING);
                    // Show thinking gesture while processing
                    if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
                        sanbotMotionManager.showThinking();
                    }
                }
            }
            
            @Override
            public void onAudioLevel(float level) {
                if (listener != null) {
                    listener.onAudioLevel(level);
                }
            }
            
            @Override
            public void onError(String error) {
                Logger.e(TAG, "Audio processor error: %s", error);
            }
        });
        
        // Initialize speaker identifier
        speakerIdentifier = new SpeakerIdentifier(this);
        
        // Initialize analytics
        analytics = new ConversationAnalytics(this);

        // Initialize HeyGen if enabled
        initializeHeyGen();

        // Initialize LiveAvatar if enabled (takes precedence over HeyGen if both enabled)
        initializeLiveAvatar();

        createNotificationChannel();
    }

    /**
     * Initialize HeyGen avatar support
     */
    private void initializeHeyGen() {
        if (!HeyGenConfig.isEnabled()) {
            Logger.d(TAG, "HeyGen avatar is disabled");
            heyGenEnabled = false;
            return;
        }

        heyGenEnabled = true;
        heyGenSessionManager = new HeyGenSessionManager();
        heyGenSessionManager.setContext(this);
        heyGenSessionManager.setListener(new HeyGenSessionManager.SessionListener() {
            @Override
            public void onSessionCreated(String sessionId) {
                Logger.d(TAG, "HeyGen session created: %s", sessionId);
            }

            @Override
            public void onSessionConnected() {
                Logger.i(TAG, "HeyGen avatar connected");
            }

            @Override
            public void onSessionError(String error) {
                Logger.e(TAG, "HeyGen avatar error: %s", error);
                heyGenEnabled = false;
                if (listener != null) {
                    listener.onAvatarError(error);
                }
            }

            @Override
            public void onSessionStopped() {
                Logger.d(TAG, "HeyGen session stopped");
            }

            @Override
            public void onVideoTrackReady() {
                Logger.i(TAG, "HeyGen video track ready");
                if (listener != null) {
                    listener.onAvatarVideoReady();
                }
            }
        });

        Logger.i(TAG, "HeyGen avatar support initialized");
    }

    /**
     * Initialize LiveAvatar support (Audio-based streaming for lowest latency)
     *
     * If LiveAvatar is enabled, it takes precedence over HeyGen for avatar streaming.
     * LiveAvatar uses direct audio streaming from OpenAI, bypassing text-to-speech
     * for the lowest possible latency (~200-400ms vs ~500-800ms with text).
     */
    private void initializeLiveAvatar() {
        if (!LiveAvatarConfig.isEnabled()) {
            Logger.d(TAG, "LiveAvatar is disabled");
            liveAvatarEnabled = false;
            return;
        }

        // If LiveAvatar is enabled, disable HeyGen to avoid conflicts
        if (heyGenEnabled) {
            Logger.i(TAG, "LiveAvatar enabled - disabling HeyGen to avoid conflicts");
            heyGenEnabled = false;
            if (heyGenSessionManager != null) {
                heyGenSessionManager.destroy();
                heyGenSessionManager = null;
            }
        }

        liveAvatarEnabled = true;
        liveAvatarSessionManager = new LiveAvatarSessionManager(this);
        liveAvatarSessionManager.setListener(new LiveAvatarSessionManager.SessionListener() {
            @Override
            public void onStateChanged(LiveAvatarSessionManager.SessionState state) {
                Logger.d(TAG, "LiveAvatar state: %s", state);
                // When LiveAvatar becomes connected, ensure OpenAI audio is muted
                if (state == LiveAvatarSessionManager.SessionState.CONNECTED) {
                    Logger.i(TAG, "LiveAvatar CONNECTED - ensuring OpenAI audio is muted");
                    if (webRTCManager != null) {
                        webRTCManager.setRemoteAudioMuted(true);
                    }
                }
                // If LiveAvatar disconnects or errors, unmute OpenAI audio
                if (state == LiveAvatarSessionManager.SessionState.ERROR ||
                    state == LiveAvatarSessionManager.SessionState.IDLE) {
                    if (liveAvatarEnabled) {
                        Logger.i(TAG, "LiveAvatar disconnected - unmuting OpenAI audio");
                        if (webRTCManager != null) {
                            webRTCManager.setRemoteAudioMuted(false);
                        }
                    }
                }
            }

            @Override
            public void onStreamReady() {
                Logger.i(TAG, "LiveAvatar stream ready - avatar is now ready for lip sync");
                // Enable text buffer for lip sync now that stream is ready
                liveAvatarSessionManager.enableTextBuffer();
                // Ensure OpenAI audio stays muted (avatar will handle audio)
                if (webRTCManager != null) {
                    webRTCManager.setRemoteAudioMuted(true);
                }
                // Configure audio booster for LiveAvatar TTS playback
                // LiveKit audio goes through STREAM_MUSIC, so boost that
                if (audioBooster != null && !audioBooster.isConfigured()) {
                    audioBooster.configureForMaxVolume();
                    Logger.i(TAG, "Audio boosted for LiveAvatar: %s", audioBooster.getVolumeInfo());
                }
                if (listener != null) {
                    listener.onAvatarVideoReady();
                }
            }

            @Override
            public void onConnectionQualityChanged(LiveAvatarSessionManager.ConnectionQuality quality) {
                Logger.d(TAG, "LiveAvatar connection quality: %s", quality);
            }

            @Override
            public void onUserTranscription(String text) {
                Logger.d(TAG, "LiveAvatar user transcription: %s", text);
            }

            @Override
            public void onAvatarTranscription(String text) {
                Logger.d(TAG, "LiveAvatar avatar transcription: %s", text);
            }

            @Override
            public void onAvatarSpeakStarted() {
                Logger.d(TAG, "LiveAvatar speak started");
            }

            @Override
            public void onAvatarSpeakEnded() {
                Logger.d(TAG, "LiveAvatar speak ended");
            }

            @Override
            public void onError(String error) {
                Logger.e(TAG, "LiveAvatar error: %s", error);
                liveAvatarEnabled = false;
                // Unmute OpenAI audio since avatar failed - let user hear the agent
                if (webRTCManager != null) {
                    Logger.i(TAG, "Unmuting OpenAI audio - LiveAvatar failed, falling back to direct audio");
                    webRTCManager.setRemoteAudioMuted(false);
                }
                if (listener != null) {
                    listener.onAvatarError(error);
                }
            }
        });

        Logger.i(TAG, "LiveAvatar support initialized (audio streaming: %b)",
            LiveAvatarConfig.useAudioStreaming());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "Service started");
        startForeground(NOTIFICATION_ID, createNotification());
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Logger.i(TAG, "VoiceAgentService destroyed");
        stopConversation();
        
        if (webRTCManager != null) {
            webRTCManager.disconnect();
        }
        
        if (audioProcessor != null) {
            audioProcessor.release();
        }
        
        if (audioBooster != null) {
            audioBooster.resetAudio();
        }
        
        if (functionExecutor != null) {
            functionExecutor.shutdown();
        }
        
        if (sanbotMotionManager != null) {
            sanbotMotionManager.release();
        }

        // Cleanup HeyGen
        if (heyGenSessionManager != null) {
            heyGenSessionManager.destroy();
            heyGenSessionManager = null;
        }

        // Cleanup LiveAvatar
        if (liveAvatarSessionManager != null) {
            liveAvatarSessionManager.destroy();
            liveAvatarSessionManager = null;
        }

        super.onDestroy();
    }
    
    // ============================================
    // PUBLIC METHODS
    // ============================================
    
    public void setListener(VoiceAgentListener listener) {
        this.listener = listener;
    }
    
    /**
     * Set custom AI instructions directly.
     * This overrides any AgentConfig settings.
     */
    public void setAiInstructions(String instructions) {
        this.aiInstructions = instructions;
        Logger.d(TAG, "AI instructions set directly (length: %d)", instructions != null ? instructions.length() : 0);
    }

    /**
     * Configure the voice agent with an AgentConfig.
     * Must be called before starting a conversation.
     *
     * @param config The agent configuration containing personality, instructions, etc.
     */
    public void configure(AgentConfig config) {
        this.agentConfig = config;
        this.aiInstructions = config.buildSystemInstructions();
        Logger.i(TAG, "Agent configured: name=%s, company=%s, personality=%s",
            config.getAgentName(), config.getCompanyName(), config.getPersonality().name());
    }

    /**
     * Get the current agent configuration.
     */
    public AgentConfig getAgentConfig() {
        return agentConfig;
    }

    /**
     * Get the current AI instructions (either from config or set directly).
     */
    public String getAiInstructions() {
        if (aiInstructions == null || aiInstructions.isEmpty()) {
            aiInstructions = agentConfig.buildSystemInstructions();
        }
        return aiInstructions;
    }
    
    /**
     * Initialize Sanbot SDK managers for robot motion control.
     * Call this from Activity after obtaining SDK managers.
     */
    public void initializeSanbotSdk(Object headManager, Object wingManager, 
                                    Object wheelManager, Object sysManager) {
        if (sanbotMotionManager != null) {
            sanbotMotionManager.initialize(headManager, wingManager, wheelManager, sysManager);
            Logger.i(TAG, "Sanbot SDK initialized for motion control");
        }
    }
    
    public SanbotMotionManager getSanbotMotionManager() {
        return sanbotMotionManager;
    }

    /**
     * Get the HeyGen session manager for UI binding
     */
    public HeyGenSessionManager getHeyGenSessionManager() {
        return heyGenSessionManager;
    }

    /**
     * Check if HeyGen avatar is enabled and active
     */
    public boolean isHeyGenEnabled() {
        return heyGenEnabled && heyGenSessionManager != null;
    }

    /**
     * Get the LiveAvatar session manager for UI binding
     */
    public LiveAvatarSessionManager getLiveAvatarSessionManager() {
        return liveAvatarSessionManager;
    }

    /**
     * Check if LiveAvatar is enabled and active
     */
    public boolean isLiveAvatarEnabled() {
        return liveAvatarEnabled && liveAvatarSessionManager != null;
    }

    /**
     * Check if any avatar (HeyGen or LiveAvatar) is enabled
     */
    public boolean isAnyAvatarEnabled() {
        return isHeyGenEnabled() || isLiveAvatarEnabled();
    }

    public void startConversation() {
        if (currentState != ConversationState.IDLE && currentState != ConversationState.ERROR) {
            Logger.w(TAG, "Cannot start conversation in state: %s", currentState);
            return;
        }
        
        Logger.i(TAG, "Starting conversation...");
        setState(ConversationState.CONNECTING);
        
        currentTranscript = new StringBuilder();
        userTranscript = new StringBuilder();
        aiTranscript = new StringBuilder();
        conversationStartTime = System.currentTimeMillis();
        messageCount = 0;
        sessionConfigured = false;
        
        analytics.startSession();
        webRTCManager.connect();
    }
    
    public void stopConversation() {
        if (currentState == ConversationState.IDLE) {
            return;
        }

        Logger.i(TAG, "Stopping conversation...");

        // Stop HeyGen avatar session
        if (heyGenSessionManager != null) {
            heyGenSessionManager.stopSession();
        }

        // Stop LiveAvatar session
        if (liveAvatarSessionManager != null) {
            liveAvatarSessionManager.stopSession();
        }

        // Say goodbye gesture
        if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
            sanbotMotionManager.sayGoodbye();
        }

        ConversationAnalytics.SessionSummary summary = analytics.endSession();
        if (summary != null) {
            Logger.i(TAG, "Session summary: %s", summary.toString());
        }

        webRTCManager.disconnect();
        
        if (audioProcessor != null) {
            audioProcessor.release();
        }
        
        // Reset audio to original settings
        if (audioBooster != null) {
            audioBooster.resetAudio();
        }
        
        // Reset robot to neutral
        if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
            mainHandler.postDelayed(() -> sanbotMotionManager.resetAll(), 1500);
        }
        
        logDisconnect("user_initiated");
        setState(ConversationState.IDLE);
    }
    
    public ConversationState getCurrentState() {
        return currentState;
    }
    
    public long getConversationDuration() {
        if (conversationStartTime == 0) return 0;
        return System.currentTimeMillis() - conversationStartTime;
    }
    
    public String getCurrentSessionId() {
        return currentSessionId;
    }
    
    public ConversationAnalytics getAnalytics() {
        return analytics;
    }
    
    public SpeakerIdentifier getSpeakerIdentifier() {
        return speakerIdentifier;
    }
    
    public AudioBooster getAudioBooster() {
        return audioBooster;
    }
    
    public void setAudioBoostLevel(int percent) {
        if (audioBooster != null) {
            audioBooster.setBoostLevel(percent);
        }
    }
    
    public int getAudioBoostLevel() {
        if (audioBooster != null) {
            return audioBooster.getBoostLevel();
        }
        return 0;
    }
    
    public void processAudioSamples(short[] samples) {
        if (audioProcessor != null && audioProcessor.isInitialized()) {
            audioProcessor.processAudioSamples(samples);
        }
        
        if (speakerIdentifier != null && samples != null && samples.length >= 480) {
            SpeakerIdentifier.IdentificationResult result = speakerIdentifier.identifySpeaker(samples);
            if (result != null && !result.isNewSpeaker && result.confidence > 0.75f) {
                Logger.d(TAG, "Speaker identified: %s (%.0f%%)", 
                    result.speakerName, result.confidence * 100);
                if (listener != null) {
                    listener.onSpeakerIdentified(result.speakerName, result.confidence);
                }
            }
        }
    }
    
    // ============================================
    // WebRTCManager.WebRTCCallback implementation
    // ============================================
    
    @Override
    public void onConnected() {
        Logger.i(TAG, "WebRTC connected");
        setState(ConversationState.CONFIGURING);

        // Configure audio for maximum volume
        if (audioBooster != null) {
            audioBooster.configureForMaxVolume();
            Logger.i(TAG, "Audio configured: %s", audioBooster.getVolumeInfo());
        }

        if (audioProcessor != null) {
            audioProcessor.initialize(0);
        }

        // Start HeyGen avatar session (runs in parallel with OpenAI session config)
        if (heyGenEnabled && heyGenSessionManager != null) {
            Logger.d(TAG, "Starting HeyGen avatar session...");
            heyGenSessionManager.startSession(HeyGenConfig.getAvatarId());
        }

        // Start LiveAvatar session (runs in parallel with OpenAI session config)
        if (liveAvatarEnabled && liveAvatarSessionManager != null) {
            Logger.d(TAG, "Starting LiveAvatar session...");
            liveAvatarSessionManager.startSession(LiveAvatarConfig.getAvatarId());
        }

        // Send session configuration with robot motion tools
        configureSession();
    }
    
    /**
     * Configure the OpenAI session with tools and instructions
     */
    private void configureSession() {
        if (sessionConfigured) {
            Logger.d(TAG, "Session already configured");
            return;
        }
        
        Logger.d(TAG, "Configuring session with robot motion support...");

        // Get instructions from config (builds if not already set)
        String instructions = getAiInstructions();
        Logger.d(TAG, "Using AI instructions (length: %d)", instructions.length());

        // Create session update with robot-aware instructions
        String sessionUpdate = ClientEvents.sessionUpdateWithRobotMotion(instructions);
        
        webRTCManager.sendEvent(sessionUpdate);
        sessionConfigured = true;
        
        Logger.i(TAG, "Session configured with robot motion tools");
        
        // Perform greeting gesture
        if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
            mainHandler.postDelayed(() -> {
                sanbotMotionManager.greet();
            }, 500);
        }
    }
    
    @Override
    public void onDisconnected(String reason) {
        Logger.i(TAG, "WebRTC disconnected: %s", reason);
        
        if (analytics != null) {
            analytics.recordError("disconnect: " + reason);
        }
        
        // Reset audio when disconnected
        if (audioBooster != null) {
            audioBooster.resetAudio();
        }
        
        if (currentState != ConversationState.IDLE) {
            logDisconnect(reason);
            setState(ConversationState.IDLE);
        }
    }
    
    @Override
    public void onError(String error) {
        Logger.e(TAG, "WebRTC error: %s", error);
        
        if (analytics != null) {
            analytics.recordError(error);
        }
        
        // Reset audio on error
        if (audioBooster != null) {
            audioBooster.resetAudio();
        }
        
        setState(ConversationState.ERROR);
        if (listener != null) {
            listener.onError(error);
        }
    }
    
    @Override
    public void onServerEvent(ServerEvents.ParsedEvent event) {
        Logger.d(TAG, "Server event: %s", event.type);

        if (event.isSessionCreated()) {
            handleSessionCreated(event);
        } else if (event.isSessionUpdated()) {
            Logger.d(TAG, "Session updated successfully");
            setState(ConversationState.READY);
        } else if (event.isSpeechStarted()) {
            setState(ConversationState.LISTENING);
        } else if (event.isSpeechStopped()) {
            setState(ConversationState.PROCESSING);
        } else if (event.isAudioDelta()) {
            // LOWEST LATENCY: Stream audio directly to LiveAvatar
            handleAudioDelta(event);
        } else if (event.isAudioDone()) {
            // Complete audio streaming to LiveAvatar
            handleAudioDone(event);
        } else if (event.isTranscriptDelta()) {
            handleTranscriptDelta(event);
        } else if (event.isTranscriptDone()) {
            handleTranscriptDone(event);
        } else if (event.isResponseDone()) {
            handleResponseDone(event);
        } else if (event.isFunctionCallArgumentsDone()) {
            handleFunctionCall(event);
        } else if (event.isError()) {
            handleServerError(event);
        }
    }

    /**
     * Handle audio delta from OpenAI - stream directly to LiveAvatar for lowest latency
     */
    private void handleAudioDelta(ServerEvents.ParsedEvent event) {
        String audioDelta = event.getAudioDelta();
        if (audioDelta == null || audioDelta.isEmpty()) {
            return;
        }

        // Debug: Log LiveAvatar state
        boolean connected = liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected();
        boolean useAudio = LiveAvatarConfig.useAudioStreaming();
        Logger.d(TAG, "AudioDelta: liveAvatarEnabled=%b, connected=%b, useAudioStreaming=%b",
                liveAvatarEnabled, connected, useAudio);

        // Stream audio delta to LiveAvatar if enabled and using audio streaming mode
        if (liveAvatarEnabled && liveAvatarSessionManager != null &&
            liveAvatarSessionManager.isConnected() && LiveAvatarConfig.useAudioStreaming()) {
            // Get the audio buffer and add the delta for streaming
            AudioDeltaBuffer audioBuffer = liveAvatarSessionManager.getAudioDeltaBuffer();
            if (audioBuffer != null) {
                audioBuffer.addAudioDelta(audioDelta);
                Logger.d(TAG, "Audio delta sent to LiveAvatar buffer");
            }
        }
    }

    /**
     * Handle audio done from OpenAI - complete the audio stream to LiveAvatar
     */
    private void handleAudioDone(ServerEvents.ParsedEvent event) {
        // Complete audio streaming to LiveAvatar
        if (liveAvatarEnabled && liveAvatarSessionManager != null &&
            liveAvatarSessionManager.isConnected() && LiveAvatarConfig.useAudioStreaming()) {
            AudioDeltaBuffer audioBuffer = liveAvatarSessionManager.getAudioDeltaBuffer();
            if (audioBuffer != null) {
                audioBuffer.complete();
            }
        }
    }
    
    @Override
    public void onSpeechStarted() {
        Logger.d(TAG, "Speech input started");
        setState(ConversationState.LISTENING);

        // Interrupt avatar when user starts speaking (barge-in)
        if (heyGenEnabled && heyGenSessionManager != null && heyGenSessionManager.isReady()) {
            heyGenSessionManager.interrupt();
        }
        if (liveAvatarEnabled && liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected()) {
            liveAvatarSessionManager.interrupt();
        }

        if (analytics != null) {
            analytics.recordUserMessage();
        }
    }
    
    @Override
    public void onSpeechStopped() {
        Logger.d(TAG, "Speech input stopped");
        setState(ConversationState.PROCESSING);
    }
    
    @Override
    public void onRemoteAudioTrack(AudioTrack track) {
        Logger.d(TAG, "Remote audio track received - AI is speaking");
        setState(ConversationState.SPEAKING);

        // When LiveAvatar is ENABLED (not just connected), mute OpenAI's WebRTC audio
        // LiveAvatar will play audio from its own TTS (perfect audio-visual sync)
        // IMPORTANT: Mute immediately when enabled, don't wait for connection
        // The LiveAvatar session connects asynchronously and audio track arrives before it's ready
        if (liveAvatarEnabled && liveAvatarSessionManager != null) {
            Logger.i(TAG, "Muting OpenAI audio - LiveAvatar will handle audio playback");
            webRTCManager.setRemoteAudioMuted(true);
            // Enable text buffer for lip sync
            liveAvatarSessionManager.enableTextBuffer();
        } else {
            // No avatar - let OpenAI audio play directly
            // Ensure audio is boosted when AI starts speaking
            if (audioBooster != null && !audioBooster.isConfigured()) {
                audioBooster.configureForMaxVolume();
                Logger.i(TAG, "Audio boosted for AI speech: %s", audioBooster.getVolumeInfo());
            }
        }
    }
    
    // ============================================
    // Event Handlers
    // ============================================
    
    private void setState(ConversationState newState) {
        if (currentState != newState) {
            Logger.d(TAG, "State: %s -> %s", currentState, newState);

            // Debug: Log stack trace for IDLE/ERROR transitions to understand cause
            if (newState == ConversationState.IDLE || newState == ConversationState.ERROR) {
                Logger.w(TAG, "*** STATE CHANGING TO %s *** Logging call stack:", newState);
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                for (int i = 2; i < Math.min(10, stackTrace.length); i++) {
                    Logger.w(TAG, "  at %s.%s(%s:%d)",
                        stackTrace[i].getClassName(),
                        stackTrace[i].getMethodName(),
                        stackTrace[i].getFileName(),
                        stackTrace[i].getLineNumber());
                }
            }

            currentState = newState;

            if (listener != null) {
                listener.onStateChanged(newState);
            }
        }
    }
    
    private void handleSessionCreated(ServerEvents.ParsedEvent event) {
        if (event.raw != null && event.raw.has("session")) {
            try {
                currentSessionId = event.raw.getAsJsonObject("session").get("id").getAsString();
                Logger.i(TAG, "Session created: %s", currentSessionId);
            } catch (Exception e) {
                Logger.e(e, "Failed to parse session ID");
            }
        }
        
        // Configure session after creation
        configureSession();
    }
    
    private void handleTranscriptDelta(ServerEvents.ParsedEvent event) {
        String delta = event.getTranscriptDelta();
        if (delta != null && !delta.isEmpty()) {
            currentTranscript.append(delta);

            if (listener != null) {
                listener.onTranscript(currentTranscript.toString(), false);
            }

            // Forward text delta to HeyGen avatar for lip-sync
            if (heyGenEnabled && heyGenSessionManager != null && heyGenSessionManager.isReady()) {
                heyGenSessionManager.addTextDelta(delta);
            }

            // Forward text delta to LiveAvatar for TTS and lip-sync
            // In WebRTC mode, audio deltas are not available, so we use text-based TTS
            if (liveAvatarEnabled && liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected()) {
                liveAvatarSessionManager.addTextDelta(delta);
            }
        }
    }
    
    private void handleTranscriptDone(ServerEvents.ParsedEvent event) {
        String transcript = event.getTranscript();
        if (transcript == null) {
            transcript = currentTranscript.toString();
        }
        
        aiTranscript.append("AI: ").append(transcript).append("\n");
        messageCount++;
        currentTranscript = new StringBuilder();
        
        if (listener != null) {
            listener.onTranscript(transcript, false);
        }
        
        if (analytics != null) {
            analytics.recordAiResponse();
        }
    }
    
    private void handleResponseDone(ServerEvents.ParsedEvent event) {
        Logger.d(TAG, "Response complete");

        // Flush any remaining text to HeyGen avatar
        if (heyGenEnabled && heyGenSessionManager != null) {
            heyGenSessionManager.flushBuffer();
        }

        // Complete any remaining audio/text to LiveAvatar
        if (liveAvatarEnabled && liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected()) {
            // Flush text buffer for TTS-based lip sync
            liveAvatarSessionManager.flushTextBuffer();

            // Also complete audio buffer if it was streaming
            AudioDeltaBuffer audioBuffer = liveAvatarSessionManager.getAudioDeltaBuffer();
            if (audioBuffer != null && audioBuffer.isStreaming()) {
                audioBuffer.complete();
            }
        }

        ServerEvents.ResponseInfo responseInfo = event.getResponseInfo();
        if (responseInfo != null && responseInfo.hasFunctionCall()) {
            handleFunctionCallFromResponse(event);
        }

        if (currentState == ConversationState.SPEAKING ||
            currentState == ConversationState.EXECUTING_FUNCTION) {
            setState(ConversationState.READY);
        }
    }
    
    private void handleFunctionCall(ServerEvents.ParsedEvent event) {
        ServerEvents.FunctionCallInfo funcInfo = event.getFunctionCallInfo();
        if (funcInfo == null) return;
        
        Logger.i(TAG, "Function call: %s (id: %s)", funcInfo.name, funcInfo.callId);
        
        // Don't change to EXECUTING_FUNCTION state for quick robot motion calls
        boolean isRobotMotion = funcInfo.name.startsWith("robot_");
        
        if (!isRobotMotion) {
            setState(ConversationState.EXECUTING_FUNCTION);
        }
        
        functionExecutor.executeFunctionCall(
            funcInfo.callId,
            funcInfo.name,
            funcInfo.arguments,
            currentSessionId,
            new FunctionExecutor.ExecutionCallback() {
                @Override
                public void onFunctionResult(String callId, String result) {
                    Logger.d(TAG, "Function %s succeeded", funcInfo.name);
                    webRTCManager.sendFunctionResult(callId, result);
                    
                    // Only update state for non-robot functions
                    if (!isRobotMotion && currentState == ConversationState.EXECUTING_FUNCTION) {
                        setState(ConversationState.READY);
                    }
                }
                
                @Override
                public void onFunctionError(String callId, String error) {
                    Logger.e(TAG, "Function %s failed: %s", funcInfo.name, error);
                    webRTCManager.sendFunctionResult(callId, "{\"error\": \"" + error + "\"}");
                    
                    if (!isRobotMotion && currentState == ConversationState.EXECUTING_FUNCTION) {
                        setState(ConversationState.READY);
                    }
                }
            }
        );
    }
    
    private void handleFunctionCallFromResponse(ServerEvents.ParsedEvent event) {
        functionExecutor.handleFunctionCall(event, currentSessionId, 
            new FunctionExecutor.ExecutionCallback() {
                @Override
                public void onFunctionResult(String callId, String result) {
                    webRTCManager.sendFunctionResult(callId, result);
                }
                
                @Override
                public void onFunctionError(String callId, String error) {
                    webRTCManager.sendFunctionResult(callId, "{\"error\": \"" + error + "\"}");
                }
            }
        );
    }
    
    private void handleServerError(ServerEvents.ParsedEvent event) {
        ServerEvents.ErrorInfo errorInfo = event.getErrorInfo();
        String errorMessage = errorInfo != null ? errorInfo.message : "Unknown error";
        
        Logger.e(TAG, "Server error: %s", errorMessage);
        
        if (analytics != null) {
            analytics.recordError("server: " + errorMessage);
        }
        
        if (listener != null) {
            listener.onError(errorMessage);
        }
    }
    
    private void logDisconnect(String reason) {
        long duration = getConversationDuration();
        String fullTranscript = userTranscript.toString() + aiTranscript.toString();
        
        Logger.d(TAG, "Logging disconnect - Session: %s, Duration: %dms, Messages: %d, Reason: %s",
            currentSessionId, duration, messageCount, reason);
    }
    
    // ============================================
    // Notification
    // ============================================
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Voice Agent",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Voice agent is active");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Agent Active")
            .setContentText("Tap to return to conversation")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}