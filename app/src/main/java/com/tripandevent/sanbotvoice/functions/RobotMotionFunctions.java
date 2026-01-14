package com.tripandevent.sanbotvoice.functions;

import android.content.Context;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.sanbot.SanbotMotionManager;
import com.tripandevent.sanbotvoice.utils.Logger;

/**
 * Function handlers for robot motion control.
 * These functions are called by the AI to make the robot move.
 */
public class RobotMotionFunctions {
    
    private static final String TAG = "RobotMotion";
    
    private final SanbotMotionManager motionManager;
    
    public RobotMotionFunctions(Context context) {
        this.motionManager = SanbotMotionManager.getInstance(context);
    }
    
    /**
     * Execute a gesture
     */
    public void executeGesture(String arguments, FunctionHandler.FunctionCallback callback) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String gesture = args.has("gesture") ? args.get("gesture").getAsString() : "nod";
            
            Logger.d(TAG, "Executing gesture: %s", gesture);
            
            switch (gesture.toLowerCase()) {
                case "nod":
                    motionManager.nodHead();
                    break;
                case "shake":
                    motionManager.shakeHead();
                    break;
                case "wave":
                    motionManager.waveHand(true);
                    break;
                case "greet":
                    motionManager.greet();
                    break;
                case "thinking":
                    motionManager.showThinking();
                    break;
                case "agree":
                    motionManager.showAgreement();
                    break;
                case "disagree":
                    motionManager.showDisagreement();
                    break;
                case "excited":
                    motionManager.showExcitement();
                    break;
                case "listening":
                    motionManager.showListening();
                    break;
                case "goodbye":
                    motionManager.sayGoodbye();
                    break;
                case "acknowledge":
                    motionManager.acknowledge();
                    break;
                default:
                    motionManager.nodHead();
                    break;
            }
            
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("gesture", gesture);
            callback.onSuccess(result.toString());
            
        } catch (Exception e) {
            Logger.e(e, "Error executing gesture");
            callback.onError(e.getMessage());
        }
    }
    
    /**
     * Show emotion
     */
    public void showEmotion(String arguments, FunctionHandler.FunctionCallback callback) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String emotion = args.has("emotion") ? args.get("emotion").getAsString() : "normal";
            
            Logger.d(TAG, "Showing emotion: %s", emotion);
            motionManager.showEmotion(emotion);
            
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("emotion", emotion);
            callback.onSuccess(result.toString());
            
        } catch (Exception e) {
            Logger.e(e, "Error showing emotion");
            callback.onError(e.getMessage());
        }
    }
    
    /**
     * Move head/look direction
     */
    public void moveHead(String arguments, FunctionHandler.FunctionCallback callback) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String direction = args.has("direction") ? args.get("direction").getAsString() : "center";
            
            Logger.d(TAG, "Looking: %s", direction);
            motionManager.lookAt(direction);
            
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("direction", direction);
            callback.onSuccess(result.toString());
            
        } catch (Exception e) {
            Logger.e(e, "Error moving head");
            callback.onError(e.getMessage());
        }
    }
    
    /**
     * Move hands/wings
     */
    public void moveHands(String arguments, FunctionHandler.FunctionCallback callback) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String action = args.has("action") ? args.get("action").getAsString() : "wave";
            boolean rightHand = !args.has("hand") || "right".equals(args.get("hand").getAsString());
            
            Logger.d(TAG, "Hand action: %s", action);
            
            switch (action.toLowerCase()) {
                case "wave":
                    motionManager.waveHand(rightHand);
                    break;
                case "raise":
                    motionManager.raiseBothHands();
                    break;
                default:
                    motionManager.waveHand(rightHand);
                    break;
            }
            
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("action", action);
            callback.onSuccess(result.toString());
            
        } catch (Exception e) {
            Logger.e(e, "Error moving hands");
            callback.onError(e.getMessage());
        }
    }
    
    /**
     * Move body
     */
    public void moveBody(String arguments, FunctionHandler.FunctionCallback callback) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String action = args.has("action") ? args.get("action").getAsString() : "wiggle";
            
            Logger.d(TAG, "Body action: %s", action);
            
            switch (action.toLowerCase()) {
                case "turn_left":
                    motionManager.smallTurn(false);
                    break;
                case "turn_right":
                    motionManager.smallTurn(true);
                    break;
                case "wiggle":
                    motionManager.wiggle();
                    break;
                default:
                    motionManager.wiggle();
                    break;
            }
            
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("action", action);
            callback.onSuccess(result.toString());
            
        } catch (Exception e) {
            Logger.e(e, "Error moving body");
            callback.onError(e.getMessage());
        }
    }
}