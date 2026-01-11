package com.tripandevent.sanbotvoice.sanbot;

import android.app.Activity;

import com.tripandevent.sanbotvoice.utils.Logger;

/**
 * Helper class to initialize Sanbot SDK managers.
 * 
 * This class uses reflection to obtain SDK managers, allowing the app
 * to compile and run even when the Sanbot SDK is not available.
 * 
 * When running on a Sanbot robot with SDK, this will provide real managers.
 * When running on other devices, managers will be null and motions will be no-ops.
 */
public class SanbotSdkHelper {
    
    private static final String TAG = "SanbotSdkHelper";
    
    private Object headMotionManager;
    private Object wingMotionManager;
    private Object wheelMotionManager;
    private Object systemManager;
    
    private boolean isInitialized = false;
    private boolean isSdkAvailable = false;
    
    /**
     * Initialize SDK managers from a Sanbot Activity.
     * Call this from an Activity that extends SanbotActivity.
     * 
     * @param activity The activity (should be SanbotActivity or subclass)
     */
    public void initialize(Activity activity) {
        Logger.d(TAG, "Initializing Sanbot SDK helpers...");
        
        try {
            // Check if this is a SanbotActivity
            Class<?> sanbotActivityClass = Class.forName("com.sanbot.opensdk.base.SanbotActivity");
            
            if (!sanbotActivityClass.isInstance(activity)) {
                Logger.w(TAG, "Activity is not a SanbotActivity, SDK not available");
                isInitialized = true;
                isSdkAvailable = false;
                return;
            }
            
            // Get FuncConstant class for manager constants
            Class<?> funcConstantClass = Class.forName("com.sanbot.opensdk.function.unit.FuncConstant");
            
            // Get getUnitManager method
            java.lang.reflect.Method getUnitManager = sanbotActivityClass.getMethod(
                "getUnitManager", String.class);
            
            // Get manager constants
            String headManagerConst = (String) funcConstantClass.getField("HEADMOTION_MANAGER").get(null);
            String wingManagerConst = (String) funcConstantClass.getField("WINGMOTION_MANAGER").get(null);
            String wheelManagerConst = (String) funcConstantClass.getField("WHEELMOTION_MANAGER").get(null);
            String systemManagerConst = (String) funcConstantClass.getField("SYSTEM_MANAGER").get(null);
            
            // Get managers
            headMotionManager = getUnitManager.invoke(activity, headManagerConst);
            wingMotionManager = getUnitManager.invoke(activity, wingManagerConst);
            wheelMotionManager = getUnitManager.invoke(activity, wheelManagerConst);
            systemManager = getUnitManager.invoke(activity, systemManagerConst);
            
            isSdkAvailable = true;
            isInitialized = true;
            
            Logger.i(TAG, "Sanbot SDK initialized successfully");
            Logger.d(TAG, "HeadManager: %s, WingManager: %s, WheelManager: %s, SystemManager: %s",
                headMotionManager != null, wingMotionManager != null, 
                wheelMotionManager != null, systemManager != null);
            
        } catch (ClassNotFoundException e) {
            Logger.i(TAG, "Sanbot SDK not available (classes not found)");
            isInitialized = true;
            isSdkAvailable = false;
        } catch (Exception e) {
            Logger.e(e, "Error initializing Sanbot SDK");
            isInitialized = true;
            isSdkAvailable = false;
        }
    }
    
    /**
     * Check if SDK is available
     */
    public boolean isSdkAvailable() {
        return isSdkAvailable;
    }
    
    /**
     * Check if initialization is complete
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    public Object getHeadMotionManager() {
        return headMotionManager;
    }
    
    public Object getWingMotionManager() {
        return wingMotionManager;
    }
    
    public Object getWheelMotionManager() {
        return wheelMotionManager;
    }
    
    public Object getSystemManager() {
        return systemManager;
    }
    
    /**
     * Initialize motion manager with obtained SDK managers
     */
    // public void initializeMotionManager(SanbotMotionManager motionManager) {
    //     if (motionManager != null) {
    //         motionManager.initialize(
    //             headMotionManager, 
    //             wingMotionManager, 
    //             wheelMotionManager, 
    //             systemManager
    //         );
    //     }
    // }
}