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
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tripandevent.sanbotvoice.analytics.ConversationAnalytics;
import com.tripandevent.sanbotvoice.audio.AudioBooster;
import com.tripandevent.sanbotvoice.core.ConversationState;
import com.tripandevent.sanbotvoice.core.VoiceAgentService;
import com.tripandevent.sanbotvoice.ui.views.AudioWaveformView;
import com.tripandevent.sanbotvoice.ui.views.VoiceAnimationView;
import com.tripandevent.sanbotvoice.utils.Logger;

public class MainActivity extends AppCompatActivity implements VoiceAgentService.VoiceAgentListener {
    
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // UI components
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView transcriptText;
    private TextView speakerText;
    private TextView statsText;
    private TextView volumeText;
    private ScrollView transcriptScroll;
    private VoiceAnimationView voiceAnimation;
    private AudioWaveformView waveformView;
    private SeekBar volumeBoostSeekBar;
    
    // Service connection
    private VoiceAgentService voiceService;
    private boolean isServiceBound = false;
    
    // Transcript
    private StringBuilder transcriptBuilder = new StringBuilder();
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VoiceAgentService.LocalBinder binder = (VoiceAgentService.LocalBinder) service;
            voiceService = binder.getService();
            voiceService.setListener(MainActivity.this);
            isServiceBound = true;
            Logger.d(TAG, "Service connected");
            updateUIForState(voiceService.getCurrentState());
            
            // Update volume boost seekbar if exists
            if (volumeBoostSeekBar != null) {
                volumeBoostSeekBar.setProgress(voiceService.getAudioBoostLevel());
            }
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
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        statusText = findViewById(R.id.statusText);
        transcriptText = findViewById(R.id.transcriptText);
        transcriptScroll = findViewById(R.id.transcriptScroll);
        voiceAnimation = findViewById(R.id.voiceAnimation);
        waveformView = findViewById(R.id.waveformView);
        speakerText = findViewById(R.id.speakerText);
        statsText = findViewById(R.id.statsText);
        volumeText = findViewById(R.id.volumeText);
        volumeBoostSeekBar = findViewById(R.id.volumeBoostSeekBar);
        
        stopButton.setEnabled(false);
        statusText.setText("Initializing...");
        
        if (speakerText != null) {
            speakerText.setVisibility(View.GONE);
        }
        
        if (statsText != null) {
            statsText.setVisibility(View.GONE);
        }
        
        // Setup volume boost seekbar
        if (volumeBoostSeekBar != null) {
            volumeBoostSeekBar.setMax(100);
            volumeBoostSeekBar.setProgress(33); // Default 33% boost
            
            volumeBoostSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isServiceBound && voiceService != null) {
                        voiceService.setAudioBoostLevel(progress);
                        updateVolumeText(progress);
                    }
                }
                
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
        
        updateVolumeText(33);
    }
    
    private void updateVolumeText(int percent) {
        if (volumeText != null) {
            volumeText.setText(String.format("Volume Boost: %d%%", percent));
        }
    }
    
    private void setupClickListeners() {
        startButton.setOnClickListener(v -> {
            if (isServiceBound && voiceService != null) {
                transcriptBuilder = new StringBuilder();
                transcriptText.setText("");
                voiceService.startConversation();
            }
        });
        
        stopButton.setOnClickListener(v -> {
            if (isServiceBound && voiceService != null) {
                voiceService.stopConversation();
                showSessionStats();
            }
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
                startButton.setEnabled(false);
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
            String prefix = isUser ? "You: " : "AI: ";
            transcriptBuilder.append(prefix).append(text).append("\n\n");
            transcriptText.setText(transcriptBuilder.toString());
            transcriptScroll.post(() -> transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN));
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
            if (waveformView != null) {
                waveformView.setAudioLevel(level);
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
                statusText.setText("Ready to start");
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(false);
                    voiceAnimation.setSpeaking(false);
                }
                if (waveformView != null) {
                    waveformView.setIdle();
                }
                break;
                
            case CONNECTING:
            case CONFIGURING:
                statusText.setText("Connecting...");
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(false);
                    voiceAnimation.setSpeaking(false);
                }
                if (waveformView != null) {
                    waveformView.setIdle();
                }
                break;
                
            case READY:
                statusText.setText("Ready - Speak now");
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(true);
                    voiceAnimation.setSpeaking(false);
                }
                if (waveformView != null) {
                    waveformView.setListening(true);
                }
                break;
                
            case LISTENING:
                statusText.setText("Listening...");
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(true);
                    voiceAnimation.setSpeaking(false);
                }
                if (waveformView != null) {
                    waveformView.setListening(true);
                }
                break;
                
            case PROCESSING:
            case EXECUTING_FUNCTION:
                statusText.setText("Processing...");
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(false);
                    voiceAnimation.setSpeaking(false);
                }
                if (waveformView != null) {
                    waveformView.setIdle();
                }
                break;
                
            case SPEAKING:
                statusText.setText("AI Speaking...");
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(false);
                    voiceAnimation.setSpeaking(true);
                }
                if (waveformView != null) {
                    waveformView.setSpeaking(true);
                }
                break;
                
            case DISCONNECTING:
                statusText.setText("Disconnecting...");
                startButton.setEnabled(false);
                stopButton.setEnabled(false);
                break;
                
            case ERROR:
                statusText.setText("Error - Tap Start to retry");
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                if (voiceAnimation != null) {
                    voiceAnimation.setListening(false);
                    voiceAnimation.setSpeaking(false);
                }
                if (waveformView != null) {
                    waveformView.setIdle();
                }
                break;
        }
    }
    
    private void showSessionStats() {
        if (voiceService == null || statsText == null) return;
        
        ConversationAnalytics analytics = voiceService.getAnalytics();
        if (analytics == null) return;
        
        ConversationAnalytics.AggregateStats stats = analytics.getAggregateStats();
        
        // Also show audio info
        AudioBooster audioBooster = voiceService.getAudioBooster();
        String audioInfo = audioBooster != null ? audioBooster.getVolumeInfo() : "N/A";
        
        String statsStr = String.format(
            "Sessions: %d | Total Time: %s | Avg Response: %dms\nAudio: %s",
            stats.totalSessions,
            formatDuration(stats.totalDurationMs),
            stats.avgResponseTimeMs,
            audioInfo
        );
        
        statsText.setVisibility(View.VISIBLE);
        statsText.setText(statsStr);
    }
    
    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}