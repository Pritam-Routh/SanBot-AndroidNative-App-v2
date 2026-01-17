package com.tripandevent.sanbotvoice.functions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.api.CrmApiClient;
import com.tripandevent.sanbotvoice.api.CrmApiClient.*;
import com.tripandevent.sanbotvoice.utils.Logger;

/**
 * Function handlers for CRM operations:
 * - save_customer_lead: Save lead to CRM (POST /api/leads)
 * - get_packages: Fetch packages from CRM (GET /api/packages)
 * - get_package_details: Get specific package info
 * - search_packages: Search packages by keyword
 * 
 * API Base URL: https://crm.tripandevent.com/api
 * Authentication: Bearer token from /auth/login
 */
public class CrmFunctionHandlers {
    
    private static final String TAG = "CrmFunctions";
    private static final Gson gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd")
            .create();
    
    // Default values for lead source tracking
    private static final String DEFAULT_LEAD_SOURCE = "Voice Agent";
    private static final String DEFAULT_LEAD_SOURCE_TYPE = "voice_agent";
    private static final String DEFAULT_UTM_SOURCE = "sanbot";
    private static final String DEFAULT_UTM_MEDIUM = "voice";
    
    private final CrmApiClient crmClient;
    
    public CrmFunctionHandlers() {
        this.crmClient = CrmApiClient.getInstance();
    }
    
    // ============================================
    // SAVE CUSTOMER LEAD
    // ============================================
    
    /**
     * Handle save_customer_lead function call
     * 
     * Maps AI function arguments to CRM API LeadData schema
     */
    public void saveCustomerLead(String arguments, String sessionId, 
                                  FunctionHandler.FunctionCallback callback) {
        Logger.function("save_customer_lead called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            // Build lead data matching CRM API schema (camelCase)
            LeadData lead = new LeadData();
            
            // Contact Information (Required: name)
            lead.name = getStringOrDefault(args, "name", "Unknown Customer");
            lead.email = getStringOrNull(args, "email");
            lead.mobile = getStringOrNull(args, "mobile");
            if (lead.mobile == null) {
                lead.mobile = getStringOrNull(args, "phone");
            }
            
            // Location
            lead.destination = getStringOrNull(args, "destination");
            lead.nearestAirport = getStringOrNull(args, "nearest_airport");
            if (lead.nearestAirport == null) {
                lead.nearestAirport = getStringOrNull(args, "nearestAirport");
            }
            lead.arrivalCity = getStringOrNull(args, "arrival_city");
            if (lead.arrivalCity == null) {
                lead.arrivalCity = getStringOrNull(args, "arrivalCity");
            }
            lead.departureCity = getStringOrNull(args, "departure_city");
            if (lead.departureCity == null) {
                lead.departureCity = getStringOrNull(args, "departureCity");
            }
            
            // Dates
            lead.travelDate = getStringOrNull(args, "travel_date");
            if (lead.travelDate == null) {
                lead.travelDate = getStringOrNull(args, "travelDate");
            }
            lead.expectedDate = getStringOrNull(args, "expected_date");
            if (lead.expectedDate == null) {
                lead.expectedDate = getStringOrNull(args, "expectedDate");
            }
            lead.journeyStartDate = getStringOrNull(args, "journey_start_date");
            if (lead.journeyStartDate == null) {
                lead.journeyStartDate = getStringOrNull(args, "journeyStartDate");
            }
            if (lead.journeyStartDate == null) {
                lead.journeyStartDate = lead.travelDate;
            }
            lead.journeyEndDate = getStringOrNull(args, "journey_end_date");
            if (lead.journeyEndDate == null) {
                lead.journeyEndDate = getStringOrNull(args, "journeyEndDate");
            }
            
            // Duration
            lead.durationNights = getIntOrNull(args, "nights");
            if (lead.durationNights == null) {
                lead.durationNights = getIntOrNull(args, "duration_nights");
            }
            if (lead.durationNights == null) {
                lead.durationNights = getIntOrNull(args, "durationNights");
            }
            if (lead.durationNights != null) {
                lead.durationDays = lead.durationNights + 1;
            }
            
            // Travelers
            lead.adults = getIntOrDefault(args, "adults", 2);
            lead.children = getIntOrDefault(args, "children", 0);
            lead.infants = getIntOrDefault(args, "infants", 0);
            
            // Accommodation
            lead.hotelType = getStringOrNull(args, "hotel_type");
            if (lead.hotelType == null) {
                lead.hotelType = getStringOrNull(args, "hotelType");
            }
            // Convert simple values to CRM format if needed
            if (lead.hotelType != null) {
                lead.hotelType = normalizeHotelType(lead.hotelType);
            }
            
            lead.totalRooms = getIntOrNull(args, "total_rooms");
            if (lead.totalRooms == null) {
                lead.totalRooms = getIntOrNull(args, "totalRooms");
            }
            if (lead.totalRooms == null) {
                lead.totalRooms = getIntOrNull(args, "rooms");
            }
            
            lead.extraMattress = getIntOrNull(args, "extra_mattress");
            if (lead.extraMattress == null) {
                lead.extraMattress = getIntOrNull(args, "extraMattress");
            }
            
            lead.mealPlan = getStringOrNull(args, "meal_plan");
            if (lead.mealPlan == null) {
                lead.mealPlan = getStringOrNull(args, "mealPlan");
            }
            // Normalize meal plan
            if (lead.mealPlan != null) {
                lead.mealPlan = normalizeMealPlan(lead.mealPlan);
            }
            
            // Extras
            Boolean videoPhoto = getBooleanOrNull(args, "videography_photography");
            if (videoPhoto == null) {
                videoPhoto = getBooleanOrNull(args, "videographyPhotography");
            }
            lead.videographyPhotography = videoPhoto;
            
            lead.specialRequirement = getStringOrNull(args, "special_requirements");
            if (lead.specialRequirement == null) {
                lead.specialRequirement = getStringOrNull(args, "specialRequirement");
            }
            if (lead.specialRequirement == null) {
                lead.specialRequirement = getStringOrNull(args, "special_requirement");
            }
            
            // Journey type
            lead.journeyType = getStringOrNull(args, "journey_type");
            if (lead.journeyType == null) {
                lead.journeyType = getStringOrNull(args, "journeyType");
            }
            
            // Budget/estimated value
            Double budget = getDoubleOrNull(args, "budget");
            if (budget != null) {
                lead.estimatedValue = budget;
            }
            
            // Source tracking (use defaults)
            lead.source = DEFAULT_LEAD_SOURCE;
            lead.sourceType = DEFAULT_LEAD_SOURCE_TYPE;
            lead.utmSource = DEFAULT_UTM_SOURCE;
            lead.utmMedium = DEFAULT_UTM_MEDIUM;
            lead.utmCampaign = sessionId;
            
            // AI summary from conversation
            lead.aiSummary = getStringOrNull(args, "conversation_summary");
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
                    // Return as success so AI can handle gracefully
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
    
    /**
     * Normalize hotel type to CRM format
     */
    private String normalizeHotelType(String input) {
        if (input == null) return null;
        
        String lower = input.toLowerCase().trim();
        
        if (lower.contains("5") || lower.contains("five") || lower.contains("luxury") || lower.contains("premium")) {
            return "5 Star";
        } else if (lower.contains("4") || lower.contains("four") || lower.contains("deluxe")) {
            return "4 Star";
        } else if (lower.contains("3") || lower.contains("three") || lower.contains("standard")) {
            return "3 Star";
        } else if (lower.contains("2") || lower.contains("two") || lower.contains("budget")) {
            return "2 Star";
        } else if (lower.contains("resort")) {
            return "Resort";
        } else if (lower.contains("villa")) {
            return "Villa";
        } else if (lower.contains("homestay") || lower.contains("home stay")) {
            return "Homestay";
        }
        
        // Return as-is if no match
        return input;
    }
    
    /**
     * Normalize meal plan to CRM format
     */
    private String normalizeMealPlan(String input) {
        if (input == null) return null;
        
        String lower = input.toLowerCase().trim();
        
        if (lower.contains("ap") || lower.contains("all inclusive") || lower.contains("full board")) {
            return "AP";  // American Plan (All meals)
        } else if (lower.contains("map") || lower.contains("half board")) {
            return "MAP"; // Modified American Plan (Breakfast + Dinner)
        } else if (lower.contains("cp") || lower.contains("breakfast")) {
            return "CP";  // Continental Plan (Breakfast only)
        } else if (lower.contains("ep") || lower.contains("room only") || lower.contains("no meal")) {
            return "EP";  // European Plan (Room only)
        }
        
        return input;
    }
    
    // ============================================
    // GET PACKAGES
    // ============================================
    
    /**
     * Handle get_packages function call
     */
    public void getPackages(String arguments, String sessionId,
                            FunctionHandler.FunctionCallback callback) {
        Logger.function("get_packages called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            // Build filters
            PackageFilters filters = new PackageFilters();
            filters.destination = getStringOrNull(args, "destination");
            filters.packageType = getStringOrNull(args, "package_type");
            if (filters.packageType == null) {
                filters.packageType = getStringOrNull(args, "packageType");
            }
            
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
                        
                        // Build packages array with voice-friendly descriptions
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
                            
                            // Build voice summary (max 5 packages)
                            if (count <= 5) {
                                voiceSummary.append("Option ").append(count).append(": ");
                                voiceSummary.append(pkg.toVoiceDescription()).append(". ");
                            }
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
    
    /**
     * Handle get_package_details function call
     */
    public void getPackageDetails(String arguments, String sessionId,
                                   FunctionHandler.FunctionCallback callback) {
        Logger.function("get_package_details called with: %s", arguments);
        
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            
            Integer packageId = getIntOrNull(args, "package_id");
            if (packageId == null) {
                packageId = getIntOrNull(args, "packageId");
            }
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
    
    /**
     * Handle search_packages function call
     */
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
                // If no query, get all packages
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
                            
                            if (count <= 5) {
                                voiceSummary.append("Option ").append(count).append(": ");
                                voiceSummary.append(pkg.toVoiceDescription()).append(". ");
                            }
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
    
    private Boolean getBooleanOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsBoolean();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}