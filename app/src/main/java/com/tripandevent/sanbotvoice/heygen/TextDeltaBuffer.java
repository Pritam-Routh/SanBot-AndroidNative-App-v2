package com.tripandevent.sanbotvoice.heygen;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

/**
 * TextDeltaBuffer
 *
 * Intelligent text delta batching for smooth avatar speech.
 *
 * Accumulates text deltas until one of these conditions is met:
 * 1. Sentence boundary reached (. ! ?)
 * 2. Minimum word threshold met AND batch delay elapsed
 * 3. Maximum buffer size exceeded
 * 4. Maximum delay timeout reached
 *
 * This ensures low-latency streaming while avoiding choppy speech
 * from too-small text chunks.
 */
public class TextDeltaBuffer {
    private static final String TAG = "TextDeltaBuffer";

    // Pattern to detect sentence boundaries
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]\\s*$");

    // Buffer state
    private final StringBuilder buffer = new StringBuilder();
    private int wordCount = 0;
    private long lastDeltaTime = 0;
    private long bufferStartTime = 0;

    // Configuration
    private final int minWords;
    private final int batchDelayMs;
    private final int maxBufferSize;
    private final int maxDelayMs;

    // Handler for delayed flush
    private final Handler handler;
    private Runnable pendingFlush;

    // Callback for flushed text
    private FlushCallback callback;

    // Enabled state
    private volatile boolean enabled = true;

    public interface FlushCallback {
        void onFlush(String text);
    }

    public TextDeltaBuffer() {
        this(HeyGenConfig.getMinWords(),
             HeyGenConfig.getBatchDelayMs(),
             HeyGenConfig.MAX_BUFFER_SIZE,
             HeyGenConfig.MAX_BUFFER_DELAY_MS);
    }

    public TextDeltaBuffer(int minWords, int batchDelayMs, int maxBufferSize, int maxDelayMs) {
        this.minWords = minWords;
        this.batchDelayMs = batchDelayMs;
        this.maxBufferSize = maxBufferSize;
        this.maxDelayMs = maxDelayMs;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Set the callback for flushed text
     */
    public void setCallback(@NonNull FlushCallback callback) {
        this.callback = callback;
    }

    /**
     * Enable or disable the buffer
     * When disabled, deltas are ignored
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clear();
        }
    }

    /**
     * Check if buffer is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Add a text delta to the buffer
     *
     * @param delta Text delta from OpenAI transcript
     */
    public synchronized void addDelta(String delta) {
        if (!enabled || delta == null || delta.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        // Initialize buffer start time
        if (buffer.length() == 0) {
            bufferStartTime = now;
        }

        // Append delta to buffer
        buffer.append(delta);
        lastDeltaTime = now;

        // Count words in delta
        wordCount += countWords(delta);

        // Cancel any pending flush
        cancelPendingFlush();

        // Check if we should flush
        boolean shouldFlush = false;
        String reason = null;

        // Check for sentence boundary
        if (SENTENCE_END.matcher(buffer).find()) {
            shouldFlush = true;
            reason = "sentence boundary";
        }
        // Check max buffer size
        else if (buffer.length() >= maxBufferSize) {
            shouldFlush = true;
            reason = "max buffer size";
        }
        // Check max delay
        else if (now - bufferStartTime >= maxDelayMs) {
            shouldFlush = true;
            reason = "max delay timeout";
        }
        // Check min words with batch delay
        else if (wordCount >= minWords) {
            // Schedule delayed flush
            scheduleDelayedFlush();
        }

        if (shouldFlush) {
            Log.d(TAG, "Flushing buffer (" + reason + "): " + wordCount + " words, " + buffer.length() + " chars");
            flush();
        }
    }

    /**
     * Force flush any remaining buffer content
     * Call this when response is complete
     */
    public synchronized void forceFlush() {
        cancelPendingFlush();
        if (buffer.length() > 0) {
            Log.d(TAG, "Force flushing buffer: " + wordCount + " words, " + buffer.length() + " chars");
            flush();
        }
    }

    /**
     * Clear the buffer without flushing
     */
    public synchronized void clear() {
        cancelPendingFlush();
        buffer.setLength(0);
        wordCount = 0;
        bufferStartTime = 0;
        lastDeltaTime = 0;
    }

    /**
     * Flush the buffer and notify callback
     */
    private void flush() {
        if (buffer.length() == 0) {
            return;
        }

        String text = buffer.toString().trim();
        buffer.setLength(0);
        wordCount = 0;
        bufferStartTime = 0;

        if (!text.isEmpty() && callback != null) {
            callback.onFlush(text);
        }
    }

    /**
     * Schedule a delayed flush after batch delay
     */
    private void scheduleDelayedFlush() {
        pendingFlush = () -> {
            synchronized (TextDeltaBuffer.this) {
                if (buffer.length() > 0 && wordCount >= minWords) {
                    Log.d(TAG, "Delayed flush: " + wordCount + " words, " + buffer.length() + " chars");
                    flush();
                }
            }
        };
        handler.postDelayed(pendingFlush, batchDelayMs);
    }

    /**
     * Cancel any pending delayed flush
     */
    private void cancelPendingFlush() {
        if (pendingFlush != null) {
            handler.removeCallbacks(pendingFlush);
            pendingFlush = null;
        }
    }

    /**
     * Count words in a string
     */
    private int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    /**
     * Get current buffer size
     */
    public int getBufferSize() {
        return buffer.length();
    }

    /**
     * Get current word count
     */
    public int getWordCount() {
        return wordCount;
    }

    /**
     * Check if buffer has content
     */
    public boolean hasContent() {
        return buffer.length() > 0;
    }

    /**
     * Cleanup resources
     */
    public void destroy() {
        cancelPendingFlush();
        clear();
        callback = null;
    }
}
