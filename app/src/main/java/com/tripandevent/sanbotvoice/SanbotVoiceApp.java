package com.tripandevent.sanbotvoice;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

/**
 * Application class for Sanbot Voice Agent.
 * Handles global initialization and lifecycle.
 */
public class SanbotVoiceApp extends Application {
    
    private static SanbotVoiceApp instance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // Initialize logging first
        Logger.init();
        Logger.i("SanbotVoiceApp", "Application starting...");
        
        // Create notification channel for foreground service
        createNotificationChannel();
        
        // Log device info for debugging
        logDeviceInfo();
        
        Logger.i("SanbotVoiceApp", "Application initialized successfully");
    }
    
    /**
     * Get application instance
     */
    public static SanbotVoiceApp getInstance() {
        return instance;
    }
    
    /**
     * Create notification channel for Android O+
     * Required for foreground service notification
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW  // Low importance = no sound
            );
            channel.setDescription("Voice agent active conversation status");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Logger.d("SanbotVoiceApp", "Notification channel created");
            }
        }
    }
    
    /**
     * Log device information for debugging purposes
     */
    private void logDeviceInfo() {
        Logger.d("SanbotVoiceApp", "Device Info:");
        Logger.d("SanbotVoiceApp", "  Manufacturer: %s", Build.MANUFACTURER);
        Logger.d("SanbotVoiceApp", "  Model: %s", Build.MODEL);
        Logger.d("SanbotVoiceApp", "  Device: %s", Build.DEVICE);
        Logger.d("SanbotVoiceApp", "  Android Version: %s (SDK %d)", Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        Logger.d("SanbotVoiceApp", "  App Version: %s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        
        // Check if this looks like a Sanbot device
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        if (manufacturer.contains("sanbot") || model.contains("sanbot") || 
            model.contains("elf") || model.contains("s5")) {
            Logger.i("SanbotVoiceApp", "Running on Sanbot device");
        } else {
            Logger.w("SanbotVoiceApp", "Not running on Sanbot device - some features may not work");
        }
    }
    
    @Override
    public void onTerminate() {
        Logger.i("SanbotVoiceApp", "Application terminating...");
        super.onTerminate();
    }
    
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Logger.w("SanbotVoiceApp", "Low memory warning received");
    }
    
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Logger.d("SanbotVoiceApp", "Trim memory level: %d", level);
    }
}
