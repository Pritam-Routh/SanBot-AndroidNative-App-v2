package com.tripandevent.sanbotvoice.functions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tripandevent.sanbotvoice.openai.events.ServerEvents;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executor for handling function calls from the AI.
 * Routes function calls to appropriate handlers.
 */
public class FunctionExecutor {
    
    private static final String TAG = "FunctionExecutor";
    
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Function handlers
    private CrmFunctionHandlers crmHandlers;
    private RobotMotionFunctions robotMotionFunctions;
    
    // Context for handlers that need it
    private Context context;
    
    public interface ExecutionCallback {
        void onFunctionResult(String callId, String result);
        void onFunctionError(String callId, String error);
    }
    
    public FunctionExecutor() {
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.crmHandlers = new CrmFunctionHandlers();
    }
    
    /**
     * Set context for handlers that need Android context
     */
    public void setContext(Context context) {
        this.context = context.getApplicationContext();
        this.robotMotionFunctions = new RobotMotionFunctions(context);
    }
    
    /**
     * Handle function call from server event
     */
    public void handleFunctionCall(ServerEvents.ParsedEvent event, String sessionId,
                                   ExecutionCallback callback) {
        ServerEvents.ResponseInfo responseInfo = event.getResponseInfo();
        if (responseInfo == null || !responseInfo.hasFunctionCall()) {
            Logger.w(TAG, "No function call found in event");
            return;
        }
        
        ServerEvents.OutputItem functionCall = responseInfo.getFunctionCall();
        executeFunctionCall(
            functionCall.callId,
            functionCall.name,
            functionCall.arguments,
            sessionId,
            callback
        );
    }
    
    /**
     * Execute a function call by name
     */
    public void executeFunctionCall(String callId, String functionName, String arguments,
                                    String sessionId, ExecutionCallback callback) {
        
        Logger.function("Executing: %s (callId: %s)", functionName, callId);
        Logger.function("Arguments: %s", arguments);
        
        executor.execute(() -> {
            try {
                FunctionHandler.FunctionCallback funcCallback = new FunctionHandler.FunctionCallback() {
                    @Override
                    public void onSuccess(String result) {
                        Logger.function("Function %s completed", functionName);
                        notifyResult(callback, callId, result);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Logger.e(TAG, "Function %s failed: %s", functionName, error);
                        String errorResult = "{\"success\":false,\"error\":\"" + 
                            error.replace("\"", "\\\"") + "\"}";
                        notifyResult(callback, callId, errorResult);
                    }
                };
                
                switch (functionName) {
                    // CRM Functions
                    case "save_customer_lead":
                        crmHandlers.saveCustomerLead(arguments, sessionId, funcCallback);
                        break;
                        
                    case "get_packages":
                        crmHandlers.getPackages(arguments, sessionId, funcCallback);
                        break;
                        
                    case "get_package_details":
                        crmHandlers.getPackageDetails(arguments, sessionId, funcCallback);
                        break;
                        
                    case "search_packages":
                        crmHandlers.searchPackages(arguments, sessionId, funcCallback);
                        break;
                    
                    // Robot Motion Functions
                    case "robot_gesture":
                        if (robotMotionFunctions != null) {
                            robotMotionFunctions.executeGesture(arguments, funcCallback);
                        } else {
                            funcCallback.onSuccess("{\"success\":true,\"message\":\"Robot motion not available\"}");
                        }
                        break;
                        
                    case "robot_emotion":
                        if (robotMotionFunctions != null) {
                            robotMotionFunctions.showEmotion(arguments, funcCallback);
                        } else {
                            funcCallback.onSuccess("{\"success\":true,\"message\":\"Robot motion not available\"}");
                        }
                        break;
                        
                    case "robot_look":
                        if (robotMotionFunctions != null) {
                            robotMotionFunctions.moveHead(arguments, funcCallback);
                        } else {
                            funcCallback.onSuccess("{\"success\":true,\"message\":\"Robot motion not available\"}");
                        }
                        break;
                        
                    case "robot_move_hands":
                        if (robotMotionFunctions != null) {
                            robotMotionFunctions.moveHands(arguments, funcCallback);
                        } else {
                            funcCallback.onSuccess("{\"success\":true,\"message\":\"Robot motion not available\"}");
                        }
                        break;
                        
                    case "robot_move_body":
                        if (robotMotionFunctions != null) {
                            robotMotionFunctions.moveBody(arguments, funcCallback);
                        } else {
                            funcCallback.onSuccess("{\"success\":true,\"message\":\"Robot motion not available\"}");
                        }
                        break;
                    
                    // Disconnect
                    case "disconnect_call":
                        funcCallback.onSuccess("{\"success\":true,\"action\":\"disconnect\"}");
                        break;
                        
                    default:
                        Logger.w(TAG, "Unknown function: %s", functionName);
                        funcCallback.onError("Unknown function: " + functionName);
                        break;
                }
                
            } catch (Exception e) {
                Logger.e(e, "Exception executing function %s", functionName);
                String errorResult = "{\"success\":false,\"error\":\"" + 
                    e.getMessage().replace("\"", "\\\"") + "\"}";
                notifyResult(callback, callId, errorResult);
            }
        });
    }
    
    private void notifyResult(ExecutionCallback callback, String callId, String result) {
        mainHandler.post(() -> callback.onFunctionResult(callId, result));
    }
    
    public void shutdown() {
        executor.shutdown();
    }
}