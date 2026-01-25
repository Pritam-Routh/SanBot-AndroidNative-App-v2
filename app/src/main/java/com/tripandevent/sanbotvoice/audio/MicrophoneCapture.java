package com.tripandevent.sanbotvoice.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.core.content.ContextCompat;

import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microphone capture for OpenAI WebSocket mode.
 *
 * Captures audio from the microphone and provides PCM16 data
 * that can be sent to OpenAI via input_audio_buffer.append.
 *
 * Audio format: 24kHz, 16-bit signed, mono (PCM16)
 * This matches OpenAI Realtime API's expected input format.
 */
public class MicrophoneCapture {

    private static final String TAG = "MicrophoneCapture";

    // Audio format matching OpenAI Realtime API
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // Buffer size for ~20ms of audio (optimal for WebRTC/VoIP)
    private static final int BUFFER_DURATION_MS = 20;
    private static final int SAMPLES_PER_BUFFER = SAMPLE_RATE * BUFFER_DURATION_MS / 1000; // 480 samples
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit
    private static final int BUFFER_SIZE_BYTES = SAMPLES_PER_BUFFER * BYTES_PER_SAMPLE; // 960 bytes

    private final Context context;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private AudioDataCallback callback;

    /**
     * Callback interface for audio data
     */
    public interface AudioDataCallback {
        /**
         * Called when audio data is captured
         * @param pcmData Raw PCM16 audio bytes (24kHz, 16-bit signed, mono)
         */
        void onAudioData(byte[] pcmData);

        /**
         * Called when an error occurs
         */
        void onError(String error);
    }

    public MicrophoneCapture(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCallback(AudioDataCallback callback) {
        this.callback = callback;
    }

    /**
     * Start capturing audio from the microphone
     */
    public boolean start() {
        if (isCapturing.get()) {
            Logger.w(TAG, "Already capturing");
            return true;
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Logger.e(TAG, "RECORD_AUDIO permission not granted");
            if (callback != null) {
                callback.onError("Microphone permission not granted");
            }
            return false;
        }

        // Get minimum buffer size
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Logger.e(TAG, "Failed to get minimum buffer size");
            if (callback != null) {
                callback.onError("Audio configuration not supported");
            }
            return false;
        }

        // Use larger buffer to avoid underruns
        int bufferSize = Math.max(minBufferSize * 2, BUFFER_SIZE_BYTES * 4);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord failed to initialize");
                if (callback != null) {
                    callback.onError("Failed to initialize microphone");
                }
                return false;
            }

            audioRecord.startRecording();
            isCapturing.set(true);

            // Start capture thread
            captureThread = new Thread(this::captureLoop, "MicrophoneCapture");
            captureThread.start();

            Logger.i(TAG, "Microphone capture started (24kHz, PCM16, mono)");
            return true;

        } catch (SecurityException e) {
            Logger.e(e, "Security exception starting microphone");
            if (callback != null) {
                callback.onError("Microphone permission denied");
            }
            return false;
        } catch (Exception e) {
            Logger.e(e, "Failed to start microphone capture");
            if (callback != null) {
                callback.onError("Failed to start microphone: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Stop capturing audio
     */
    public void stop() {
        isCapturing.set(false);

        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Logger.w(TAG, "Interrupted while stopping capture thread");
            }
            captureThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Logger.w(TAG, "Error stopping AudioRecord: " + e.getMessage());
            }
            audioRecord = null;
        }

        Logger.i(TAG, "Microphone capture stopped");
    }

    /**
     * Check if currently capturing
     */
    public boolean isCapturing() {
        return isCapturing.get();
    }

    /**
     * Audio capture loop
     */
    private void captureLoop() {
        byte[] buffer = new byte[BUFFER_SIZE_BYTES];

        Logger.d(TAG, "Capture loop started, buffer size: %d bytes", BUFFER_SIZE_BYTES);

        while (isCapturing.get() && audioRecord != null) {
            int bytesRead = audioRecord.read(buffer, 0, BUFFER_SIZE_BYTES);

            if (bytesRead > 0 && callback != null) {
                // Copy the buffer to avoid issues with reuse
                byte[] audioData = new byte[bytesRead];
                System.arraycopy(buffer, 0, audioData, 0, bytesRead);
                callback.onAudioData(audioData);
            } else if (bytesRead < 0) {
                Logger.e(TAG, "AudioRecord read error: %d", bytesRead);
                if (callback != null) {
                    callback.onError("Audio capture error");
                }
                break;
            }
        }

        Logger.d(TAG, "Capture loop ended");
    }

    /**
     * Get the audio session ID for audio effects
     */
    public int getAudioSessionId() {
        if (audioRecord != null) {
            return audioRecord.getAudioSessionId();
        }
        return 0;
    }
}
