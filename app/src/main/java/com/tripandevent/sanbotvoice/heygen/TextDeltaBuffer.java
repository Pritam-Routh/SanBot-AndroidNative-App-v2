package com.tripandevent.sanbotvoice.heygen;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

/**
 * TextDeltaBuffer - Dynamic text batching for natural avatar speech
 *
 * Mimics OpenAI Realtime's response behavior:
 * 1. FIRST FLUSH: Send quickly (3-4 words) to start avatar speaking immediately
 * 2. SUBSEQUENT FLUSHES: Wait for sentence boundaries for smooth continuation
 * 3. INTERRUPT: Clear buffer when user starts speaking
 *
 * This creates a natural conversation flow:
 * - Avatar starts responding quickly (low latency for first words)
 * - Speech continues smoothly (sentence-based batching)
 * - User can interrupt anytime (buffer clears, avatar stops)
 */
public class TextDeltaBuffer {
    private static final String TAG = "TextDeltaBuffer";

    // Pattern to detect sentence boundaries
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]\\s*$");
    // Pattern to detect clause boundaries (comma, semicolon, colon, dash)
    private static final Pattern CLAUSE_END = Pattern.compile("[,;:\\-]\\s*$");

    // Buffer state
    private final StringBuilder buffer = new StringBuilder();
    private int wordCount = 0;
    private long bufferStartTime = 0;

    // Dynamic batching state
    private boolean isFirstFlush = true;  // Track if this is the first flush of a new response
    private int totalWordsFlushed = 0;    // Track total words flushed in current response

    // Configuration - First flush (eager, low latency)
    private static final int FIRST_FLUSH_MIN_WORDS = 3;      // Start speaking after just 3 words
    private static final int FIRST_FLUSH_MAX_DELAY_MS = 150; // Max 150ms wait for first flush

    // Configuration - Subsequent flushes (sentence-based, smooth)
    private final int minWords;           // Min words for mid-sentence flush
    private final int batchDelayMs;       // Delay before mid-sentence flush
    private final int maxBufferSize;      // Force flush if buffer too large
    private final int maxDelayMs;         // Force flush after max delay

    // Handler for delayed flush
    private final Handler handler;
    private Runnable pendingFlush;
    private Runnable firstFlushTimeout;

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
     * Reset for a new response
     * Call this when a new AI response starts (e.g., response.created event)
     */
    public synchronized void resetForNewResponse() {
        clear();
        isFirstFlush = true;
        totalWordsFlushed = 0;
        Log.d(TAG, "Reset for new response - ready for eager first flush");
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

        // Count words in delta
        wordCount += countWords(delta);

        // Cancel any pending flush
        cancelPendingFlush();
        cancelFirstFlushTimeout();

        // Check if we should flush
        boolean shouldFlush = false;
        String reason = null;

        if (isFirstFlush) {
            // FIRST FLUSH: Eager strategy - send quickly to start avatar speaking
            if (wordCount >= FIRST_FLUSH_MIN_WORDS) {
                // Check for natural break point (sentence or clause end)
                if (SENTENCE_END.matcher(buffer).find()) {
                    shouldFlush = true;
                    reason = "first flush (sentence)";
                } else if (CLAUSE_END.matcher(buffer).find()) {
                    shouldFlush = true;
                    reason = "first flush (clause)";
                } else if (wordCount >= FIRST_FLUSH_MIN_WORDS + 2) {
                    // If no natural break, flush after a few more words
                    shouldFlush = true;
                    reason = "first flush (word count)";
                } else {
                    // Schedule timeout for first flush
                    scheduleFirstFlushTimeout();
                }
            } else {
                // Schedule timeout in case words come slowly
                scheduleFirstFlushTimeout();
            }
        } else {
            // SUBSEQUENT FLUSHES: Sentence-based strategy for smooth speech

            // Check for sentence boundary - primary trigger
            if (SENTENCE_END.matcher(buffer).find()) {
                shouldFlush = true;
                reason = "sentence boundary";
            }
            // Check max buffer size - prevent overflow
            else if (buffer.length() >= maxBufferSize) {
                shouldFlush = true;
                reason = "max buffer size";
            }
            // Check max delay - ensure eventual flush
            else if (now - bufferStartTime >= maxDelayMs) {
                shouldFlush = true;
                reason = "max delay timeout";
            }
            // Check for clause boundary with enough words
            else if (CLAUSE_END.matcher(buffer).find() && wordCount >= minWords) {
                shouldFlush = true;
                reason = "clause boundary";
            }
            // Schedule delayed flush if we have enough words
            else if (wordCount >= minWords) {
                scheduleDelayedFlush();
            }
        }

        if (shouldFlush) {
            Log.d(TAG, "Flushing (" + reason + "): " + wordCount + " words, " + buffer.length() + " chars, firstFlush=" + isFirstFlush);
            flush();
        }
    }

    /**
     * Force flush any remaining buffer content
     * Call this when response is complete
     */
    public synchronized void forceFlush() {
        cancelPendingFlush();
        cancelFirstFlushTimeout();
        if (buffer.length() > 0) {
            Log.d(TAG, "Force flushing: " + wordCount + " words, " + buffer.length() + " chars");
            flush();
        }
    }

    /**
     * Clear the buffer without flushing (for interrupts)
     * Call this when user starts speaking to interrupt the avatar
     */
    public synchronized void clear() {
        cancelPendingFlush();
        cancelFirstFlushTimeout();
        buffer.setLength(0);
        wordCount = 0;
        bufferStartTime = 0;
    }

    /**
     * Interrupt the current response
     * Clears buffer and resets state for next response
     */
    public synchronized void interrupt() {
        Log.d(TAG, "Interrupt - clearing buffer and resetting state");
        clear();
        isFirstFlush = true;
        totalWordsFlushed = 0;
    }

    /**
     * Flush the buffer and notify callback
     */
    private void flush() {
        if (buffer.length() == 0) {
            return;
        }

        String text = buffer.toString().trim();
        totalWordsFlushed += wordCount;

        buffer.setLength(0);
        wordCount = 0;
        bufferStartTime = 0;

        // After first flush, switch to sentence-based strategy
        if (isFirstFlush) {
            isFirstFlush = false;
            Log.d(TAG, "First flush complete - switching to sentence-based batching");
        }

        if (!text.isEmpty() && callback != null) {
            callback.onFlush(text);
        }
    }

    /**
     * Schedule timeout for first flush
     * Ensures we don't wait too long for the first words
     */
    private void scheduleFirstFlushTimeout() {
        if (firstFlushTimeout != null) return; // Already scheduled

        firstFlushTimeout = () -> {
            synchronized (TextDeltaBuffer.this) {
                if (isFirstFlush && buffer.length() > 0 && wordCount >= 2) {
                    Log.d(TAG, "First flush timeout: " + wordCount + " words");
                    flush();
                }
            }
        };
        handler.postDelayed(firstFlushTimeout, FIRST_FLUSH_MAX_DELAY_MS);
    }

    /**
     * Cancel first flush timeout
     */
    private void cancelFirstFlushTimeout() {
        if (firstFlushTimeout != null) {
            handler.removeCallbacks(firstFlushTimeout);
            firstFlushTimeout = null;
        }
    }

    /**
     * Schedule a delayed flush after batch delay
     */
    private void scheduleDelayedFlush() {
        if (pendingFlush != null) return; // Already scheduled

        pendingFlush = () -> {
            synchronized (TextDeltaBuffer.this) {
                if (buffer.length() > 0 && wordCount >= minWords) {
                    Log.d(TAG, "Delayed flush: " + wordCount + " words");
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
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
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
     * Check if waiting for first flush
     */
    public boolean isWaitingForFirstFlush() {
        return isFirstFlush;
    }

    /**
     * Get total words flushed in current response
     */
    public int getTotalWordsFlushed() {
        return totalWordsFlushed;
    }

    /**
     * Cleanup resources
     */
    public void destroy() {
        cancelPendingFlush();
        cancelFirstFlushTimeout();
        clear();
        callback = null;
    }
}
