package com.tripandevent.sanbotvoice.sanbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manager for Sanbot robot motion control.
 * Uses reflection to call SDK methods to avoid compile-time dependency.
 * 
 * Supports: Head motion, Wing motion, Wheel motion, Emotions
 */
public class SanbotMotionManager {
    
    private static final String TAG = "SanbotMotion";
    
    private static SanbotMotionManager instance;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final ReentrantLock motionLock;
    
    // SDK availability
    private boolean sdkAvailable = false;
    private boolean initialized = false;
    
    // SDK manager instances (loaded via reflection)
    private Object headMotionManager;
    private Object wingMotionManager;
    private Object wheelMotionManager;
    private Object systemManager;
    
    // Motion limits
    private static final int HEAD_HORIZONTAL_MIN = -45;
    private static final int HEAD_HORIZONTAL_MAX = 45;
    private static final int HEAD_VERTICAL_MIN = -15;
    private static final int HEAD_VERTICAL_MAX = 15;
    private static final int WING_ANGLE_MIN = 0;
    private static final int WING_ANGLE_MAX = 180;
    
    private SanbotMotionManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.motionLock = new ReentrantLock();
        
        // Try to initialize SDK
        initializeSdk();
    }
    
    public static synchronized SanbotMotionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SanbotMotionManager(context);
        }
        return instance;
    }
    
    /**
     * Initialize Sanbot SDK via reflection
     */
    private void initializeSdk() {
        try {
            // Try to load SDK classes
            Class<?> headManagerClass = Class.forName("com.qihancloud.opensdk.beans.FuncConstant");
            sdkAvailable = true;
            Logger.i(TAG, "Sanbot SDK available");
        } catch (ClassNotFoundException e) {
            sdkAvailable = false;
            Logger.w(TAG, "Sanbot SDK not available - running in simulation mode");
        }
    }
    
    /**
     * Check if SDK is available
     */
    public boolean isSdkAvailable() {
        return sdkAvailable;
    }
    
    /**
     * Check if motion manager is available and ready
     * This is the method VoiceAgentService expects
     */
    public boolean isAvailable() {
        return sdkAvailable || true; // Return true to allow simulation mode
    }
    
    /**
     * Initialize with SDK managers
     * Called by VoiceAgentService when SDK is ready
     */
    public void initialize(Object headManager, Object wingManager, Object wheelManager, Object sysManager) {
        this.headMotionManager = headManager;
        this.wingMotionManager = wingManager;
        this.wheelMotionManager = wheelManager;
        this.systemManager = sysManager;
        this.initialized = true;
        Logger.i(TAG, "SanbotMotionManager initialized with SDK managers");
    }
    
    /**
     * Release resources
     * Called by VoiceAgentService on cleanup
     */
    public void release() {
        Logger.i(TAG, "Releasing SanbotMotionManager resources");
        this.headMotionManager = null;
        this.wingMotionManager = null;
        this.wheelMotionManager = null;
        this.systemManager = null;
        this.initialized = false;
    }
    
    /**
     * Inject SDK managers (called from SanbotSdkHelper when SDK is initialized)
     */
    public void setHeadMotionManager(Object manager) {
        this.headMotionManager = manager;
    }
    
    public void setWingMotionManager(Object manager) {
        this.wingMotionManager = manager;
    }
    
    public void setWheelMotionManager(Object manager) {
        this.wheelMotionManager = manager;
    }
    
    public void setSystemManager(Object manager) {
        this.systemManager = manager;
    }
    
    // ============================================
    // HEAD MOTIONS
    // ============================================
    
    /**
     * Nod head up and down (agreement)
     */
    public void nodHead() {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: nodHead");
            if (headMotionManager != null) {
                moveHeadRelative(0, 10, 3);
                sleep(200);
                moveHeadRelative(0, -20, 3);
                sleep(200);
                moveHeadRelative(0, 10, 3);
            } else {
                Logger.d(TAG, "[SIM] Nodding head");
            }
        });
    }
    
    /**
     * Shake head left and right (disagreement)
     */
    public void shakeHead() {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: shakeHead");
            if (headMotionManager != null) {
                moveHeadRelative(15, 0, 3);
                sleep(200);
                moveHeadRelative(-30, 0, 3);
                sleep(200);
                moveHeadRelative(15, 0, 3);
            } else {
                Logger.d(TAG, "[SIM] Shaking head");
            }
        });
    }
    
    /**
     * Tilt head to one side (curious)
     */
    public void tiltHead(boolean toRight) {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: tiltHead (right=%b)", toRight);
            int angle = toRight ? 15 : -15;
            if (headMotionManager != null) {
                moveHeadRelative(angle, 5, 2);
            } else {
                Logger.d(TAG, "[SIM] Tilting head %s", toRight ? "right" : "left");
            }
        });
    }
    
    /**
     * Look in a direction
     */
    public void lookAt(String direction) {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: lookAt %s", direction);
            int horizontal = 0;
            int vertical = 0;
            
            switch (direction.toLowerCase()) {
                case "left":
                    horizontal = -30;
                    break;
                case "right":
                    horizontal = 30;
                    break;
                case "up":
                    vertical = 10;
                    break;
                case "down":
                    vertical = -10;
                    break;
                case "center":
                default:
                    horizontal = 0;
                    vertical = 0;
                    break;
            }
            
            if (headMotionManager != null) {
                moveHeadAbsolute(horizontal, vertical, 3);
            } else {
                Logger.d(TAG, "[SIM] Looking %s", direction);
            }
        });
    }
    
    /**
     * Move head relative to current position
     */
    private void moveHeadRelative(int horizontal, int vertical, int speed) {
        if (headMotionManager == null) return;
        
        try {
            Class<?> motionClass = Class.forName("com.qihancloud.opensdk.function.beans.headmotion.RelativeAngleHeadMotion");
            Object motion = motionClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(
                            clamp(horizontal, HEAD_HORIZONTAL_MIN, HEAD_HORIZONTAL_MAX),
                            clamp(vertical, HEAD_VERTICAL_MIN, HEAD_VERTICAL_MAX),
                            clamp(speed, 1, 10));
            
            headMotionManager.getClass().getMethod("doAbsoluteLocateMotion", motionClass)
                    .invoke(headMotionManager, motion);
        } catch (Exception e) {
            Logger.e(e, "Failed to move head relative");
        }
    }
    
    /**
     * Move head to absolute position
     */
    private void moveHeadAbsolute(int horizontal, int vertical, int speed) {
        if (headMotionManager == null) return;
        
        try {
            Class<?> motionClass = Class.forName("com.qihancloud.opensdk.function.beans.headmotion.AbsoluteAngleHeadMotion");
            Object motion = motionClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(
                            clamp(horizontal, HEAD_HORIZONTAL_MIN, HEAD_HORIZONTAL_MAX),
                            clamp(vertical, HEAD_VERTICAL_MIN, HEAD_VERTICAL_MAX),
                            clamp(speed, 1, 10));
            
            headMotionManager.getClass().getMethod("doAbsoluteLocateMotion", motionClass)
                    .invoke(headMotionManager, motion);
        } catch (Exception e) {
            Logger.e(e, "Failed to move head absolute");
        }
    }
    
    // ============================================
    // WING/HAND MOTIONS
    // ============================================
    
    /**
     * Wave hand (greeting)
     */
    public void waveHand(boolean rightHand) {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: waveHand (right=%b)", rightHand);
            if (wingMotionManager != null) {
                int hand = rightHand ? 1 : 0; // 0=left, 1=right
                moveWing(hand, 120, 4);
                sleep(300);
                moveWing(hand, 80, 4);
                sleep(300);
                moveWing(hand, 120, 4);
                sleep(300);
                moveWing(hand, 0, 4);
            } else {
                Logger.d(TAG, "[SIM] Waving %s hand", rightHand ? "right" : "left");
            }
        });
    }
    
    /**
     * Raise both hands (excitement)
     */
    public void raiseBothHands() {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: raiseBothHands");
            if (wingMotionManager != null) {
                moveWing(2, 150, 5); // 2 = both
                sleep(500);
                moveWing(2, 0, 3);
            } else {
                Logger.d(TAG, "[SIM] Raising both hands");
            }
        });
    }
    
    /**
     * Move wing/arm
     */
    private void moveWing(int wing, int angle, int speed) {
        if (wingMotionManager == null) return;
        
        try {
            Class<?> motionClass = Class.forName("com.qihancloud.opensdk.function.beans.wingmotion.RelativeAngleWingMotion");
            Object motion = motionClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(wing, clamp(angle, WING_ANGLE_MIN, WING_ANGLE_MAX), clamp(speed, 1, 8));
            
            wingMotionManager.getClass().getMethod("doRelativeAngleMotion", motionClass)
                    .invoke(wingMotionManager, motion);
        } catch (Exception e) {
            Logger.e(e, "Failed to move wing");
        }
    }
    
    // ============================================
    // BODY MOTIONS
    // ============================================
    
    /**
     * Small turn (attention)
     */
    public void smallTurn(boolean toRight) {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: smallTurn (right=%b)", toRight);
            if (wheelMotionManager != null) {
                int angle = toRight ? 15 : -15;
                turnBody(angle, 2);
            } else {
                Logger.d(TAG, "[SIM] Small turn %s", toRight ? "right" : "left");
            }
        });
    }
    
    /**
     * Wiggle (excitement)
     */
    public void wiggle() {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: wiggle");
            if (wheelMotionManager != null) {
                turnBody(10, 3);
                sleep(200);
                turnBody(-20, 3);
                sleep(200);
                turnBody(10, 3);
            } else {
                Logger.d(TAG, "[SIM] Wiggling");
            }
        });
    }
    
    /**
     * Turn body
     */
    private void turnBody(int angle, int speed) {
        if (wheelMotionManager == null) return;
        
        try {
            Class<?> motionClass = Class.forName("com.qihancloud.opensdk.function.beans.wheelmotion.NoAngleWheelMotion");
            Object motion = motionClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(0, clamp(speed, 1, 10), angle > 0 ? 1 : 0);
            
            wheelMotionManager.getClass().getMethod("doNoAngleMotion", motionClass)
                    .invoke(wheelMotionManager, motion);
            
            sleep(Math.abs(angle) * 20);
            
            // Stop
            wheelMotionManager.getClass().getMethod("doNoAngleMotion", motionClass)
                    .invoke(wheelMotionManager, motionClass.getConstructor(int.class, int.class, int.class)
                            .newInstance(0, 0, 0));
        } catch (Exception e) {
            Logger.e(e, "Failed to turn body");
        }
    }
    
    // ============================================
    // EMOTIONS
    // ============================================
    
    /**
     * Show emotion on face display
     */
    public void showEmotion(String emotion) {
        executeMotion(() -> {
            Logger.d(TAG, "Executing: showEmotion %s", emotion);
            if (systemManager != null) {
                try {
                    int emotionId = getEmotionId(emotion);
                    systemManager.getClass().getMethod("showEmotion", int.class)
                            .invoke(systemManager, emotionId);
                } catch (Exception e) {
                    Logger.e(e, "Failed to show emotion");
                }
            } else {
                Logger.d(TAG, "[SIM] Showing emotion: %s", emotion);
            }
        });
    }
    
    private int getEmotionId(String emotion) {
        switch (emotion.toLowerCase()) {
            case "happy": return 1;
            case "excited": return 2;
            case "thinking": return 3;
            case "curious": return 4;
            case "shy": return 5;
            case "laugh": return 6;
            case "goodbye": return 7;
            case "normal":
            default: return 0;
        }
    }
    
    // ============================================
    // COMBINED GESTURES
    // ============================================
    
    /**
     * Greeting gesture (wave + look + emotion)
     */
    public void greet() {
        showEmotion("happy");
        waveHand(true);
        nodHead();
    }
    
    /**
     * Thinking gesture
     */
    public void showThinking() {
        showEmotion("thinking");
        tiltHead(true);
    }
    
    /**
     * Agreement gesture
     */
    public void showAgreement() {
        showEmotion("happy");
        nodHead();
    }
    
    /**
     * Disagreement gesture
     */
    public void showDisagreement() {
        shakeHead();
    }
    
    /**
     * Excitement gesture
     */
    public void showExcitement() {
        showEmotion("excited");
        raiseBothHands();
        wiggle();
    }
    
    /**
     * Listening gesture
     */
    public void showListening() {
        showEmotion("curious");
        tiltHead(true);
    }
    
    /**
     * Goodbye gesture
     */
    public void sayGoodbye() {
        showEmotion("goodbye");
        waveHand(true);
    }
    
    /**
     * Acknowledge gesture (subtle nod)
     */
    public void acknowledge() {
        nodHead();
    }
    
    /**
     * Reset all to neutral position
     */
    public void resetAll() {
        executeMotion(() -> {
            Logger.d(TAG, "Resetting to neutral");
            showEmotion("normal");
            lookAt("center");
            if (wingMotionManager != null) {
                moveWing(2, 0, 3);
            }
        });
    }
    
    // ============================================
    // UTILITY METHODS
    // ============================================
    
    /**
     * Execute motion on background thread with lock
     */
    private void executeMotion(Runnable motion) {
        executor.execute(() -> {
            motionLock.lock();
            try {
                motion.run();
            } finally {
                motionLock.unlock();
            }
        });
    }
    
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    
    /**
     * Shutdown the motion manager
     */
    public void shutdown() {
        executor.shutdown();
    }
}