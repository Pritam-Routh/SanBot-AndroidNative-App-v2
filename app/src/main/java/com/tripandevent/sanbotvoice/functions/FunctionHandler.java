package com.tripandevent.sanbotvoice.functions;

/**
 * Interface for function handlers.
 * Each function that can be called by the AI implements this interface.
 */
public interface FunctionHandler {
    
    /**
     * Callback for function execution results
     */
    interface FunctionCallback {
        /**
         * Called when function execution succeeds
         * @param result JSON result to send back to AI
         */
        void onSuccess(String result);
        
        /**
         * Called when function execution fails
         * @param error Error message
         */
        void onError(String error);
    }
    
    /**
     * Execute the function
     * 
     * @param arguments JSON string of arguments from AI
     * @param sessionId Current session ID
     * @param callback Callback for result
     */
    void execute(String arguments, String sessionId, FunctionCallback callback);
}