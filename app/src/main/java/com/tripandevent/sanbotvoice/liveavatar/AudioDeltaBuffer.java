package com.tripandevent.sanbotvoice.liveavatar;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioDeltaBuffer
 *
 * High-performance buffer for streaming PCM audio deltas from OpenAI
 * to LiveAvatar with minimal latency.
 *
 * Audio Format: PCM 24kHz, 16-bit signed, mono
 * Chunk Size: 20ms (960 bytes)
 *
 * For LOWEST LATENCY operation:
 * - Streams audio chunks immediately as they arrive
 * - No batching delays - direct forwarding
 * - Splits incoming audio into optimal WebRTC frame size (20ms)
 *
 * Usage:
 * 1. Set listener via setListener()
 * 2. Call addAudioDelta() with base64 audio from OpenAI
 * 3. Call complete() when response is done
 * 4. Call clear() to reset/cancel
 */
public class AudioDeltaBuffer {
    private static final String TAG = "AudioDeltaBuffer";

    // Audio format constants (matching OpenAI Realtime output and LiveAvatar input)
    private static final int SAMPLE_RATE = LiveAvatarConfig.AUDIO_SAMPLE_RATE;  // 24000 Hz
    private static final int BYTES_PER_SAMPLE = LiveAvatarConfig.BYTES_PER_SAMPLE;  // 2 bytes (16-bit)
    private static final int CHUNK_DURATION_MS = LiveAvatarConfig.AUDIO_CHUNK_DURATION_MS;  // 20ms
    private static final int BYTES_PER_CHUNK = LiveAvatarConfig.BYTES_PER_CHUNK;  // 960 bytes

    /**
     * Callback interface for audio chunk events
     */
    public interface AudioFlushListener {
        /**
         * Called when an audio chunk is ready to be sent as raw bytes.
         * @param audioBytes Raw PCM audio bytes (20ms, 960 bytes)
         *                   16-bit signed, mono, 24kHz
         *                   Should be sent as binary WebSocket frame for lip sync.
         */
        void onAudioChunk(byte[] audioBytes);

        /**
         * Called when all audio has been sent
         */
        void onAudioComplete();
    }

    // Buffer for accumulating partial audio data
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    // Handler for main thread callbacks (optional)
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // State tracking
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    // Listener for audio events
    private AudioFlushListener listener;

    // Statistics for debugging
    private int totalChunksSent = 0;
    private int totalBytesReceived = 0;
    private long streamStartTime = 0;

    /**
     * Set the callback listener for audio events
     */
    public void setListener(AudioFlushListener listener) {
        this.listener = listener;
    }

    /**
     * Enable or disable the buffer
     * When disabled, audio deltas are ignored
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (!enabled) {
            clear();
        }
    }

    /**
     * Check if buffer is enabled
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Check if currently streaming audio
     */
    public boolean isStreaming() {
        return isStreaming.get();
    }

    /**
     * Add audio delta from OpenAI response.
     * Immediately processes and streams to LiveAvatar for lowest latency.
     *
     * @param base64Audio Base64 encoded PCM audio chunk from OpenAI
     */
    public synchronized void addAudioDelta(String base64Audio) {
        if (!enabled.get() || base64Audio == null || base64Audio.isEmpty()) {
            return;
        }

        // Start streaming on first delta
        if (!isStreaming.getAndSet(true)) {
            streamStartTime = System.currentTimeMillis();
            totalChunksSent = 0;
            totalBytesReceived = 0;
            Log.d(TAG, "Audio streaming started");
        }

        try {
            // Decode base64 audio
            byte[] audioBytes = Base64.decode(base64Audio, Base64.DEFAULT);
            totalBytesReceived += audioBytes.length;

            // Append to buffer
            audioBuffer.write(audioBytes);

            // Stream complete chunks immediately (20ms each)
            while (audioBuffer.size() >= BYTES_PER_CHUNK) {
                byte[] allBytes = audioBuffer.toByteArray();

                // Extract one chunk
                byte[] chunk = new byte[BYTES_PER_CHUNK];
                System.arraycopy(allBytes, 0, chunk, 0, BYTES_PER_CHUNK);

                // Keep remaining bytes in buffer
                audioBuffer.reset();
                if (allBytes.length > BYTES_PER_CHUNK) {
                    audioBuffer.write(allBytes, BYTES_PER_CHUNK, allBytes.length - BYTES_PER_CHUNK);
                }

                // Send chunk immediately for lowest latency
                sendChunk(chunk);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing audio delta", e);
        }
    }

    /**
     * Called when OpenAI response is complete.
     * Flushes any remaining audio in the buffer.
     */
    public synchronized void complete() {
        if (!isStreaming.get()) {
            return;
        }

        // Flush any remaining audio (may be less than 20ms)
        if (audioBuffer.size() > 0) {
            byte[] remaining = audioBuffer.toByteArray();
            sendChunk(remaining);
            audioBuffer.reset();
        }

        // Log statistics
        long duration = System.currentTimeMillis() - streamStartTime;
        Log.d(TAG, String.format("Audio streaming complete: %d chunks, %d bytes, %dms",
            totalChunksSent, totalBytesReceived, duration));

        isStreaming.set(false);

        // Notify listener
        if (listener != null) {
            listener.onAudioComplete();
        }
    }

    /**
     * Clear buffer and cancel streaming.
     * Use this for interruption/barge-in.
     */
    public synchronized void clear() {
        if (audioBuffer.size() > 0) {
            Log.d(TAG, "Clearing audio buffer: " + audioBuffer.size() + " bytes discarded");
        }
        audioBuffer.reset();
        isStreaming.set(false);
        totalChunksSent = 0;
        totalBytesReceived = 0;
    }

    /**
     * Send a single audio chunk to the listener as raw bytes.
     *
     * The raw bytes should be sent as a binary WebSocket frame to LiveAvatar.
     * This ensures no encoding/decoding corruption occurs.
     */
    private void sendChunk(byte[] chunk) {
        if (listener == null || chunk == null || chunk.length == 0) {
            return;
        }

        totalChunksSent++;

        // Pass raw bytes directly - LiveAvatarSessionManager will send as binary frame
        listener.onAudioChunk(chunk);
    }

    /**
     * Get current buffer size in bytes
     */
    public int getBufferSize() {
        return audioBuffer.size();
    }

    /**
     * Get total chunks sent in current stream
     */
    public int getTotalChunksSent() {
        return totalChunksSent;
    }

    /**
     * Get total bytes received in current stream
     */
    public int getTotalBytesReceived() {
        return totalBytesReceived;
    }

    /**
     * Get audio duration received in milliseconds
     */
    public long getAudioDurationMs() {
        // bytes / (sample_rate * bytes_per_sample) * 1000
        return (long) totalBytesReceived / (SAMPLE_RATE * BYTES_PER_SAMPLE / 1000);
    }

    /**
     * Cleanup resources
     */
    public void destroy() {
        clear();
        listener = null;
    }
}
