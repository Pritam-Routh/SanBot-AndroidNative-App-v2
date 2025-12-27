package com.tripandevent.sanbotvoice.functions;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.api.ApiClient;
import com.tripandevent.sanbotvoice.api.TripAndEventApi;
import com.tripandevent.sanbotvoice.api.models.ApiResponse;
import com.tripandevent.sanbotvoice.api.models.QuoteRequest;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Function handler for creating and saving price quotes.
 * 
 * This is called when the AI generates a quote for the customer.
 */
public class CreateQuoteFunction implements FunctionHandler {
    
    private static final String TAG = "CreateQuoteFunction";
    private final Gson gson = new Gson();
    
    @Override
    public void execute(String arguments, String sessionId, FunctionCallback callback) {
        Logger.function("Executing create_quote with args: %s", arguments);
        
        try {
            // Parse arguments
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            // Build quote request
            QuoteRequest.Builder builder = new QuoteRequest.Builder()
                .sessionId(sessionId);
            
            // Customer info
            if (args.has("customerName") && !args.get("customerName").isJsonNull()) {
                builder.customerName(args.get("customerName").getAsString());
            }
            if (args.has("customerEmail") && !args.get("customerEmail").isJsonNull()) {
                builder.customerEmail(args.get("customerEmail").getAsString());
            }
            if (args.has("customerPhone") && !args.get("customerPhone").isJsonNull()) {
                builder.customerPhone(args.get("customerPhone").getAsString());
            }
            
            // Parse items
            if (args.has("items") && args.get("items").isJsonArray()) {
                JsonArray itemsArray = args.getAsJsonArray("items");
                List<QuoteRequest.QuoteItem> items = new ArrayList<>();
                
                for (JsonElement elem : itemsArray) {
                    JsonObject itemObj = elem.getAsJsonObject();
                    
                    String description = itemObj.has("description") ? 
                        itemObj.get("description").getAsString() : "Item";
                    int quantity = itemObj.has("quantity") ? 
                        itemObj.get("quantity").getAsInt() : 1;
                    double unitPrice = itemObj.has("unitPrice") ? 
                        itemObj.get("unitPrice").getAsDouble() : 0.0;
                    
                    items.add(new QuoteRequest.QuoteItem(description, quantity, unitPrice));
                }
                
                builder.items(items);
            }
            
            // Notes
            if (args.has("notes") && !args.get("notes").isJsonNull()) {
                builder.notes(args.get("notes").getAsString());
            }
            
            // Set validity period (30 days from now)
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, 30);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            builder.validUntil(sdf.format(cal.getTime()));
            
            QuoteRequest quoteRequest = builder.build();
            Logger.function("Creating quote for: %s", quoteRequest.getCustomerName());
            
            // Call API
            ApiClient.getInstance().getApi().createQuote(
                ApiClient.getInstance().getAuthHeader(),
                quoteRequest
            ).enqueue(new Callback<ApiResponse<TripAndEventApi.CreateQuoteResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<TripAndEventApi.CreateQuoteResponse>> call,
                                      Response<ApiResponse<TripAndEventApi.CreateQuoteResponse>> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        ApiResponse<TripAndEventApi.CreateQuoteResponse> apiResponse = response.body();
                        
                        if (apiResponse.isSuccess()) {
                            TripAndEventApi.CreateQuoteResponse data = apiResponse.getData();
                            Logger.function("Quote created: %s", data.quoteNumber);
                            
                            // Return success result to AI
                            JsonObject result = new JsonObject();
                            result.addProperty("success", true);
                            result.addProperty("quoteId", data.quoteId);
                            result.addProperty("quoteNumber", data.quoteNumber);
                            result.addProperty("message", "Quote " + data.quoteNumber + " created successfully");
                            if (data.pdfUrl != null) {
                                result.addProperty("pdfUrl", data.pdfUrl);
                            }
                            callback.onSuccess(result.toString());
                        } else {
                            Logger.e(TAG, "API error: %s", apiResponse.getError());
                            callback.onError("Failed to create quote");
                        }
                    } else {
                        Logger.e(TAG, "HTTP error: %d", response.code());
                        callback.onError("Server error creating quote");
                    }
                }
                
                @Override
                public void onFailure(Call<ApiResponse<TripAndEventApi.CreateQuoteResponse>> call,
                                     Throwable t) {
                    Logger.e(TAG, "Network error: %s", t.getMessage());
                    callback.onError("Network error creating quote");
                }
            });
            
        } catch (Exception e) {
            Logger.e(e, "Error executing create_quote");
            callback.onError("Error creating quote: " + e.getMessage());
        }
    }
}
