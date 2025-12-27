package com.tripandevent.sanbotvoice.functions;

import android.os.Handler;
import android.os.Looper;

import com.tripandevent.sanbotvoice.openai.events.ServerEvents;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executor for handling function calls from the AI.
 * 
 * When the AI decides to call a function, this class:
 * 1. Looks up the appropriate handler
 * 2. Executes the function asynchronously
 * 3. Returns the result to be sent back to the AI
 */
public class FunctionExecutor {
    
    private static final String TAG = "FunctionExecutor";
    
    private final FunctionRegistry registry;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    /**
     * Callback for function execution results
     */
    public interface ExecutionCallback {
        /**
         * Called when function execution completes
         * @param callId The function call ID from the AI
         * @param result The result JSON to send back to the AI
         */
        void onFunctionResult(String callId, String result);
        
        /**
         * Called when function execution fails
         * @param callId The function call ID from the AI
         * @param error Error message
         */
        void onFunctionError(String callId, String error);
    }
    
    public FunctionExecutor() {
        this.registry = new FunctionRegistry();
        this.executor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Get the function registry (for session configuration)
     */
    public FunctionRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Handle a function call from a server event
     * 
     * @param event The parsed server event containing function call info
     * @param sessionId Current session ID
     * @param callback Callback for the result
     */
    public void handleFunctionCall(ServerEvents.ParsedEvent event, String sessionId, 
                                   ExecutionCallback callback) {
        
        // Get function call info from response.done event
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
     * Execute a function call
     * 
     * @param callId Function call ID
     * @param functionName Name of the function to call
     * @param arguments JSON string of arguments
     * @param sessionId Current session ID
     * @param callback Callback for the result
     */
    public void executeFunctionCall(String callId, String functionName, String arguments,
                                    String sessionId, ExecutionCallback callback) {
        
        Logger.function("Executing function: %s (callId: %s)", functionName, callId);
        Logger.function("Arguments: %s", arguments);
        
        // Look up handler
        FunctionHandler handler = registry.getHandler(functionName);
        if (handler == null) {
            Logger.e(TAG, "Unknown function: %s", functionName);
            notifyError(callback, callId, "Unknown function: " + functionName);
            return;
        }
        
        // Execute asynchronously
        executor.execute(() -> {
            try {
                handler.execute(arguments, sessionId, new FunctionHandler.FunctionCallback() {
                    @Override
                    public void onSuccess(String result) {
                        Logger.function("Function %s completed successfully", functionName);
                        notifyResult(callback, callId, result);
                    }
                    
                    @Override
                    public void onError(String error) {
                        Logger.e(TAG, "Function %s failed: %s", functionName, error);
                        // Return error as a result so AI can handle gracefully
                        String errorResult = "{\"success\":false,\"error\":\"" + 
                            error.replace("\"", "\\\"") + "\"}";
                        notifyResult(callback, callId, errorResult);
                    }
                });
            } catch (Exception e) {
                Logger.e(e, "Exception executing function %s", functionName);
                String errorResult = "{\"success\":false,\"error\":\"Internal error: " + 
                    e.getMessage().replace("\"", "\\\"") + "\"}";
                notifyResult(callback, callId, errorResult);
            }
        });
    }
    
    /**
     * Notify callback of result on main thread
     */
    private void notifyResult(ExecutionCallback callback, String callId, String result) {
        mainHandler.post(() -> callback.onFunctionResult(callId, result));
    }
    
    /**
     * Notify callback of error on main thread
     */
    private void notifyError(ExecutionCallback callback, String callId, String error) {
        mainHandler.post(() -> callback.onFunctionError(callId, error));
    }
    
    /**
     * Shutdown the executor
     */
    public void shutdown() {
        executor.shutdown();
    }
}
