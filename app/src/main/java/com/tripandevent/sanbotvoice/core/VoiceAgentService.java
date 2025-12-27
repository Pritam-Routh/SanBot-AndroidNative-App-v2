package com.tripandevent.sanbotvoice.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tripandevent.sanbotvoice.MainActivity;
import com.tripandevent.sanbotvoice.R;
import com.tripandevent.sanbotvoice.audio.AudioBooster;
import com.tripandevent.sanbotvoice.audio.AudioProcessor;
import com.tripandevent.sanbotvoice.audio.SpeakerIdentifier;
import com.tripandevent.sanbotvoice.analytics.ConversationAnalytics;
import com.tripandevent.sanbotvoice.functions.FunctionExecutor;
import com.tripandevent.sanbotvoice.openai.events.ServerEvents;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;
import com.tripandevent.sanbotvoice.webrtc.WebRTCManager;

import org.webrtc.AudioTrack;

/**
 * Foreground service that manages the voice conversation lifecycle.
 */
public class VoiceAgentService extends Service implements WebRTCManager.WebRTCCallback {
    
    private static final String TAG = "VoiceService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "voice_agent_channel";
    
    private final IBinder binder = new LocalBinder();
    
    // Core components
    private WebRTCManager webRTCManager;
    private FunctionExecutor functionExecutor;
    private AudioProcessor audioProcessor;
    private AudioBooster audioBooster;
    private SpeakerIdentifier speakerIdentifier;
    private ConversationAnalytics analytics;
    
    // State
    private ConversationState currentState = ConversationState.IDLE;
    private VoiceAgentListener listener;
    private String currentSessionId;
    private long conversationStartTime;
    private int messageCount;
    
    // Transcript accumulation
    private StringBuilder currentTranscript = new StringBuilder();
    private StringBuilder userTranscript = new StringBuilder();
    private StringBuilder aiTranscript = new StringBuilder();
    
    /**
     * Listener interface for UI updates
     */
    public interface VoiceAgentListener {
        void onStateChanged(ConversationState state);
        void onTranscript(String text, boolean isUser);
        void onError(String error);
        void onAudioLevel(float level);
        void onSpeakerIdentified(String speakerName, float confidence);
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
        
        // Initialize function executor
        functionExecutor = new FunctionExecutor();
        
        // Initialize audio booster
        audioBooster = new AudioBooster(this);
        
        // Initialize audio processor
        audioProcessor = new AudioProcessor(this);
        audioProcessor.setCallback(new AudioProcessor.AudioProcessorCallback() {
            @Override
            public void onSpeechStart() {
                Logger.d(TAG, "Local speech started");
                if (currentState == ConversationState.READY) {
                    setState(ConversationState.LISTENING);
                }
            }
            
            @Override
            public void onSpeechEnd() {
                Logger.d(TAG, "Local speech ended");
                if (currentState == ConversationState.LISTENING) {
                    setState(ConversationState.PROCESSING);
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
        
        createNotificationChannel();
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
        
        super.onDestroy();
    }
    
    public void setListener(VoiceAgentListener listener) {
        this.listener = listener;
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
        
        analytics.startSession();
        webRTCManager.connect();
    }
    
    public void stopConversation() {
        if (currentState == ConversationState.IDLE) {
            return;
        }
        
        Logger.i(TAG, "Stopping conversation...");
        
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
            Logger.d(TAG, "Audio reset after conversation");
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
    
    /**
     * Set audio boost level (0-100)
     */
    public void setAudioBoostLevel(int percent) {
        if (audioBooster != null) {
            audioBooster.setBoostLevel(percent);
        }
    }
    
    /**
     * Get current audio boost level
     */
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
        setState(ConversationState.READY);
        
        // Configure audio for maximum volume
        if (audioBooster != null) {
            audioBooster.configureForMaxVolume();
            Logger.i(TAG, "Audio configured: %s", audioBooster.getVolumeInfo());
        }
        
        if (audioProcessor != null) {
            audioProcessor.initialize(0);
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
            Logger.d(TAG, "Session updated");
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
        
        // Ensure audio is boosted when AI starts speaking
        if (audioBooster != null && !audioBooster.isConfigured()) {
            audioBooster.configureForMaxVolume();
            Logger.i(TAG, "Audio boosted for AI speech: %s", audioBooster.getVolumeInfo());
        }
    }
    
    // ============================================
    // Private helper methods
    // ============================================
    
    private void setState(ConversationState newState) {
        if (currentState != newState) {
            Logger.d(TAG, "State: %s -> %s", currentState, newState);
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
        setState(ConversationState.READY);
    }
    
    private void handleTranscriptDelta(ServerEvents.ParsedEvent event) {
        String delta = event.getTranscriptDelta();
        if (delta != null && !delta.isEmpty()) {
            currentTranscript.append(delta);
            
            if (listener != null) {
                listener.onTranscript(currentTranscript.toString(), false);
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
        
        ServerEvents.ResponseInfo responseInfo = event.getResponseInfo();
        if (responseInfo != null && responseInfo.hasFunctionCall()) {
            handleFunctionCallFromResponse(event);
        }
        
        if (currentState == ConversationState.SPEAKING) {
            setState(ConversationState.READY);
        }
    }
    
    private void handleFunctionCall(ServerEvents.ParsedEvent event) {
        ServerEvents.FunctionCallInfo funcInfo = event.getFunctionCallInfo();
        if (funcInfo == null) return;
        
        Logger.i(TAG, "Function call: %s (id: %s)", funcInfo.name, funcInfo.callId);
        
        setState(ConversationState.EXECUTING_FUNCTION);
        
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
                    setState(ConversationState.READY);
                }
                
                @Override
                public void onFunctionError(String callId, String error) {
                    Logger.e(TAG, "Function %s failed: %s", funcInfo.name, error);
                    webRTCManager.sendFunctionResult(callId, "{\"error\": \"" + error + "\"}");
                    setState(ConversationState.READY);
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
                    setState(ConversationState.READY);
                }
                
                @Override
                public void onFunctionError(String callId, String error) {
                    webRTCManager.sendFunctionResult(callId, "{\"error\": \"" + error + "\"}");
                    setState(ConversationState.READY);
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