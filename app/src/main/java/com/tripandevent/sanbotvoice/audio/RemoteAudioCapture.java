package com.tripandevent.sanbotvoice.audio;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RemoteAudioCapture
 *
 * Captures and processes PCM 16-bit 24kHz mono audio from OpenAI Realtime API.
 *
 * This class implements JavaAudioDeviceModule.SamplesReadyCallback to intercept
 * audio samples from the WebRTC audio device module.
 *
 * Audio Format: PCM 16-bit signed, little-endian, 24kHz, mono
 * Chunk Duration: ~10ms per callback (WebRTC standard)
 *
 * Usage:
 * 1. Create instance
 * 2. Pass to JavaAudioDeviceModule.Builder.setPlayoutSamplesReadyCallback()
 * 3. Audio samples are received in onWebRtcAudioPlayoutSamplesReady()
 * 4. Or call addAudioDelta() with base64 audio from data channel (fallback)
 * 5. Receive decoded PCM data via onPcmAudioCaptured callback
 * 6. Call release() when done
 *
 * This class serves as the central hub for capturing OpenAI's audio output
 * and forwarding it to LiveAvatar for lip-synced playback.
 */
public class RemoteAudioCapture implements JavaAudioDeviceModule.SamplesReadyCallback {

    private static final String TAG = "RemoteAudioCapture";

    // Audio format constants (matching OpenAI Realtime API output)
    public static final int SAMPLE_RATE = Constants.AUDIO_SAMPLE_RATE;  // 24000 Hz
    public static final int CHANNELS = Constants.AUDIO_CHANNELS;         // 1 (mono)
    public static final int BITS_PER_SAMPLE = Constants.AUDIO_BITS_PER_SAMPLE;  // 16-bit
    public static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;  // 2 bytes

    // Frame size: WebRTC typically sends 10ms chunks
    // 24000 Hz * 0.010s = 240 samples per 10ms frame
    // 240 samples * 2 bytes = 480 bytes per 10ms frame
    public static final int SAMPLES_PER_10MS = SAMPLE_RATE / 100;
    public static final int BYTES_PER_10MS = SAMPLES_PER_10MS * BYTES_PER_SAMPLE;

    /**
     * Callback interface for receiving captured PCM audio
     */
    public interface AudioCaptureListener {
        /**
         * Called when PCM audio data is captured/decoded.
         *
         * @param pcmData Raw PCM audio bytes (16-bit signed, little-endian, 24kHz, mono)
         * @param numSamples Number of audio samples
         * @param sampleRate Sample rate in Hz (24000)
         * @param channels Number of channels (1 = mono)
         */
        void onPcmAudioCaptured(byte[] pcmData, int numSamples, int sampleRate, int channels);

        /**
         * Called when audio capture starts (first frame received)
         */
        default void onCaptureStarted() {}

        /**
         * Called when audio capture stops
         */
        default void onCaptureStopped() {}

        /**
         * Called on error
         */
        default void onCaptureError(String error) {}
    }

    private final Handler mainHandler;
    private final ExecutorService ioExecutor;
    private AudioCaptureListener listener;

    // State
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);

    // Statistics
    private long totalBytesCaptured = 0;
    private long totalFramesCaptured = 0;
    private long captureStartTime = 0;

    // Optional file recording
    private FileOutputStream fileOutputStream;
    private File recordingFile;
    private boolean isRecordingToFile = false;

    public RemoteAudioCapture() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.ioExecutor = Executors.newSingleThreadExecutor();
    }

    public RemoteAudioCapture(AudioCaptureListener listener) {
        this();
        this.listener = listener;
    }

    /**
     * Set the listener for captured audio events
     */
    public void setListener(AudioCaptureListener listener) {
        this.listener = listener;
    }

    /**
     * Enable or disable audio capture
     * When disabled, audio data is ignored
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        if (!enabled && isCapturing.get()) {
            stopCapture();
        }
    }

    /**
     * Check if capture is enabled
     */
    public boolean isEnabled() {
        return isEnabled.get();
    }

    /**
     * Check if currently capturing audio
     */
    public boolean isCapturing() {
        return isCapturing.get();
    }

    // ============================================
    // JavaAudioDeviceModule.SamplesReadyCallback
    // ============================================

    /**
     * Called by JavaAudioDeviceModule when playout audio samples are ready.
     * This captures the audio being played to the speaker (OpenAI's response).
     *
     * @param audioSamples The audio samples from WebRTC playout
     */
    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
        // Note: This callback is for RECORDING (microphone), not playout
        // We need setPlayoutSamplesReadyCallback for speaker output
        // This method is required by the interface but we don't use it for capture
    }

    /**
     * Process audio samples from WebRTC playout.
     * Call this from a custom playout callback.
     *
     * @param audioSamples The audio samples
     */
    public void onPlayoutSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
        if (!isEnabled.get()) {
            return;
        }

        // Convert AudioSamples to byte array
        byte[] pcmData = audioSamples.getData();
        int sampleRate = audioSamples.getSampleRate();
        int channels = audioSamples.getChannelCount();
        int numSamples = pcmData.length / BYTES_PER_SAMPLE;

        // Process the audio
        processAudioData(pcmData, numSamples, sampleRate, channels);
    }

    // ============================================
    // Data Channel Audio (Fallback)
    // ============================================

    /**
     * Add base64-encoded audio delta from OpenAI data channel.
     * Decodes the base64 and forwards as raw PCM.
     * This is a fallback when WebRTC playout capture is not available.
     *
     * @param base64Audio Base64 encoded PCM audio from OpenAI
     */
    public void addAudioDelta(String base64Audio) {
        if (!isEnabled.get() || base64Audio == null || base64Audio.isEmpty()) {
            return;
        }

        try {
            // Decode base64 to raw PCM bytes
            byte[] pcmData = Base64.decode(base64Audio, Base64.DEFAULT);
            addPcmAudio(pcmData);

        } catch (Exception e) {
            Logger.e(e, "Error decoding base64 audio");
            notifyError("Failed to decode audio: " + e.getMessage());
        }
    }

    /**
     * Add raw PCM audio data.
     * This is an alternative entry point for audio capture when
     * WebRTC playout capture is not available.
     *
     * @param pcmData Raw PCM bytes (16-bit signed, little-endian, 24kHz, mono)
     */
    public void addPcmAudio(byte[] pcmData) {
        if (!isEnabled.get() || pcmData == null || pcmData.length == 0) {
            return;
        }

        int numSamples = pcmData.length / BYTES_PER_SAMPLE;
        processAudioData(pcmData, numSamples, SAMPLE_RATE, CHANNELS);
    }

    /**
     * Process captured audio data (from either WebRTC or data channel)
     */
    private void processAudioData(byte[] pcmData, int numSamples, int sampleRate, int channels) {
        // Mark capture started on first data
        if (!isCapturing.getAndSet(true)) {
            captureStartTime = System.currentTimeMillis();
            totalBytesCaptured = 0;
            totalFramesCaptured = 0;
            Logger.d(TAG, "Audio capture started (rate=%d, channels=%d)", sampleRate, channels);
            notifyCaptureStarted();
        }

        // Update statistics
        totalBytesCaptured += pcmData.length;
        totalFramesCaptured++;

        // Write to file if recording
        if (isRecordingToFile && fileOutputStream != null) {
            writeToFile(pcmData);
        }

        // Notify listener on main thread
        if (listener != null) {
            // Make a copy to avoid buffer reuse issues
            byte[] pcmCopy = pcmData.clone();
            final int finalSampleRate = sampleRate;
            final int finalChannels = channels;
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onPcmAudioCaptured(pcmCopy, numSamples, finalSampleRate, finalChannels);
                }
            });
        }
    }

    /**
     * Signal that audio capture/streaming has completed.
     * Call this when OpenAI response is done.
     */
    public void complete() {
        if (isCapturing.getAndSet(false)) {
            long duration = getCaptureDurationMs();
            Logger.d(TAG, "Audio capture completed. Duration: %dms, Bytes: %d, Frames: %d",
                    duration, totalBytesCaptured, totalFramesCaptured);
            notifyCaptureStopped();
        }
    }

    /**
     * Clear/reset capture state (for interruption/barge-in)
     */
    public void clear() {
        if (isCapturing.getAndSet(false)) {
            Logger.d(TAG, "Audio capture cleared");
        }
        resetStatistics();
    }

    /**
     * Detach and cleanup (alias for clear for API compatibility)
     */
    public void detach() {
        clear();
    }

    // ============================================
    // File Recording (Optional)
    // ============================================

    /**
     * Start recording captured audio to a file.
     * Creates a raw PCM file that can be converted to WAV later.
     *
     * @param file The file to write PCM data to
     * @return true if recording started successfully
     */
    public boolean startRecordingToFile(File file) {
        if (isRecordingToFile) {
            Logger.w(TAG, "Already recording to file");
            return false;
        }

        try {
            recordingFile = file;
            fileOutputStream = new FileOutputStream(file);
            isRecordingToFile = true;
            Logger.d(TAG, "Started recording to: %s", file.getAbsolutePath());
            return true;

        } catch (IOException e) {
            Logger.e(e, "Failed to start recording to file");
            return false;
        }
    }

    /**
     * Stop recording to file and close the stream
     */
    public void stopRecordingToFile() {
        if (!isRecordingToFile) {
            return;
        }

        isRecordingToFile = false;

        ioExecutor.execute(() -> {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.flush();
                    fileOutputStream.close();
                    fileOutputStream = null;
                }

                if (recordingFile != null) {
                    Logger.d(TAG, "Recording stopped. File: %s, Size: %d bytes",
                            recordingFile.getAbsolutePath(), recordingFile.length());
                    recordingFile = null;
                }

            } catch (IOException e) {
                Logger.e(e, "Error closing recording file");
            }
        });
    }

    /**
     * Convert a recorded raw PCM file to WAV format
     *
     * @param pcmFile The source PCM file
     * @param wavFile The destination WAV file
     * @return true if conversion was successful
     */
    public static boolean convertPcmToWav(File pcmFile, File wavFile) {
        try {
            byte[] pcmData = java.nio.file.Files.readAllBytes(pcmFile.toPath());
            return writeWavFile(wavFile, pcmData, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);
        } catch (IOException e) {
            Logger.e(e, "Failed to convert PCM to WAV");
            return false;
        }
    }

    /**
     * Write PCM data to WAV file with proper header
     */
    public static boolean writeWavFile(File file, byte[] pcmData,
                                        int sampleRate, int channels, int bitsPerSample) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int dataSize = pcmData.length;
            int chunkSize = 36 + dataSize;

            // WAV header (44 bytes)
            ByteBuffer header = ByteBuffer.allocate(44);
            header.order(ByteOrder.LITTLE_ENDIAN);

            // RIFF chunk descriptor
            header.put("RIFF".getBytes());           // ChunkID
            header.putInt(chunkSize);                // ChunkSize
            header.put("WAVE".getBytes());           // Format

            // fmt sub-chunk
            header.put("fmt ".getBytes());           // Subchunk1ID
            header.putInt(16);                       // Subchunk1Size (PCM)
            header.putShort((short) 1);              // AudioFormat (1 = PCM)
            header.putShort((short) channels);       // NumChannels
            header.putInt(sampleRate);               // SampleRate
            header.putInt(byteRate);                 // ByteRate
            header.putShort((short) blockAlign);     // BlockAlign
            header.putShort((short) bitsPerSample);  // BitsPerSample

            // data sub-chunk
            header.put("data".getBytes());           // Subchunk2ID
            header.putInt(dataSize);                 // Subchunk2Size

            fos.write(header.array());
            fos.write(pcmData);

            Logger.d(TAG, "WAV file written: %s (%d bytes)", file.getName(), file.length());
            return true;

        } catch (IOException e) {
            Logger.e(e, "Failed to write WAV file");
            return false;
        }
    }

    private void writeToFile(byte[] data) {
        ioExecutor.execute(() -> {
            try {
                if (fileOutputStream != null && isRecordingToFile) {
                    fileOutputStream.write(data);
                }
            } catch (IOException e) {
                Logger.e(e, "Error writing audio to file");
            }
        });
    }

    // ============================================
    // Statistics
    // ============================================

    /**
     * Get total bytes captured
     */
    public long getTotalBytesCaptured() {
        return totalBytesCaptured;
    }

    /**
     * Get total frames captured
     */
    public long getTotalFramesCaptured() {
        return totalFramesCaptured;
    }

    /**
     * Get capture duration in milliseconds
     */
    public long getCaptureDurationMs() {
        if (captureStartTime == 0) return 0;
        return System.currentTimeMillis() - captureStartTime;
    }

    /**
     * Get estimated audio duration based on captured bytes
     */
    public long getAudioDurationMs() {
        // bytes / (sample_rate * bytes_per_sample) * 1000
        return totalBytesCaptured * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE);
    }

    private void resetStatistics() {
        totalBytesCaptured = 0;
        totalFramesCaptured = 0;
        captureStartTime = 0;
    }

    // ============================================
    // Utility Methods
    // ============================================

    /**
     * Convert PCM bytes to short array (for audio processing)
     */
    public static short[] pcmBytesToShorts(byte[] pcmBytes) {
        short[] shorts = new short[pcmBytes.length / 2];
        ByteBuffer.wrap(pcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shorts);
        return shorts;
    }

    /**
     * Convert short array to PCM bytes
     */
    public static byte[] shortsToPcmBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(shorts);
        return bytes;
    }

    /**
     * Convert PCM bytes to float array (normalized -1.0 to 1.0)
     */
    public static float[] pcmBytesToFloats(byte[] pcmBytes) {
        short[] shorts = pcmBytesToShorts(pcmBytes);
        float[] floats = new float[shorts.length];
        for (int i = 0; i < shorts.length; i++) {
            floats[i] = shorts[i] / 32768f;
        }
        return floats;
    }

    /**
     * Convert float array to PCM bytes
     */
    public static byte[] floatsToPcmBytes(float[] floats) {
        short[] shorts = new short[floats.length];
        for (int i = 0; i < floats.length; i++) {
            // Clamp and convert
            float sample = Math.max(-1f, Math.min(1f, floats[i]));
            shorts[i] = (short) (sample * 32767);
        }
        return shortsToPcmBytes(shorts);
    }

    private void stopCapture() {
        if (isCapturing.getAndSet(false)) {
            long duration = getCaptureDurationMs();
            Logger.d(TAG, "Audio capture stopped. Duration: %dms, Bytes: %d, Frames: %d",
                    duration, totalBytesCaptured, totalFramesCaptured);
            notifyCaptureStopped();
        }
    }

    // ============================================
    // Notification Helpers
    // ============================================

    private void notifyCaptureStarted() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onCaptureStarted();
            }
        });
    }

    private void notifyCaptureStopped() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onCaptureStopped();
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onCaptureError(error);
            }
        });
    }

    /**
     * Release all resources
     */
    public void release() {
        setEnabled(false);
        stopRecordingToFile();
        clear();
        ioExecutor.shutdown();
        Logger.d(TAG, "RemoteAudioCapture released");
    }
}
