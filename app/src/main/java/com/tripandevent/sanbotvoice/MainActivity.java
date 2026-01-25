package com.tripandevent.sanbotvoice;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.tripandevent.sanbotvoice.config.AgentConfig;
import com.tripandevent.sanbotvoice.core.ConversationState;
import com.tripandevent.sanbotvoice.core.VoiceAgentService;
import com.tripandevent.sanbotvoice.heygen.HeyGenConfig;
import com.tripandevent.sanbotvoice.heygen.HeyGenSessionManager;
import com.tripandevent.sanbotvoice.heygen.HeyGenVideoManager;
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarConfig;
import com.tripandevent.sanbotvoice.liveavatar.LiveAvatarSessionManager;
import com.tripandevent.sanbotvoice.ui.AvatarViewController;
import com.tripandevent.sanbotvoice.ui.views.VoiceOrbView;
import com.tripandevent.sanbotvoice.utils.Logger;

import io.livekit.android.renderer.SurfaceViewRenderer;

public class MainActivity extends AppCompatActivity implements VoiceAgentService.VoiceAgentListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    // UI components
    private ImageButton backButton;
    private TextView statusText;
    private TextView questionText;
    private TextView subtitleText;
    private TextView speakerText;
    private TextView statsText;
    private VoiceOrbView voiceOrb;

    // Avatar UI components
    private FrameLayout avatarContainer;
    private SurfaceViewRenderer avatarVideoView;
    private ProgressBar avatarLoading;
    private LinearLayout avatarErrorOverlay;
    private AvatarViewController avatarViewController;

    // Service connection
    private VoiceAgentService voiceService;
    private boolean isServiceBound = false;

    // Conversation state
    private boolean isConversationActive = false;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VoiceAgentService.LocalBinder binder = (VoiceAgentService.LocalBinder) service;
            voiceService = binder.getService();
            voiceService.setListener(MainActivity.this);

            // Configure the agent from client side
            configureAgent();

            isServiceBound = true;
            Logger.d(TAG, "Service connected");
            updateUIForState(voiceService.getCurrentState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            voiceService = null;
            isServiceBound = false;
            Logger.d(TAG, "Service disconnected");
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make status bar transparent and set light icons for dark background
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );

        // Keep screen on during the entire activity lifecycle
        // This prevents screen from sleeping during conversations
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        setContentView(R.layout.activity_main);

        Logger.i(TAG, "MainActivity created");

        initializeViews();
        setupClickListeners();

        if (checkPermissions()) {
            startVoiceService();
        } else {
            requestPermissions();
        }
    }
    
    private void initializeViews() {
        backButton = findViewById(R.id.backButton);
        statusText = findViewById(R.id.statusText);
        questionText = findViewById(R.id.questionText);
        subtitleText = findViewById(R.id.subtitleText);
        speakerText = findViewById(R.id.speakerText);
        statsText = findViewById(R.id.statsText);
        voiceOrb = findViewById(R.id.voiceOrb);

        statusText.setText("Ready");
        questionText.setText("Hi there, Welcome to Trip & Event!");

        if (speakerText != null) {
            speakerText.setVisibility(View.GONE);
        }

        if (statsText != null) {
            statsText.setVisibility(View.GONE);
        }

        // Initialize avatar UI components if any avatar provider is enabled
        if (HeyGenConfig.isEnabled() || LiveAvatarConfig.isEnabled()) {
            initializeAvatarViews();
        }
    }

    private void initializeAvatarViews() {
        avatarContainer = findViewById(R.id.avatarContainer);
        avatarVideoView = findViewById(R.id.avatarVideoView);
        avatarLoading = findViewById(R.id.avatarLoading);
        avatarErrorOverlay = findViewById(R.id.avatarErrorOverlay);

        if (avatarContainer != null && avatarVideoView != null && avatarLoading != null) {
            avatarViewController = new AvatarViewController(
                    avatarContainer,
                    avatarVideoView,
                    avatarLoading,
                    avatarErrorOverlay
            );
            Logger.d(TAG, "Avatar views initialized");
        }
    }
    
    private void setupClickListeners() {
        // Voice orb click toggles conversation
        voiceOrb.setOnOrbClickListener(() -> {
            if (isServiceBound && voiceService != null) {
                if (isConversationActive) {
                    voiceService.stopConversation();
                } else {
                    voiceService.startConversation();
                }
            }
        });

        // Back button
        backButton.setOnClickListener(v -> {
            if (isConversationActive && isServiceBound && voiceService != null) {
                voiceService.stopConversation();
            }
            finish();
        });
    }
    
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            new String[]{Manifest.permission.RECORD_AUDIO},
            PERMISSION_REQUEST_CODE
        );
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Logger.i(TAG, "All permissions granted");
                startVoiceService();
            } else {
                Logger.w(TAG, "Permissions denied");
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show();
                statusText.setText("Permission denied");
                questionText.setText("Microphone permission is required to use voice features");
            }
        }
    }
    
    private void startVoiceService() {
        Intent serviceIntent = new Intent(this, VoiceAgentService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Configure the voice agent from the client side.
     * This is where you customize the agent's personality, instructions, and behavior.
     *
     * You can either:
     * 1. Use AgentConfig.Builder to build configuration from parameters
     * 2. Set custom system instructions directly
     *
     * Call this method after the service is connected but before starting a conversation.
     */
    private void configureAgent() {
        if (voiceService == null) return;

        // ============================================
        // OPTION 1: Use AgentConfig Builder (Recommended)
        // ============================================
        AgentConfig config = new AgentConfig.Builder()
            // Agent identity
            .setAgentName("Tara")
            .setCompanyName("Trip & Event")

            // Personality (CHEERFUL, PROFESSIONAL, FRIENDLY, CALM)
            .setPersonality(AgentConfig.Personality.CHEERFUL)

            // Voice (alloy, ash, ballad, coral, echo, sage, shimmer, verse, marin, cedar)
            .setVoice("alloy")

            // Sales behavior flags
            .setEagerToShowPackages(true)   // Proactively show packages
            .setCollectLeadsEarly(true)     // Ask for contact info early
            .setUseUpselling(true)          // Suggest upgrades

            // Temperature (0.0 = focused, 1.0 = creative)
            .setTemperature(0.8)

            // Optional: Set custom company description
            .setCompanyDescription(
                "Trip & Event is India's premier travel booking platform, " +
                "specializing in customized domestic and international tour packages. " +
                "We offer honeymoon packages, family vacations, corporate trips, adventure tours, " +
                "pilgrimage journeys, and weekend getaways. " +
                "Popular destinations: Goa, Kerala, Rajasthan, Kashmir, Bali, Thailand, Dubai, Singapore, Maldives. " +
                "Our USP: Personalized itineraries, best price guarantee, 24/7 support, hassle-free booking."
            )

            .build();

        voiceService.configure(config);
        Logger.i(TAG, "Agent configured: %s for %s", config.getAgentName(), config.getCompanyName());

        // ============================================
        // OPTION 2: Set custom instructions directly (Advanced)
        // ============================================
        // Uncomment below to use fully custom instructions instead:
        /*
        String customInstructions =
            "You are a helpful AI assistant. " +
            "Your task is to help customers with their travel inquiries. " +
            "Be friendly, professional, and always try to help.";

        voiceService.setAiInstructions(customInstructions);
        */
    }

    // ============================================
    // VoiceAgentService.VoiceAgentListener
    // ============================================
    
    @Override
    public void onStateChanged(ConversationState state) {
        runOnUiThread(() -> {
            Logger.d(TAG, "State changed: %s", state);
            updateUIForState(state);
        });
    }
    
    @Override
    public void onTranscript(String text, boolean isUser) {
        runOnUiThread(() -> {
            // Update the question text with the last transcript
            if (isUser) {
                questionText.setText(text);
            }
        });
    }
    
    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            Logger.e(TAG, "Error: %s", error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            statusText.setText("Error: " + error);
        });
    }
    
    @Override
    public void onAudioLevel(float level) {
        runOnUiThread(() -> {
            if (voiceOrb != null) {
                voiceOrb.setAudioLevel(level);
            }
        });
    }
    
    @Override
    public void onSpeakerIdentified(String speakerName, float confidence) {
        runOnUiThread(() -> {
            if (speakerText != null) {
                speakerText.setVisibility(View.VISIBLE);
                speakerText.setText(String.format("Speaker: %s (%.0f%%)", speakerName, confidence * 100));
            }
        });
    }

    @Override
    public void onAvatarVideoReady() {
        runOnUiThread(() -> {
            Logger.i(TAG, "*** AVATAR VIDEO READY *** isConversationActive=%b", isConversationActive);
            if (voiceService != null) {
                Logger.i(TAG, "Current conversation state: %s", voiceService.getCurrentState());
            }

            if (avatarViewController != null && voiceService != null) {
                // Check which avatar provider is active and bind accordingly

                // Try LiveAvatar first (if enabled, it takes priority)
                if (voiceService.isLiveAvatarEnabled()) {
                    LiveAvatarSessionManager liveAvatarManager = voiceService.getLiveAvatarSessionManager();
                    if (liveAvatarManager != null) {
                        Logger.d(TAG, "LiveAvatar session state: %s", liveAvatarManager.getState());
                        avatarViewController.bindLiveAvatarManager(liveAvatarManager);
                        avatarViewController.showVideo();
                        Logger.i(TAG, "LiveAvatar video shown successfully");
                        return;
                    } else {
                        Logger.e(TAG, "LiveAvatarSessionManager is null!");
                    }
                }

                // Fall back to HeyGen if enabled
                if (voiceService.isHeyGenEnabled()) {
                    HeyGenSessionManager sessionManager = voiceService.getHeyGenSessionManager();
                    if (sessionManager != null) {
                        Logger.d(TAG, "HeyGen session state: %s", sessionManager.getState());
                        HeyGenVideoManager videoManager = sessionManager.getVideoManager();
                        if (videoManager != null) {
                            Logger.d(TAG, "VideoManager connected: %s, hasTrack: %s",
                                    videoManager.isConnected(), videoManager.hasVideoTrack());
                            avatarViewController.bindVideoManager(videoManager);
                            avatarViewController.showVideo();
                            Logger.i(TAG, "HeyGen avatar video shown successfully");
                        } else {
                            Logger.e(TAG, "HeyGen VideoManager is null!");
                        }
                    } else {
                        Logger.e(TAG, "HeyGen SessionManager is null!");
                    }
                }
            }
        });
    }

    @Override
    public void onAvatarError(String error) {
        runOnUiThread(() -> {
            Logger.e(TAG, "Avatar error: %s", error);
            if (avatarViewController != null) {
                avatarViewController.showError();
            }
            // Optionally show a toast, but don't disrupt the conversation
            // Toast.makeText(this, "Avatar unavailable: " + error, Toast.LENGTH_SHORT).show();
        });
    }
    
    // ============================================
    // UI update helpers
    // ============================================
    
    private void updateUIForState(ConversationState state) {
        // Update avatar visibility based on state
        updateAvatarForState(state);

        switch (state) {
            case IDLE:
                statusText.setText("Ready");
                questionText.setText("Hi there, welcome to Trip and Event.");
                isConversationActive = false;
                if (voiceOrb != null) {
                    voiceOrb.setListening(false);
                    voiceOrb.setSpeaking(false);
                    voiceOrb.setActive(false);
                }
                break;

            case CONNECTING:
            case CONFIGURING:
                statusText.setText("Connecting...");
                isConversationActive = true;
                if (voiceOrb != null) {
                    voiceOrb.setActive(true);
                    voiceOrb.setListening(false);
                    voiceOrb.setSpeaking(false);
                }
                break;

            case READY:
                statusText.setText("Speak now");
                isConversationActive = true;
                if (voiceOrb != null) {
                    voiceOrb.setListening(true);
                    voiceOrb.setSpeaking(false);
                }
                break;

            case LISTENING:
                statusText.setText("Listening...");
                isConversationActive = true;
                if (voiceOrb != null) {
                    voiceOrb.setListening(true);
                    voiceOrb.setSpeaking(false);
                }
                break;

            case PROCESSING:
            case EXECUTING_FUNCTION:
                statusText.setText("Processing...");
                isConversationActive = true;
                if (voiceOrb != null) {
                    voiceOrb.setListening(false);
                    voiceOrb.setSpeaking(false);
                    voiceOrb.setActive(true);
                }
                break;

            case SPEAKING:
                statusText.setText("Speaking...");
                isConversationActive = true;
                if (voiceOrb != null) {
                    voiceOrb.setListening(false);
                    voiceOrb.setSpeaking(true);
                }
                break;

            case DISCONNECTING:
                statusText.setText("Ending...");
                isConversationActive = false;
                if (voiceOrb != null) {
                    voiceOrb.setActive(false);
                }
                break;

            case ERROR:
                statusText.setText("Tap to retry");
                questionText.setText("Something went wrong. Please try again.");
                isConversationActive = false;
                if (voiceOrb != null) {
                    voiceOrb.setListening(false);
                    voiceOrb.setSpeaking(false);
                    voiceOrb.setActive(false);
                }
                break;
        }
    }

    /**
     * Update avatar UI based on conversation state
     */
    private void updateAvatarForState(ConversationState state) {
        if (avatarViewController == null) return;

        Logger.d(TAG, "updateAvatarForState: %s, avatarVisible=%b, avatarVideoReady=%b",
                state, avatarViewController.isVisible(), avatarViewController.isVideoReady());

        switch (state) {
            case CONNECTING:
            case CONFIGURING:
                // Show avatar loading when connecting (for any avatar provider)
                if (voiceService != null && voiceService.isAnyAvatarEnabled()) {
                    avatarViewController.showLoading();
                }
                break;

            case IDLE:
            case DISCONNECTING:
            case ERROR:
                // Only hide avatar if no avatar session is active
                // This prevents premature hiding while video is still playing
                if (voiceService != null) {
                    // Check LiveAvatar first (takes priority)
                    if (voiceService.isLiveAvatarEnabled()) {
                        LiveAvatarSessionManager liveAvatarManager = voiceService.getLiveAvatarSessionManager();
                        if (liveAvatarManager != null && liveAvatarManager.isActive()) {
                            Logger.d(TAG, "Keeping avatar visible - LiveAvatar session still active");
                            break;
                        }
                    }

                    // Check HeyGen
                    if (voiceService.isHeyGenEnabled()) {
                        HeyGenSessionManager sessionManager = voiceService.getHeyGenSessionManager();
                        if (sessionManager != null && sessionManager.isActive()) {
                            Logger.d(TAG, "Keeping avatar visible - HeyGen session still active");
                            break;
                        }
                    }
                }
                Logger.d(TAG, "Hiding avatar - conversation state: %s", state);
                avatarViewController.hide();
                break;

            // For other states (READY, LISTENING, PROCESSING, SPEAKING, EXECUTING_FUNCTION)
            // the avatar remains visible if already showing
            default:
                break;
        }
    }

    @Override
    protected void onDestroy() {
        // Release avatar resources before calling super
        if (avatarViewController != null) {
            avatarViewController.release();
        }

        super.onDestroy();

        if (isServiceBound) {
            if (voiceService != null) {
                voiceService.setListener(null);
            }
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }

}