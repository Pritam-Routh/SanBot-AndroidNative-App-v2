package com.tripandevent.sanbotvoice.utils;

import android.util.Log;
import com.tripandevent.sanbotvoice.BuildConfig;
import timber.log.Timber;

/**
 * Centralized logging utility.
 * Uses Timber for enhanced logging with automatic tag generation.
 * 
 * In debug builds: All logs are output
 * In release builds: Only warnings and errors are output
 */
public final class Logger {
    
    private static boolean isInitialized = false;
    
    private Logger() {
        // Prevent instantiation
    }
    
    /**
     * Initialize the logger. Call this once in Application.onCreate()
     */
    public static synchronized void init() {
        if (isInitialized) {
            return;
        }
        
        if (BuildConfig.ENABLE_DEBUG_LOGGING) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new ReleaseTree());
        }
        
        isInitialized = true;
        Timber.d("Logger initialized. Debug logging: %s", BuildConfig.ENABLE_DEBUG_LOGGING);
    }
    
    // ============================================
    // VERBOSE
    // ============================================
    
    public static void v(String message) {
        Timber.v(message);
    }
    
    public static void v(String message, Object... args) {
        Timber.v(message, args);
    }
    
    public static void v(String tag, String message) {
        Timber.tag(tag).v(message);
    }
    
    public static void v(String tag, String message, Object... args) {
        Timber.tag(tag).v(message, args);
    }
    
    // ============================================
    // DEBUG
    // ============================================
    
    public static void d(String message) {
        Timber.d(message);
    }
    
    public static void d(String message, Object... args) {
        Timber.d(message, args);
    }
    
    public static void d(String tag, String message) {
        Timber.tag(tag).d(message);
    }
    
    public static void d(String tag, String message, Object... args) {
        Timber.tag(tag).d(message, args);
    }
    
    // ============================================
    // INFO
    // ============================================
    
    public static void i(String message) {
        Timber.i(message);
    }
    
    public static void i(String message, Object... args) {
        Timber.i(message, args);
    }
    
    public static void i(String tag, String message) {
        Timber.tag(tag).i(message);
    }
    
    public static void i(String tag, String message, Object... args) {
        Timber.tag(tag).i(message, args);
    }
    
    // ============================================
    // WARNING
    // ============================================
    
    public static void w(String message) {
        Timber.w(message);
    }
    
    public static void w(String message, Object... args) {
        Timber.w(message, args);
    }
    
    public static void w(String tag, String message) {
        Timber.tag(tag).w(message);
    }
    
    public static void w(String tag, String message, Object... args) {
        Timber.tag(tag).w(message, args);
    }
    
    public static void w(Throwable t, String message) {
        Timber.w(t, message);
    }
    
    // ============================================
    // ERROR
    // ============================================
    
    public static void e(String message) {
        Timber.e(message);
    }
    
    public static void e(String message, Object... args) {
        Timber.e(message, args);
    }
    
    public static void e(String tag, String message) {
        Timber.tag(tag).e(message);
    }
    
    public static void e(String tag, String message, Object... args) {
        Timber.tag(tag).e(message, args);
    }
    
    public static void e(Throwable t) {
        Timber.e(t);
    }
    
    public static void e(Throwable t, String message) {
        Timber.e(t, message);
    }
    
    public static void e(Throwable t, String message, Object... args) {
        Timber.e(t, message, args);
    }
    
    // ============================================
    // WTF (What a Terrible Failure)
    // ============================================
    
    public static void wtf(String message) {
        Timber.wtf(message);
    }
    
    public static void wtf(Throwable t, String message) {
        Timber.wtf(t, message);
    }
    
    // ============================================
    // SPECIALIZED LOGGING
    // ============================================
    
    /**
     * Log WebRTC-related events
     */
    public static void webrtc(String message, Object... args) {
        Timber.tag(Constants.TAG_WEBRTC).d(message, args);
    }
    
    /**
     * Log OpenAI API events
     */
    public static void openai(String message, Object... args) {
        Timber.tag(Constants.TAG_OPENAI).d(message, args);
    }
    
    /**
     * Log Sanbot SDK events
     */
    public static void sanbot(String message, Object... args) {
        Timber.tag(Constants.TAG_SANBOT).d(message, args);
    }
    
    /**
     * Log audio processing events
     */
    public static void audio(String message, Object... args) {
        Timber.tag(Constants.TAG_AUDIO).d(message, args);
    }
    
    /**
     * Log function call events
     */
    public static void function(String message, Object... args) {
        Timber.tag(Constants.TAG_FUNCTION).d(message, args);
    }
    
    /**
     * Log JSON events (with pretty printing in debug mode)
     */
    public static void json(String tag, String label, String json) {
        if (BuildConfig.ENABLE_DEBUG_LOGGING) {
            Timber.tag(tag).d("%s: %s", label, json);
        }
    }
    
    // ============================================
    // RELEASE TREE
    // ============================================
    
    /**
     * Tree for release builds - only logs warnings and errors
     */
    private static class ReleaseTree extends Timber.Tree {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            // Only log warnings and errors in release
            if (priority < Log.WARN) {
                return;
            }
            
            // Use standard Android logging in release
            if (t != null) {
                Log.println(priority, tag != null ? tag : "SanbotVoice", message + "\n" + Log.getStackTraceString(t));
            } else {
                Log.println(priority, tag != null ? tag : "SanbotVoice", message);
            }
            
            // TODO: In production, consider sending errors to a crash reporting service
            // Example: FirebaseCrashlytics.getInstance().recordException(t);
        }
    }
}
