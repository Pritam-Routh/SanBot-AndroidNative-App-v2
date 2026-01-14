package com.tripandevent.sanbotvoice.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import com.tripandevent.sanbotvoice.utils.Logger;

/**
 * Enhanced audio processor with:
 * - Hardware noise suppression
 * - Acoustic echo cancellation
 * - Automatic gain control
 * - Voice activity detection
 * - Low latency audio configuration
 */
public class AudioProcessor {
    
    private static final String TAG = "AudioProcessor";
    
    private final Context context;
    private final AudioManager audioManager;
    
    // Audio effects
    private NoiseSuppressor noiseSuppressor;
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl gainControl;
    
    // Audio configuration
    private int audioSessionId = -1;
    private boolean isInitialized = false;
    
    // Callbacks
    private AudioProcessorCallback callback;
    
    // Voice Activity Detection
    private static final float VAD_THRESHOLD_DB = -40f;
    private static final int VAD_FRAME_SIZE = 480;
    private boolean isSpeechDetected = false;
    private long lastSpeechTime = 0;
    private static final long SPEECH_TIMEOUT_MS = 500;
    
    public interface AudioProcessorCallback {
        void onSpeechStart();
        void onSpeechEnd();
        void onAudioLevel(float level);
        void onError(String error);
    }
    
    public AudioProcessor(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    
    public void setCallback(AudioProcessorCallback callback) {
        this.callback = callback;
    }
    
    /**
     * Initialize audio processing with the given audio session ID
     */
    public void initialize(int audioSessionId) {
        this.audioSessionId = audioSessionId;
        
        if (audioSessionId <= 0) {
            Logger.w(TAG, "Invalid audio session ID: %d", audioSessionId);
            return;
        }
        
        configureAudioMode();
        initializeNoiseSuppressor();
        initializeEchoCanceler();
        initializeGainControl();
        
        isInitialized = true;
        Logger.d(TAG, "Audio processor initialized with session ID: %d", audioSessionId);
        logAudioCapabilities();
    }
    
    private void configureAudioMode() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            Logger.d(TAG, "Audio mode set to VOICE_COMMUNICATION");
        } catch (Exception e) {
            Logger.e(e, "Failed to configure audio mode");
        }
    }
    
    private void initializeNoiseSuppressor() {
        if (!NoiseSuppressor.isAvailable()) {
            Logger.w(TAG, "Hardware noise suppressor not available");
            return;
        }
        
        try {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId);
            if (noiseSuppressor != null) {
                noiseSuppressor.setEnabled(true);
                Logger.d(TAG, "Hardware noise suppressor enabled");
            }
        } catch (Exception e) {
            Logger.e(e, "Failed to initialize noise suppressor");
        }
    }
    
    private void initializeEchoCanceler() {
        if (!AcousticEchoCanceler.isAvailable()) {
            Logger.w(TAG, "Hardware echo canceler not available");
            return;
        }
        
        try {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId);
            if (echoCanceler != null) {
                echoCanceler.setEnabled(true);
                Logger.d(TAG, "Hardware echo canceler enabled");
            }
        } catch (Exception e) {
            Logger.e(e, "Failed to initialize echo canceler");
        }
    }
    
    private void initializeGainControl() {
        if (!AutomaticGainControl.isAvailable()) {
            Logger.w(TAG, "Hardware AGC not available");
            return;
        }
        
        try {
            gainControl = AutomaticGainControl.create(audioSessionId);
            if (gainControl != null) {
                gainControl.setEnabled(true);
                Logger.d(TAG, "Hardware AGC enabled");
            }
        } catch (Exception e) {
            Logger.e(e, "Failed to initialize AGC");
        }
    }
    
    /**
     * Process audio samples for VAD and level detection
     */
    public void processAudioSamples(short[] samples) {
        if (samples == null || samples.length == 0) return;
        
        float rms = calculateRMS(samples);
        float dbLevel = 20 * (float) Math.log10(rms / 32768f);
        
        float normalizedLevel = Math.max(0, Math.min(1, (dbLevel + 60) / 60));
        if (callback != null) {
            callback.onAudioLevel(normalizedLevel);
        }
        
        // Voice Activity Detection
        boolean speechNow = dbLevel > VAD_THRESHOLD_DB;
        long now = System.currentTimeMillis();
        
        if (speechNow) {
            lastSpeechTime = now;
            if (!isSpeechDetected) {
                isSpeechDetected = true;
                if (callback != null) {
                    callback.onSpeechStart();
                }
            }
        } else if (isSpeechDetected && (now - lastSpeechTime) > SPEECH_TIMEOUT_MS) {
            isSpeechDetected = false;
            if (callback != null) {
                callback.onSpeechEnd();
            }
        }
    }
    
    private float calculateRMS(short[] samples) {
        long sum = 0;
        for (short sample : samples) {
            sum += (long) sample * sample;
        }
        return (float) Math.sqrt((double) sum / samples.length);
    }
    
    /**
     * Apply software noise reduction
     */
    public short[] applySoftwareNoiseReduction(short[] samples, float noiseFloor) {
        short[] output = new short[samples.length];
        
        for (int i = 0; i < samples.length; i++) {
            float sample = samples[i];
            float sign = Math.signum(sample);
            float magnitude = Math.abs(sample);
            magnitude = Math.max(0, magnitude - noiseFloor * 32768);
            output[i] = (short) (sign * magnitude);
        }
        
        return output;
    }
    
    private void logAudioCapabilities() {
        Logger.d(TAG, "=== Audio Capabilities ===");
        Logger.d(TAG, "Noise Suppressor available: %b", NoiseSuppressor.isAvailable());
        Logger.d(TAG, "Echo Canceler available: %b", AcousticEchoCanceler.isAvailable());
        Logger.d(TAG, "AGC available: %b", AutomaticGainControl.isAvailable());
    }
    
    public static int getOptimalBufferSize(int sampleRate) {
        int minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        return minBuffer * 2;
    }
    
    public boolean isLowLatencySupported() {
        return context.getPackageManager().hasSystemFeature("android.hardware.audio.low_latency");
    }
    
    public boolean isProAudioSupported() {
        return context.getPackageManager().hasSystemFeature("android.hardware.audio.pro");
    }
    
    public void release() {
        if (noiseSuppressor != null) {
            noiseSuppressor.setEnabled(false);
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        
        if (echoCanceler != null) {
            echoCanceler.setEnabled(false);
            echoCanceler.release();
            echoCanceler = null;
        }
        
        if (gainControl != null) {
            gainControl.setEnabled(false);
            gainControl.release();
            gainControl = null;
        }
        
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        }
        
        isInitialized = false;
        Logger.d(TAG, "Audio processor released");
    }
    
    public boolean isNoiseSuppressionEnabled() {
        return noiseSuppressor != null && noiseSuppressor.getEnabled();
    }
    
    public boolean isEchoCancellationEnabled() {
        return echoCanceler != null && echoCanceler.getEnabled();
    }
    
    public boolean isGainControlEnabled() {
        return gainControl != null && gainControl.getEnabled();
    }
    
    public boolean isInitialized() {
        return isInitialized;
    }
}