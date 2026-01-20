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
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.tripandevent.sanbotvoice.core.ConversationState;
import com.tripandevent.sanbotvoice.core.VoiceAgentService;
import com.tripandevent.sanbotvoice.ui.views.VoiceOrbView;
import com.tripandevent.sanbotvoice.utils.Logger;

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
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (isServiceBound) {
            if (voiceService != null) {
                voiceService.setListener(null);
            }
            unbindService(serviceConnection);
            isServiceBound = false;
        }
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
    
    // ============================================
    // UI update helpers
    // ============================================
    
    private void updateUIForState(ConversationState state) {
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
    
}