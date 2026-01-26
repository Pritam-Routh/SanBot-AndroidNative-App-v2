package com.tripandevent.sanbotvoice.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
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
import com.tripandevent.sanbotvoice.orchestration.OrchestratedSessionManager;

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

    // Standalone mode flag (HeyGen FULL mode, no OpenAI WebRTC)
    private boolean isStandaloneMode = false;

    // Orchestrated mode (Single LiveKit room: User + OpenAI Agent + HeyGen Avatar)
    private OrchestratedSessionManager orchestratedSessionManager;
    private boolean isOrchestratedMode = false;

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

    // Standalone mode: echo loop prevention and debounced speak_response
    private boolean avatarIsSpeaking = false;
    private String pendingTranscription = null;
    private final Runnable speakResponseRunnable = () -> {
        String text = pendingTranscription;
        pendingTranscription = null;
        if (text != null && !text.isEmpty() && isStandaloneMode
                && liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected()) {
            Logger.i(TAG, "Sending debounced speak_response: %s", text);
            liveAvatarSessionManager.speakResponse(text);
        }
    };
    private static final long SPEAK_RESPONSE_DEBOUNCE_MS = 800;

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

        // Determine mode first (before any initialization)
        // Priority: Orchestrated > Standalone > Hybrid (legacy)
        isOrchestratedMode = LiveAvatarConfig.isOrchestratedMode();
        isStandaloneMode = !isOrchestratedMode && LiveAvatarConfig.isStandaloneMode();
        Logger.i(TAG, "Mode: orchestrated=%b, standalone=%b", isOrchestratedMode, isStandaloneMode);

        // Initialize WebRTC manager only in hybrid/legacy mode
        // Orchestrated mode: Agent runs server-side, no local WebRTC needed
        // Standalone mode: HeyGen handles everything, no OpenAI WebRTC needed
        if (!isOrchestratedMode && !isStandaloneMode) {
            Logger.i(TAG, "Hybrid mode - initializing WebRTCManager");
            webRTCManager = new WebRTCManager(this, this);
            webRTCManager.initialize();
        } else {
            Logger.i(TAG, "WebRTC DISABLED (orchestrated=%b, standalone=%b)", isOrchestratedMode, isStandaloneMode);
            webRTCManager = null;
        }
        
        // Initialize function executor with context for robot motion
        functionExecutor = new FunctionExecutor();
        functionExecutor.setContext(this);
        
        // Initialize audio booster
        audioBooster = new AudioBooster(this);
        
        // Initialize Sanbot motion manager
        sanbotMotionManager = SanbotMotionManager.getInstance(this);
        
        // Initialize audio processor
        audioProcessor = new AudioProcessor(this);

        // Only wire local speech detection when OpenAI WebRTC is active (hybrid mode).
        // In standalone mode, HeyGen VAD events drive state transitions.
        // In orchestrated mode, Agent handles STT server-side.
        if (!isStandaloneMode && !isOrchestratedMode) {
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
        }
        
        // Initialize speaker identifier
        speakerIdentifier = new SpeakerIdentifier(this);
        
        // Initialize analytics
        analytics = new ConversationAnalytics(this);

        if (isOrchestratedMode) {
            // Orchestrated mode: OrchestratedSessionManager handles everything
            // No HeyGen or LiveAvatar local sessions needed
            initializeOrchestrated();
        } else {
            // Initialize HeyGen if enabled
            initializeHeyGen();

            // Initialize LiveAvatar if enabled (takes precedence over HeyGen if both enabled)
            initializeLiveAvatar();
        }

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
            }

            @Override
            public void onStreamReady() {
                Logger.i(TAG, "LiveAvatar stream ready");

                if (isStandaloneMode) {
                    // In standalone mode, stream ready means we're ready for conversation
                    // HeyGen handles everything from here
                    Logger.i(TAG, "Standalone mode: Stream ready - transitioning to READY state");
                    setState(ConversationState.READY);
                } else {
                    // Legacy mode: just enable text buffer for lip sync
                    liveAvatarSessionManager.enableTextBuffer();
                }

                // Force reconfigure audio after LiveKit connects (legacy mode only)
                // In standalone mode, LiveKit owns the audio session for mic capture
                // DO NOT steal audio focus or it will kill LiveKit's microphone
                if (audioBooster != null && !isStandaloneMode) {
                    audioBooster.forceReconfigure();
                    Logger.i(TAG, "Audio FORCE reconfigured after LiveKit: %s", audioBooster.getVolumeInfo());
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
                Logger.i(TAG, "LiveAvatar user transcription: %s", text);
                if (isStandaloneMode) {
                    // Ignore transcriptions while avatar is speaking (echo prevention)
                    if (avatarIsSpeaking) {
                        Logger.d(TAG, "Ignoring transcription while avatar is speaking (echo): %s", text);
                        return;
                    }
                    // Display user transcript in UI
                    userTranscript.append("User: ").append(text).append("\n");
                    messageCount++;
                    if (listener != null) {
                        listener.onTranscript(text, true);
                    }
                    if (analytics != null) {
                        analytics.recordUserMessage();
                    }
                    // Debounced speak_response: accumulate transcription text,
                    // send ONE speak_response after 800ms of no new transcriptions.
                    pendingTranscription = text;
                    mainHandler.removeCallbacks(speakResponseRunnable);
                    mainHandler.postDelayed(speakResponseRunnable, SPEAK_RESPONSE_DEBOUNCE_MS);
                }
            }

            @Override
            public void onAvatarTranscription(String text) {
                Logger.d(TAG, "LiveAvatar avatar transcription: %s", text);
                if (isStandaloneMode) {
                    // Display AI transcript in UI
                    aiTranscript.append("AI: ").append(text).append("\n");
                    if (listener != null) {
                        listener.onTranscript(text, false);
                    }
                    if (analytics != null) {
                        analytics.recordAiResponse();
                    }
                }
            }

            @Override
            public void onAvatarSpeakStarted() {
                Logger.i(TAG, "LiveAvatar speak started");
                if (isStandaloneMode) {
                    avatarIsSpeaking = true;
                    setState(ConversationState.SPEAKING);
                    // Cancel any pending speak_response to prevent echo
                    mainHandler.removeCallbacks(speakResponseRunnable);
                    pendingTranscription = null;
                    // Stop listening while avatar speaks to prevent echo loop
                    // (mic picks up avatar audio → re-transcribed → triggers new response)
                    if (liveAvatarSessionManager != null) {
                        liveAvatarSessionManager.stopListening();
                    }
                }
            }

            @Override
            public void onAvatarSpeakEnded() {
                Logger.i(TAG, "LiveAvatar speak ended");
                if (isStandaloneMode) {
                    avatarIsSpeaking = false;
                    setState(ConversationState.READY);
                    // Resume listening now that avatar finished speaking
                    if (liveAvatarSessionManager != null) {
                        liveAvatarSessionManager.startListening();
                    }
                }
            }

            @Override
            public void onUserSpeakStarted() {
                Logger.d(TAG, "LiveAvatar user speak started (HeyGen VAD)");
                if (isStandaloneMode) {
                    setState(ConversationState.LISTENING);
                    // Do NOT send avatar.interrupt here - HeyGen FULL mode handles
                    // barge-in internally. Sending interrupt disrupts the processing pipeline.
                    // Show listening gesture
                    if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
                        sanbotMotionManager.showListening();
                    }
                }
            }

            @Override
            public void onUserSpeakEnded() {
                Logger.d(TAG, "LiveAvatar user speak ended (HeyGen VAD)");
                if (isStandaloneMode) {
                    setState(ConversationState.PROCESSING);
                    // Show thinking gesture while processing
                    if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
                        sanbotMotionManager.showThinking();
                    }
                }
            }

            @Override
            public void onError(String error) {
                Logger.e(TAG, "LiveAvatar error: %s", error);
                liveAvatarEnabled = false;

                if (!isStandaloneMode && webRTCManager != null) {
                    // Legacy mode: unmute OpenAI audio since avatar failed
                    Logger.i(TAG, "Unmuting OpenAI audio - LiveAvatar failed, falling back to direct audio");
                    webRTCManager.setRemoteAudioMuted(false);
                }

                if (isStandaloneMode) {
                    // Standalone mode: LiveAvatar failure is critical - set error state
                    setState(ConversationState.ERROR);
                }

                if (listener != null) {
                    listener.onAvatarError(error);
                }
            }
        });

        if (isStandaloneMode) {
            Logger.i(TAG, "LiveAvatar support initialized (STANDALONE FULL mode - no OpenAI)");
        } else {
            Logger.i(TAG, "LiveAvatar support initialized (text-based TTS lip-sync mode)");
        }
    }

    /**
     * Initialize Orchestrated mode (Single LiveKit room: User + Agent + HeyGen)
     *
     * In this mode, the OrchestratedSessionManager handles the entire session:
     * - Connects to a single LiveKit room alongside OpenAI Agent and HeyGen Avatar
     * - Agent handles STT+LLM+TTS server-side via LiveKit Agents framework
     * - HeyGen provides lip-synced video via BYOLI (Bring Your Own LiveKit Instance)
     * - Robot commands arrive via LiveKit data channel from the Agent
     * - No local WebRTCManager, AudioProcessor, or text delta batching needed
     */
    private void initializeOrchestrated() {
        Logger.i(TAG, "Initializing Orchestrated LiveKit mode");

        orchestratedSessionManager = new OrchestratedSessionManager(this);
        orchestratedSessionManager.setListener(new OrchestratedSessionManager.SessionListener() {
            @Override
            public void onStateChanged(OrchestratedSessionManager.SessionState state) {
                Logger.d(TAG, "Orchestrated state: %s", state);
                // Map orchestrated states to conversation states
                switch (state) {
                    case CONNECTING:
                        setState(ConversationState.CONNECTING);
                        break;
                    case CONNECTED:
                        setState(ConversationState.CONFIGURING);
                        break;
                    case STREAM_READY:
                        setState(ConversationState.READY);
                        break;
                    case ERROR:
                        setState(ConversationState.ERROR);
                        break;
                    case IDLE:
                        if (currentState != ConversationState.IDLE) {
                            setState(ConversationState.IDLE);
                        }
                        break;
                }
            }

            @Override
            public void onStreamReady() {
                Logger.i(TAG, "Orchestrated stream ready");
                if (listener != null) {
                    listener.onAvatarVideoReady();
                }
            }

            @Override
            public void onAgentSpeakStarted() {
                Logger.d(TAG, "Agent started speaking (orchestrated)");
                setState(ConversationState.SPEAKING);
                // Note: Agent sends explicit robot_gesture/robot_emotion commands via data channel
            }

            @Override
            public void onAgentSpeakEnded() {
                Logger.d(TAG, "Agent stopped speaking (orchestrated)");
                if (currentState == ConversationState.SPEAKING) {
                    setState(ConversationState.READY);
                }
            }

            @Override
            public void onUserTranscript(String text) {
                Logger.d(TAG, "User transcript (orchestrated): %s", text);
                userTranscript.append("User: ").append(text).append("\n");
                messageCount++;
                if (listener != null) {
                    listener.onTranscript(text, true);
                }
                if (analytics != null) {
                    analytics.recordUserMessage();
                }
            }

            @Override
            public void onAiTranscript(String text) {
                Logger.d(TAG, "AI transcript (orchestrated): %s", text);
                aiTranscript.append("AI: ").append(text).append("\n");
                messageCount++;
                if (listener != null) {
                    listener.onTranscript(text, false);
                }
                if (analytics != null) {
                    analytics.recordAiResponse();
                }
            }

            @Override
            public void onRobotCommand(String command, String arguments) {
                Logger.i(TAG, "Robot command from Agent: %s (%s)", command, arguments);
                // Execute robot commands locally via FunctionExecutor
                // The Agent already handled CRM functions server-side;
                // only robot_* commands are forwarded via data channel
                if (functionExecutor != null) {
                    functionExecutor.executeFunctionCall(
                        "orchestrated-" + System.currentTimeMillis(),
                        command,
                        arguments,
                        null,
                        new FunctionExecutor.ExecutionCallback() {
                            @Override
                            public void onFunctionResult(String callId, String result) {
                                Logger.d(TAG, "Robot command executed: %s -> %s", command, result);
                            }

                            @Override
                            public void onFunctionError(String callId, String error) {
                                Logger.w(TAG, "Robot command failed: %s -> %s", command, error);
                            }
                        }
                    );
                }
            }

            @Override
            public void onError(String error) {
                Logger.e(TAG, "Orchestrated session error: %s", error);
                if (listener != null) {
                    listener.onError(error);
                }
            }
        });

        Logger.i(TAG, "Orchestrated session manager initialized");
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

        // Cleanup Orchestrated session
        if (orchestratedSessionManager != null) {
            orchestratedSessionManager.destroy();
            orchestratedSessionManager = null;
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
     * Get the Orchestrated session manager for UI binding (video renderer)
     */
    public OrchestratedSessionManager getOrchestratedSessionManager() {
        return orchestratedSessionManager;
    }

    /**
     * Check if orchestrated mode is active
     */
    public boolean isOrchestratedMode() {
        return isOrchestratedMode && orchestratedSessionManager != null;
    }

    /**
     * Check if any avatar (HeyGen, LiveAvatar, or Orchestrated) is enabled
     */
    public boolean isAnyAvatarEnabled() {
        return isHeyGenEnabled() || isLiveAvatarEnabled() || isOrchestratedMode();
    }

    public void startConversation() {
        if (currentState != ConversationState.IDLE && currentState != ConversationState.ERROR) {
            Logger.w(TAG, "Cannot start conversation in state: %s", currentState);
            return;
        }

        Logger.i(TAG, "Starting conversation... (orchestrated=%b, standalone=%b)", isOrchestratedMode, isStandaloneMode);
        setState(ConversationState.CONNECTING);

        currentTranscript = new StringBuilder();
        userTranscript = new StringBuilder();
        aiTranscript = new StringBuilder();
        conversationStartTime = System.currentTimeMillis();
        messageCount = 0;
        sessionConfigured = false;

        analytics.startSession();

        if (isOrchestratedMode) {
            // ORCHESTRATED MODE: Single LiveKit room with Agent + HeyGen
            // Agent handles STT+LLM+TTS server-side, HeyGen provides lip-sync video
            startOrchestratedSession();
        } else if (isStandaloneMode) {
            // STANDALONE MODE: Only start LiveAvatar session
            // HeyGen handles everything: mic capture via LiveKit, STT, LLM, TTS, video
            startStandaloneSession();
        } else {
            // HYBRID MODE: Connect to OpenAI first, then start LiveAvatar in parallel
            webRTCManager.connect();
        }
    }

    /**
     * Start conversation in standalone HeyGen FULL mode.
     * No OpenAI WebRTC connection needed - HeyGen handles the entire pipeline.
     */
    private void startStandaloneSession() {
        Logger.i(TAG, "Starting standalone HeyGen FULL session...");

        // DO NOT use AudioBooster in standalone mode!
        // LiveKit manages its own audio session for mic capture.
        // AudioBooster.requestAudioFocus() steals focus from LiveKit, killing mic input.
        // Just maximize volume streams without requesting audio focus or changing audio mode.
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            Logger.i(TAG, "Standalone mode: Volume maximized (no audio focus request)");
        }

        // Start LiveAvatar session
        if (liveAvatarEnabled && liveAvatarSessionManager != null) {
            Logger.d(TAG, "Starting LiveAvatar session in standalone FULL mode...");
            liveAvatarSessionManager.startSession(LiveAvatarConfig.getAvatarId());
        } else {
            Logger.e(TAG, "Cannot start standalone session - LiveAvatar not enabled");
            setState(ConversationState.ERROR);
            if (listener != null) {
                listener.onError("LiveAvatar is not enabled for standalone mode");
            }
        }

        // Perform greeting gesture
        if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
            mainHandler.postDelayed(() -> {
                sanbotMotionManager.greet();
            }, 500);
        }
    }
    
    /**
     * Start conversation in orchestrated mode.
     * Single LiveKit room with OpenAI Agent (STT+LLM+TTS) + HeyGen Avatar (lip-sync video).
     * Android app just publishes mic and subscribes to audio + video.
     */
    private void startOrchestratedSession() {
        Logger.i(TAG, "Starting orchestrated LiveKit session...");

        // Maximize volume without requesting audio focus (LiveKit manages audio session)
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            Logger.i(TAG, "Orchestrated mode: Volume maximized (no audio focus request)");
        }

        // Start orchestrated session via backend
        if (orchestratedSessionManager != null) {
            orchestratedSessionManager.startSession(LiveAvatarConfig.getAvatarId());
        } else {
            Logger.e(TAG, "Cannot start orchestrated session - manager not initialized");
            setState(ConversationState.ERROR);
            if (listener != null) {
                listener.onError("Orchestrated session manager not initialized");
            }
        }

        // Greeting gesture
        if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
            mainHandler.postDelayed(() -> {
                sanbotMotionManager.greet();
            }, 500);
        }
    }

    public void stopConversation() {
        if (currentState == ConversationState.IDLE) {
            return;
        }

        Logger.i(TAG, "Stopping conversation...");

        // Cancel any pending speak_response
        mainHandler.removeCallbacks(speakResponseRunnable);
        pendingTranscription = null;

        // Stop HeyGen avatar session
        if (heyGenSessionManager != null) {
            heyGenSessionManager.stopSession();
        }

        // Stop LiveAvatar session
        if (liveAvatarSessionManager != null) {
            liveAvatarSessionManager.stopSession();
        }

        // Stop Orchestrated session
        if (orchestratedSessionManager != null && isOrchestratedMode) {
            orchestratedSessionManager.stopSession();
        }

        // Say goodbye gesture
        if (sanbotMotionManager != null && sanbotMotionManager.isAvailable()) {
            sanbotMotionManager.sayGoodbye();
        }

        ConversationAnalytics.SessionSummary summary = analytics.endSession();
        if (summary != null) {
            Logger.i(TAG, "Session summary: %s", summary.toString());
        }

        if (webRTCManager != null) {
            webRTCManager.disconnect();
        }
        
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

        // OpenAI audio plays directly to speaker (LiveAvatar audio is muted for lip sync only)
        // ALWAYS force reconfigure when AI starts speaking (LiveKit may have overridden settings)
        if (audioBooster != null) {
            if (liveAvatarEnabled) {
                // Force reconfigure because LiveKit may have changed audio settings
                audioBooster.forceReconfigure();
                Logger.i(TAG, "Audio FORCE reconfigured for AI speech (LiveAvatar active): %s", audioBooster.getVolumeInfo());
            } else if (!audioBooster.isConfigured()) {
                audioBooster.configureForMaxVolume();
                Logger.i(TAG, "Audio boosted for AI speech: %s", audioBooster.getVolumeInfo());
            }
        }

        // Enable text buffer for lip sync if LiveAvatar is active
        if (liveAvatarEnabled && liveAvatarSessionManager != null) {
            liveAvatarSessionManager.enableTextBuffer();
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
        if (isStandaloneMode) {
            // In standalone mode, HeyGen handles its own text/speech
            return;
        }
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

        // Complete any remaining text to LiveAvatar
        if (liveAvatarEnabled && liveAvatarSessionManager != null && liveAvatarSessionManager.isConnected()) {
            // Flush text buffer for TTS-based lip sync
            liveAvatarSessionManager.flushTextBuffer();
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
        if (isStandaloneMode) {
            Logger.w(TAG, "Function calling not supported in standalone HeyGen mode");
            return;
        }
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
        if (isStandaloneMode || webRTCManager == null) {
            Logger.w(TAG, "Function calling not supported in standalone HeyGen mode");
            return;
        }
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