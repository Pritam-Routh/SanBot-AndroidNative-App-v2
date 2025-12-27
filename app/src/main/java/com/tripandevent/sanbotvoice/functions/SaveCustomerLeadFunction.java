package com.tripandevent.sanbotvoice.functions;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.api.ApiClient;
import com.tripandevent.sanbotvoice.api.TripAndEventApi;
import com.tripandevent.sanbotvoice.api.models.ApiResponse;
import com.tripandevent.sanbotvoice.api.models.CustomerLead;
import com.tripandevent.sanbotvoice.utils.Logger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Function handler for saving customer lead information to CRM.
 * 
 * This is called when the AI collects customer contact information
 * during the conversation.
 */
public class SaveCustomerLeadFunction implements FunctionHandler {
    
    private static final String TAG = "SaveLeadFunction";
    private final Gson gson = new Gson();
    
    @Override
    public void execute(String arguments, String sessionId, FunctionCallback callback) {
        Logger.function("Executing save_customer_lead with args: %s", arguments);
        
        try {
            // Parse arguments
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            // Build customer lead
            CustomerLead.Builder builder = new CustomerLead.Builder()
                .sessionId(sessionId)
                .source("sanbot_voice_agent");
            
            if (args.has("name") && !args.get("name").isJsonNull()) {
                builder.name(args.get("name").getAsString());
            }
            if (args.has("email") && !args.get("email").isJsonNull()) {
                builder.email(args.get("email").getAsString());
            }
            if (args.has("phone") && !args.get("phone").isJsonNull()) {
                builder.phone(args.get("phone").getAsString());
            }
            if (args.has("company") && !args.get("company").isJsonNull()) {
                builder.company(args.get("company").getAsString());
            }
            if (args.has("interests") && !args.get("interests").isJsonNull()) {
                builder.interests(args.get("interests").getAsString());
            }
            if (args.has("notes") && !args.get("notes").isJsonNull()) {
                builder.notes(args.get("notes").getAsString());
            }
            
            CustomerLead lead = builder.build();
            Logger.function("Saving lead: %s", lead);
            
            // Call API
            ApiClient.getInstance().getApi().saveCustomerLead(
                ApiClient.getInstance().getAuthHeader(),
                lead
            ).enqueue(new Callback<ApiResponse<TripAndEventApi.SaveLeadResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<TripAndEventApi.SaveLeadResponse>> call,
                                      Response<ApiResponse<TripAndEventApi.SaveLeadResponse>> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        ApiResponse<TripAndEventApi.SaveLeadResponse> apiResponse = response.body();
                        
                        if (apiResponse.isSuccess()) {
                            TripAndEventApi.SaveLeadResponse data = apiResponse.getData();
                            Logger.function("Lead saved successfully: %s", data.leadId);
                            
                            // Return success result to AI
                            JsonObject result = new JsonObject();
                            result.addProperty("success", true);
                            result.addProperty("leadId", data.leadId);
                            result.addProperty("message", "Customer information saved successfully");
                            callback.onSuccess(result.toString());
                        } else {
                            Logger.e(TAG, "API error: %s", apiResponse.getError());
                            callback.onError("Failed to save customer information");
                        }
                    } else {
                        Logger.e(TAG, "HTTP error: %d", response.code());
                        callback.onError("Server error saving customer information");
                    }
                }
                
                @Override
                public void onFailure(Call<ApiResponse<TripAndEventApi.SaveLeadResponse>> call,
                                     Throwable t) {
                    Logger.e(TAG, "Network error: %s", t.getMessage());
                    callback.onError("Network error saving customer information");
                }
            });
            
        } catch (Exception e) {
            Logger.e(e, "Error executing save_customer_lead");
            callback.onError("Error processing customer information: " + e.getMessage());
        }
    }
}
