package com.tripandevent.sanbotvoice.functions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.api.ApiClient;
import com.tripandevent.sanbotvoice.api.TripAndEventApi;
import com.tripandevent.sanbotvoice.api.models.ApiResponse;
import com.tripandevent.sanbotvoice.utils.Logger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Function handler for ending the conversation.
 * 
 * This is called when the AI determines the conversation should end,
 * either because the customer said goodbye or the task is complete.
 * 
 * Note: This function signals the app to disconnect, but should still
 * return a result so the AI can say a final goodbye.
 */
public class DisconnectCallFunction implements FunctionHandler {
    
    private static final String TAG = "DisconnectFunction";
    
    /**
     * Listener for disconnect events
     */
    public interface DisconnectListener {
        void onDisconnectRequested(String reason, String summary);
    }
    
    private static DisconnectListener disconnectListener;
    
    /**
     * Set the disconnect listener (should be set by VoiceAgentService)
     */
    public static void setDisconnectListener(DisconnectListener listener) {
        disconnectListener = listener;
    }
    
    @Override
    public void execute(String arguments, String sessionId, FunctionCallback callback) {
        Logger.function("Executing disconnect_call with args: %s", arguments);
        
        try {
            // Parse arguments
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            String reason = args.has("reason") ? 
                args.get("reason").getAsString() : "unknown";
            String summary = args.has("summary") ? 
                args.get("summary").getAsString() : "";
            
            Logger.function("Disconnect requested - reason: %s", reason);
            
            // Log disconnect to backend (don't wait for response)
            logDisconnectToBackend(sessionId, reason, summary);
            
            // Notify listener (VoiceAgentService will handle actual disconnect after AI response)
            if (disconnectListener != null) {
                disconnectListener.onDisconnectRequested(reason, summary);
            }
            
            // Return success immediately so AI can say goodbye
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "Call will end after your final response. " +
                "Please say a brief, friendly goodbye to the customer.");
            callback.onSuccess(result.toString());
            
        } catch (Exception e) {
            Logger.e(e, "Error executing disconnect_call");
            // Still succeed - we want the call to end
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "Ending call. Say goodbye to the customer.");
            callback.onSuccess(result.toString());
        }
    }
    
    /**
     * Log disconnect event to backend
     */
    private void logDisconnectToBackend(String sessionId, String reason, String summary) {
        try {
            // This is fire-and-forget - we don't block on the response
            TripAndEventApi.DisconnectLog log = new TripAndEventApi.DisconnectLog(
                sessionId,
                reason,
                0,  // Duration will be calculated by the service
                0,  // Message count will be set by the service
                summary
            );
            
            ApiClient.getInstance().getApi().logDisconnect(
                ApiClient.getInstance().getAuthHeader(),
                log
            ).enqueue(new Callback<ApiResponse<Void>>() {
                @Override
                public void onResponse(Call<ApiResponse<Void>> call, 
                                      Response<ApiResponse<Void>> response) {
                    if (response.isSuccessful()) {
                        Logger.function("Disconnect logged successfully");
                    } else {
                        Logger.w(TAG, "Failed to log disconnect: %d", response.code());
                    }
                }
                
                @Override
                public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                    Logger.w(TAG, "Failed to log disconnect: %s", t.getMessage());
                }
            });
            
        } catch (Exception e) {
            Logger.w(TAG, "Error logging disconnect: %s", e.getMessage());
        }
    }
}
