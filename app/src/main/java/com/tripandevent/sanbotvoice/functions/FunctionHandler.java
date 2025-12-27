package com.tripandevent.sanbotvoice.functions;

/**
 * Interface for function handlers.
 * Each function that the AI can call must implement this interface.
 */
public interface FunctionHandler {
    
    /**
     * Execute the function with the given arguments.
     * 
     * @param arguments JSON string of function arguments from the AI
     * @param sessionId Current session ID
     * @param callback Callback for async result
     */
    void execute(String arguments, String sessionId, FunctionCallback callback);
    
    /**
     * Callback interface for function results
     */
    interface FunctionCallback {
        /**
         * Called when function completes successfully
         * @param result JSON string result to send back to AI
         */
        void onSuccess(String result);
        
        /**
         * Called when function fails
         * @param error Error message
         */
        void onError(String error);
    }
}
