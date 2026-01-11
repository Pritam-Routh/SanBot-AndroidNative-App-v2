package com.tripandevent.sanbotvoice.functions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.api.CrmApiClient;
import com.tripandevent.sanbotvoice.api.CrmApiClient.*;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Function handlers for CRM operations:
 * - save_customer_lead: Save lead to CRM
 * - get_packages: Fetch packages from CRM
 * - get_package_details: Get specific package info
 * - search_packages: Search packages by keyword
 */
public class CrmFunctionHandlers {
    
    private static final String TAG = "CrmFunctions";
    private static final Gson gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd")
            .create();
    
    private final CrmApiClient crmClient;
    
    public CrmFunctionHandlers() {
        this.crmClient = CrmApiClient.getInstance();
    }
    
    // ============================================
    // SAVE CUSTOMER LEAD
    // ============================================
    
    /**
     * Handle save_customer_lead function call
     */
    public void saveCustomerLead(String arguments, String sessionId, 
                                  FunctionHandler.FunctionCallback callback) {
        Logger.function("save_customer_lead called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            // Build lead data
            LeadData lead = new LeadData();
            
            // Required field
            lead.name = getStringOrDefault(args, "name", "Unknown Customer");
            
            // Contact info
            lead.email = getStringOrNull(args, "email");
            lead.mobile = getStringOrNull(args, "mobile");
            if (lead.mobile == null) {
                lead.mobile = getStringOrNull(args, "phone");
            }
            
            // Travel details
            lead.destination = getStringOrNull(args, "destination");
            lead.travel_date = getStringOrNull(args, "travel_date");
            lead.journey_start_date = getStringOrNull(args, "journey_start_date");
            if (lead.journey_start_date == null) {
                lead.journey_start_date = lead.travel_date;
            }
            lead.journey_end_date = getStringOrNull(args, "journey_end_date");
            
            // Duration
            lead.duration_nights = getIntOrNull(args, "nights");
            if (lead.duration_nights == null) {
                lead.duration_nights = getIntOrNull(args, "duration_nights");
            }
            if (lead.duration_nights != null) {
                lead.duration_days = lead.duration_nights + 1;
            }
            
            // Travelers
            lead.adults = getIntOrDefault(args, "adults", 2);
            lead.children = getIntOrDefault(args, "children", 0);
            lead.infants = getIntOrDefault(args, "infants", 0);
            
            // Journey type
            lead.journey_type = getStringOrNull(args, "journey_type");
            lead.arrival_city = getStringOrNull(args, "arrival_city");
            lead.departure_city = getStringOrNull(args, "departure_city");
            lead.nearest_airport = getStringOrNull(args, "nearest_airport");
            
            // Accommodation
            lead.hotel_type = getStringOrNull(args, "hotel_type");
            lead.total_rooms = getIntOrNull(args, "rooms");
            if (lead.total_rooms == null) {
                lead.total_rooms = getIntOrNull(args, "total_rooms");
            }
            lead.meal_plan = getStringOrNull(args, "meal_plan");
            
            // Special requirements
            lead.special_requirement = getStringOrNull(args, "special_requirements");
            if (lead.special_requirement == null) {
                lead.special_requirement = getStringOrNull(args, "special_requirement");
            }
            
            // Budget/estimated value
            Double budget = getDoubleOrNull(args, "budget");
            if (budget != null) {
                lead.estimated_value = budget;
            }
            
            // Source tracking
            lead.source = "Voice Agent";
            lead.source_type = "voice_agent";
            lead.utm_source = "sanbot";
            lead.utm_medium = "voice";
            lead.utm_campaign = sessionId;
            
            // AI summary from conversation
            lead.ai_summary = getStringOrNull(args, "conversation_summary");
            lead.notes = "Lead captured via Sanbot Voice Agent. Session: " + sessionId;
            
            // Interest/remarks
            String interest = getStringOrNull(args, "interest");
            if (interest != null) {
                lead.remarks = "Interest: " + interest;
            }
            
            // Call CRM API
            crmClient.createLead(lead, new LeadCallback() {
                @Override
                public void onSuccess(LeadResponse response) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("message", "Customer information saved successfully");
                    result.addProperty("lead_id", response.id);
                    result.addProperty("customer_name", lead.name);
                    
                    Logger.i(TAG, "Lead saved: ID=%d, Name=%s", response.id, lead.name);
                    callback.onSuccess(result.toString());
                }
                
                @Override
                public void onError(String error) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", false);
                    result.addProperty("error", error);
                    result.addProperty("message", "Failed to save customer information, but I have noted the details");
                    
                    Logger.e(TAG, "Failed to save lead: %s", error);
                    callback.onSuccess(result.toString());
                }
            });
            
        } catch (Exception e) {
            Logger.e(e, "Error processing save_customer_lead");
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
            callback.onError(e.getMessage());
        }
    }
    
    // ============================================
    // GET PACKAGES
    // ============================================
    
    public void getPackages(String arguments, String sessionId,
                            FunctionHandler.FunctionCallback callback) {
        Logger.function("get_packages called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            // Build filters
            PackageFilters filters = new PackageFilters();
            filters.destination = getStringOrNull(args, "destination");
            filters.packageType = getStringOrNull(args, "package_type");
            
            Double minPrice = getDoubleOrNull(args, "budget_min");
            if (minPrice != null) filters.minPrice = minPrice;
            
            Double maxPrice = getDoubleOrNull(args, "budget_max");
            if (maxPrice != null) filters.maxPrice = maxPrice;
            
            Integer nights = getIntOrNull(args, "nights");
            if (nights != null) {
                filters.minNights = Math.max(1, nights - 1);
                filters.maxNights = nights + 1;
            }
            
            Integer minNights = getIntOrNull(args, "min_nights");
            if (minNights != null) filters.minNights = minNights;
            
            Integer maxNights = getIntOrNull(args, "max_nights");
            if (maxNights != null) filters.maxNights = maxNights;
            
            filters.limit = getIntOrDefault(args, "limit", 5);
            
            // Call CRM API
            crmClient.getPackages(filters, new PackagesCallback() {
                @Override
                public void onSuccess(PackagesResponse response) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    
                    if (response.packages == null || response.packages.isEmpty()) {
                        result.addProperty("message", "No packages found matching your criteria");
                        result.addProperty("count", 0);
                        result.add("packages", new JsonArray());
                    } else {
                        result.addProperty("count", response.packages.size());
                        result.addProperty("message", "Found " + response.packages.size() + " packages");
                        
                        JsonArray packagesArray = new JsonArray();
                        StringBuilder voiceSummary = new StringBuilder();
                        voiceSummary.append("I found ").append(response.packages.size()).append(" packages. ");
                        
                        int count = 0;
                        for (PackageDetail pkg : response.packages) {
                            count++;
                            JsonObject pkgObj = new JsonObject();
                            pkgObj.addProperty("id", pkg.id);
                            pkgObj.addProperty("name", pkg.getDisplayName());
                            pkgObj.addProperty("price", pkg.getPrice());
                            pkgObj.addProperty("currency", pkg.currency);
                            pkgObj.addProperty("nights", pkg.getNights());
                            pkgObj.addProperty("days", pkg.getDays());
                            pkgObj.addProperty("description", pkg.description);
                            pkgObj.addProperty("voice_description", pkg.toVoiceDescription());
                            
                            if (pkg.destinations != null) {
                                pkgObj.add("destinations", gson.toJsonTree(pkg.destinations));
                            }
                            if (pkg.inclusions != null) {
                                pkgObj.add("inclusions", gson.toJsonTree(pkg.inclusions));
                            }
                            
                            packagesArray.add(pkgObj);
                            
                            voiceSummary.append("Option ").append(count).append(": ");
                            voiceSummary.append(pkg.toVoiceDescription()).append(". ");
                        }
                        
                        result.add("packages", packagesArray);
                        result.addProperty("voice_summary", voiceSummary.toString());
                    }
                    
                    Logger.i(TAG, "Packages fetched: count=%d", 
                        response.packages != null ? response.packages.size() : 0);
                    callback.onSuccess(result.toString());
                }
                
                @Override
                public void onError(String error) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", false);
                    result.addProperty("error", error);
                    result.addProperty("message", "I'm having trouble fetching package information right now");
                    
                    Logger.e(TAG, "Failed to fetch packages: %s", error);
                    callback.onSuccess(result.toString());
                }
            });
            
        } catch (Exception e) {
            Logger.e(e, "Error processing get_packages");
            callback.onError(e.getMessage());
        }
    }
    
    // ============================================
    // GET PACKAGE DETAILS
    // ============================================
    
    public void getPackageDetails(String arguments, String sessionId,
                                   FunctionHandler.FunctionCallback callback) {
        Logger.function("get_package_details called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            Integer packageId = getIntOrNull(args, "package_id");
            if (packageId == null) {
                packageId = getIntOrNull(args, "id");
            }
            
            if (packageId == null) {
                callback.onError("Package ID is required");
                return;
            }
            
            final int pkgId = packageId;
            
            crmClient.getPackageById(pkgId, new PackageDetailCallback() {
                @Override
                public void onSuccess(PackageDetail pkg) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("id", pkg.id);
                    result.addProperty("name", pkg.getDisplayName());
                    result.addProperty("description", pkg.description);
                    result.addProperty("price", pkg.getPrice());
                    result.addProperty("currency", pkg.currency);
                    result.addProperty("nights", pkg.getNights());
                    result.addProperty("days", pkg.getDays());
                    result.addProperty("min_pax", pkg.minPax > 0 ? pkg.minPax : pkg.min_pax);
                    result.addProperty("max_pax", pkg.maxPax > 0 ? pkg.maxPax : pkg.max_pax);
                    
                    if (pkg.destinations != null) {
                        result.add("destinations", gson.toJsonTree(pkg.destinations));
                    }
                    if (pkg.inclusions != null) {
                        result.add("inclusions", gson.toJsonTree(pkg.inclusions));
                    }
                    if (pkg.exclusions != null) {
                        result.add("exclusions", gson.toJsonTree(pkg.exclusions));
                    }
                    
                    result.addProperty("voice_description", pkg.toDetailedVoiceDescription());
                    
                    Logger.i(TAG, "Package details fetched: %s", pkg.getDisplayName());
                    callback.onSuccess(result.toString());
                }
                
                @Override
                public void onError(String error) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", false);
                    result.addProperty("error", error);
                    result.addProperty("message", "Could not find that package");
                    
                    callback.onSuccess(result.toString());
                }
            });
            
        } catch (Exception e) {
            Logger.e(e, "Error processing get_package_details");
            callback.onError(e.getMessage());
        }
    }
    
    // ============================================
    // SEARCH PACKAGES
    // ============================================
    
    public void searchPackages(String arguments, String sessionId,
                               FunctionHandler.FunctionCallback callback) {
        Logger.function("search_packages called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            String query = getStringOrNull(args, "query");
            if (query == null) {
                query = getStringOrNull(args, "keyword");
            }
            if (query == null) {
                query = getStringOrNull(args, "search");
            }
            
            if (query == null || query.trim().isEmpty()) {
                getPackages("{\"limit\": 5}", sessionId, callback);
                return;
            }
            
            crmClient.searchPackages(query, new PackagesCallback() {
                @Override
                public void onSuccess(PackagesResponse response) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    
                    if (response.packages == null || response.packages.isEmpty()) {
                        result.addProperty("message", "No packages found for your search");
                        result.addProperty("count", 0);
                    } else {
                        result.addProperty("count", response.packages.size());
                        
                        JsonArray packagesArray = new JsonArray();
                        StringBuilder voiceSummary = new StringBuilder();
                        voiceSummary.append("I found ").append(response.packages.size())
                                   .append(" packages matching your search. ");
                        
                        int count = 0;
                        for (PackageDetail pkg : response.packages) {
                            count++;
                            JsonObject pkgObj = new JsonObject();
                            pkgObj.addProperty("id", pkg.id);
                            pkgObj.addProperty("name", pkg.getDisplayName());
                            pkgObj.addProperty("price", pkg.getPrice());
                            pkgObj.addProperty("nights", pkg.getNights());
                            pkgObj.addProperty("voice_description", pkg.toVoiceDescription());
                            packagesArray.add(pkgObj);
                            
                            voiceSummary.append("Option ").append(count).append(": ");
                            voiceSummary.append(pkg.toVoiceDescription()).append(". ");
                            
                            if (count >= 5) break;
                        }
                        
                        result.add("packages", packagesArray);
                        result.addProperty("voice_summary", voiceSummary.toString());
                    }
                    
                    callback.onSuccess(result.toString());
                }
                
                @Override
                public void onError(String error) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", false);
                    result.addProperty("error", error);
                    callback.onSuccess(result.toString());
                }
            });
            
        } catch (Exception e) {
            Logger.e(e, "Error processing search_packages");
            callback.onError(e.getMessage());
        }
    }
    
    // ============================================
    // HELPER METHODS
    // ============================================
    
    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }
    
    private String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        String value = getStringOrNull(obj, key);
        return value != null ? value : defaultValue;
    }
    
    private Integer getIntOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
    
    private int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
        Integer value = getIntOrNull(obj, key);
        return value != null ? value : defaultValue;
    }
    
    private Double getDoubleOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsDouble();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}