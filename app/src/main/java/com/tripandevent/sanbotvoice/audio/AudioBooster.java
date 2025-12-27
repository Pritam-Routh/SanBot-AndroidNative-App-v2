package com.tripandevent.sanbotvoice.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.LoudnessEnhancer;

import com.tripandevent.sanbotvoice.utils.Logger;

/**
 * Boosts audio volume for AI assistant playback.
 * 
 * Features:
 * - Configures audio mode for voice communication
 * - Enables speakerphone for louder output
 * - Maximizes all relevant audio streams
 * - Optional loudness enhancer for additional boost
 */
public class AudioBooster {
    
    private static final String TAG = "AudioBooster";
    
    private final Context context;
    private final AudioManager audioManager;
    private LoudnessEnhancer loudnessEnhancer;
    
    // Boost level in millibels (1000 mB = 10 dB)
    private static final int DEFAULT_BOOST_MB = 1000;  // 10 dB boost
    private static final int MAX_BOOST_MB = 3000;      // 30 dB max boost
    
    // Track original settings for restoration
    private int originalMode = AudioManager.MODE_NORMAL;
    private boolean originalSpeakerphone = false;
    private int originalVoiceVolume = 0;
    private int originalMusicVolume = 0;
    private boolean settingsSaved = false;
    
    // State
    private boolean isConfigured = false;
    
    public AudioBooster(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    
    /**
     * Save current audio settings before modifying
     */
    private void saveOriginalSettings() {
        if (settingsSaved) return;
        
        try {
            originalMode = audioManager.getMode();
            originalSpeakerphone = audioManager.isSpeakerphoneOn();
            originalVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            settingsSaved = true;
            
            Logger.d(TAG, "Original settings saved - Mode: %d, Speaker: %b, Voice: %d, Music: %d",
                originalMode, originalSpeakerphone, originalVoiceVolume, originalMusicVolume);
        } catch (Exception e) {
            Logger.e(e, "Failed to save original audio settings");
        }
    }
    
    /**
     * Configure audio for maximum volume playback
     */
    public void configureForMaxVolume() {
        if (isConfigured) {
            Logger.d(TAG, "Audio already configured for max volume");
            return;
        }
        
        try {
            // Save original settings first
            saveOriginalSettings();
            
            // Set audio mode for voice communication
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            
            // Enable speakerphone for louder output
            audioManager.setSpeakerphoneOn(true);
            
            // Maximize voice call volume
            int maxVoiceVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceVolume, 0);
            
            // Maximize music volume (some devices route WebRTC audio here)
            int maxMusicVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVolume, 0);
            
            // Maximize system volume
            int maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, maxSystemVolume, 0);
            
            // Request audio focus
            requestAudioFocus();
            
            isConfigured = true;
            
            Logger.i(TAG, "Audio configured for max volume - Voice: %d/%d, Music: %d/%d", 
                maxVoiceVolume, maxVoiceVolume, maxMusicVolume, maxMusicVolume);
            
        } catch (Exception e) {
            Logger.e(e, "Failed to configure audio volume");
        }
    }
    
    /**
     * Request audio focus for voice communication
     */
    private void requestAudioFocus() {
        try {
            int result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
            
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logger.d(TAG, "Audio focus granted");
            } else {
                Logger.w(TAG, "Audio focus request failed: %d", result);
            }
        } catch (Exception e) {
            Logger.e(e, "Failed to request audio focus");
        }
    }
    
    /**
     * Attach loudness enhancer to audio session for additional boost.
     * Call this after WebRTC connection is established.
     * 
     * @param audioSessionId The audio session ID from WebRTC
     */
    public void attachLoudnessEnhancer(int audioSessionId) {
        if (audioSessionId <= 0) {
            Logger.w(TAG, "Invalid audio session ID: %d", audioSessionId);
            return;
        }
        
        try {
            // Release previous instance if exists
            releaseLoudnessEnhancer();
            
            loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
            loudnessEnhancer.setTargetGain(DEFAULT_BOOST_MB);
            loudnessEnhancer.setEnabled(true);
            
            Logger.i(TAG, "Loudness enhancer attached - Session: %d, Gain: %d mB", 
                audioSessionId, DEFAULT_BOOST_MB);
            
        } catch (Exception e) {
            Logger.e(e, "Failed to create loudness enhancer for session %d", audioSessionId);
        }
    }
    
    /**
     * Set boost level (0 to 100 percent)
     * 
     * @param percent Boost level from 0 (no boost) to 100 (maximum boost)
     */
    public void setBoostLevel(int percent) {
        // Clamp percent to valid range
        percent = Math.max(0, Math.min(100, percent));
        
        if (loudnessEnhancer == null) {
            Logger.w(TAG, "Loudness enhancer not initialized");
            return;
        }
        
        int boostMb = (int) ((percent / 100f) * MAX_BOOST_MB);
        try {
            loudnessEnhancer.setTargetGain(boostMb);
            Logger.d(TAG, "Boost level set to %d%% (%d mB)", percent, boostMb);
        } catch (Exception e) {
            Logger.e(e, "Failed to set boost level");
        }
    }
    
    /**
     * Get current boost level as percentage
     * 
     * @return Boost level from 0 to 100
     */
    public int getBoostLevel() {
        if (loudnessEnhancer == null) return 0;
        
        try {
            float gainMb = loudnessEnhancer.getTargetGain();
            return (int) ((gainMb / (float) MAX_BOOST_MB) * 100);
        } catch (Exception e) {
            Logger.e(e, "Failed to get boost level");
            return 0;
        }
    }
    
    /**
     * Check if loudness enhancer is active
     */
    public boolean isLoudnessEnhancerEnabled() {
        return loudnessEnhancer != null;
    }
    
    /**
     * Check if audio is configured for max volume
     */
    public boolean isConfigured() {
        return isConfigured;
    }
    
    /**
     * Release loudness enhancer resources
     */
    public void releaseLoudnessEnhancer() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setEnabled(false);
                loudnessEnhancer.release();
                Logger.d(TAG, "Loudness enhancer released");
            } catch (Exception e) {
                Logger.e(e, "Error releasing loudness enhancer");
            }
            loudnessEnhancer = null;
        }
    }
    
    /**
     * Reset audio to original settings
     */
    public void resetAudio() {
        try {
            // Release loudness enhancer first
            releaseLoudnessEnhancer();
            
            // Abandon audio focus
            audioManager.abandonAudioFocus(null);
            
            // Restore original settings
            if (settingsSaved) {
                audioManager.setMode(originalMode);
                audioManager.setSpeakerphoneOn(originalSpeakerphone);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, originalVoiceVolume, 0);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0);
                
                Logger.d(TAG, "Audio settings restored to original");
            } else {
                // Default reset
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
                Logger.d(TAG, "Audio reset to defaults");
            }
            
            isConfigured = false;
            settingsSaved = false;
            
        } catch (Exception e) {
            Logger.e(e, "Failed to reset audio");
        }
    }
    
    /**
     * Get current volume info for debugging
     */
    public String getVolumeInfo() {
        try {
            int voiceVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            int voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            int musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int mode = audioManager.getMode();
            boolean speaker = audioManager.isSpeakerphoneOn();
            
            return String.format(
                "Voice: %d/%d, Music: %d/%d, Mode: %d, Speaker: %b, Boost: %d%%",
                voiceVol, voiceMax, musicVol, musicMax, mode, speaker, getBoostLevel()
            );
        } catch (Exception e) {
            return "Error getting volume info";
        }
    }
}