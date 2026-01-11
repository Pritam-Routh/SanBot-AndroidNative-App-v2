package com.tripandevent.sanbotvoice.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * API client for Trip & Event CRM system.
 * 
 * Endpoints:
 * - POST /api/leads - Create new lead
 * - GET /api/packages - Get packages list
 * - GET /api/packages/{id} - Get package details
 */
public class CrmApiClient {
    
    private static final String TAG = "CrmApiClient";
    
    // CRM Base URL
    private static final String CRM_BASE_URL = "https://crm.tripandevent.com";
    
    // API Endpoints
    private static final String ENDPOINT_LEADS = "/api/leads";
    private static final String ENDPOINT_PACKAGES = "/api/packages";
    
    // API Key/Token for authentication (configure in Constants)
    private static final String API_KEY = Constants.CRM_API_KEY;
    
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd")
            .create();
    
    private final OkHttpClient httpClient;
    
    // Singleton instance
    private static CrmApiClient instance;
    
    public static synchronized CrmApiClient getInstance() {
        if (instance == null) {
            instance = new CrmApiClient();
        }
        return instance;
    }
    
    private CrmApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    // ============================================
    // CALLBACK INTERFACES
    // ============================================
    
    public interface LeadCallback {
        void onSuccess(LeadResponse response);
        void onError(String error);
    }
    
    public interface PackagesCallback {
        void onSuccess(PackagesResponse response);
        void onError(String error);
    }
    
    public interface PackageDetailCallback {
        void onSuccess(PackageDetail response);
        void onError(String error);
    }
    
    // ============================================
    // LEAD API
    // ============================================
    
    /**
     * Create a new lead in the CRM
     * 
     * @param leadData Lead data to create
     * @param callback Callback for result
     */
    public void createLead(@NonNull LeadData leadData, @NonNull LeadCallback callback) {
        Logger.d(TAG, "Creating lead: %s", leadData.name);
        
        String json = gson.toJson(leadData);
        Logger.d(TAG, "Lead JSON: %s", json);
        
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
                .url(CRM_BASE_URL + ENDPOINT_LEADS)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("X-Source", "sanbot-voice-agent")
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Logger.e(e, "Failed to create lead");
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    
                    if (response.isSuccessful()) {
                        LeadResponse leadResponse = gson.fromJson(responseBody, LeadResponse.class);
                        if (leadResponse == null) {
                            leadResponse = new LeadResponse();
                            leadResponse.success = true;
                            leadResponse.message = "Lead created successfully";
                        }
                        leadResponse.success = true;
                        Logger.i(TAG, "Lead created successfully: %s", leadResponse.id);
                        callback.onSuccess(leadResponse);
                    } else {
                        String error = "Failed to create lead: " + response.code();
                        if (!responseBody.isEmpty()) {
                            try {
                                JsonObject errorObj = gson.fromJson(responseBody, JsonObject.class);
                                if (errorObj.has("message")) {
                                    error = errorObj.get("message").getAsString();
                                }
                            } catch (Exception ignored) {}
                        }
                        Logger.e(TAG, error);
                        callback.onError(error);
                    }
                } catch (Exception e) {
                    Logger.e(e, "Error parsing lead response");
                    callback.onError("Error processing response: " + e.getMessage());
                }
            }
        });
    }
    
    // ============================================
    // PACKAGES API
    // ============================================
    
    /**
     * Get all packages with optional filters
     * 
     * @param filters Optional filters (destination, type, minPrice, maxPrice, etc.)
     * @param callback Callback for result
     */
    public void getPackages(@Nullable PackageFilters filters, @NonNull PackagesCallback callback) {
        Logger.d(TAG, "Fetching packages");
        
        StringBuilder url = new StringBuilder(CRM_BASE_URL + ENDPOINT_PACKAGES);
        
        // Add query parameters for filters
        if (filters != null) {
            url.append("?");
            boolean hasParam = false;
            
            if (filters.destination != null && !filters.destination.isEmpty()) {
                url.append("destination=").append(filters.destination);
                hasParam = true;
            }
            
            if (filters.packageType != null && !filters.packageType.isEmpty()) {
                if (hasParam) url.append("&");
                url.append("package_type=").append(filters.packageType);
                hasParam = true;
            }
            
            if (filters.minPrice > 0) {
                if (hasParam) url.append("&");
                url.append("min_price=").append(filters.minPrice);
                hasParam = true;
            }
            
            if (filters.maxPrice > 0) {
                if (hasParam) url.append("&");
                url.append("max_price=").append(filters.maxPrice);
                hasParam = true;
            }
            
            if (filters.duration != null && !filters.duration.isEmpty()) {
                if (hasParam) url.append("&");
                url.append("duration=").append(filters.duration);
                hasParam = true;
            }
            
            if (filters.minNights > 0) {
                if (hasParam) url.append("&");
                url.append("min_nights=").append(filters.minNights);
                hasParam = true;
            }
            
            if (filters.maxNights > 0) {
                if (hasParam) url.append("&");
                url.append("max_nights=").append(filters.maxNights);
                hasParam = true;
            }
            
            if (filters.limit > 0) {
                if (hasParam) url.append("&");
                url.append("limit=").append(filters.limit);
            }
            
            // Status filter - only return active/published packages
            if (hasParam) url.append("&");
            url.append("status=Published");
        }
        
        Logger.d(TAG, "Packages URL: %s", url.toString());
        
        Request request = new Request.Builder()
                .url(url.toString())
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("X-Source", "sanbot-voice-agent")
                .get()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Logger.e(e, "Failed to fetch packages");
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    
                    if (response.isSuccessful()) {
                        PackagesResponse packagesResponse = gson.fromJson(responseBody, PackagesResponse.class);
                        if (packagesResponse == null) {
                            packagesResponse = new PackagesResponse();
                        }
                        packagesResponse.success = true;
                        Logger.i(TAG, "Fetched %d packages", 
                            packagesResponse.packages != null ? packagesResponse.packages.size() : 0);
                        callback.onSuccess(packagesResponse);
                    } else {
                        String error = "Failed to fetch packages: " + response.code();
                        Logger.e(TAG, error);
                        callback.onError(error);
                    }
                } catch (Exception e) {
                    Logger.e(e, "Error parsing packages response");
                    callback.onError("Error processing response: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Get package details by ID
     * 
     * @param packageId Package ID
     * @param callback Callback for result
     */
    public void getPackageById(int packageId, @NonNull PackageDetailCallback callback) {
        Logger.d(TAG, "Fetching package: %d", packageId);
        
        String url = CRM_BASE_URL + ENDPOINT_PACKAGES + "/" + packageId;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("X-Source", "sanbot-voice-agent")
                .get()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Logger.e(e, "Failed to fetch package %d", packageId);
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    
                    if (response.isSuccessful()) {
                        PackageDetail packageDetail = gson.fromJson(responseBody, PackageDetail.class);
                        Logger.i(TAG, "Fetched package: %s", packageDetail.packageName);
                        callback.onSuccess(packageDetail);
                    } else {
                        String error = "Package not found: " + response.code();
                        Logger.e(TAG, error);
                        callback.onError(error);
                    }
                } catch (Exception e) {
                    Logger.e(e, "Error parsing package response");
                    callback.onError("Error processing response: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Search packages by keyword (searches name, description, destinations)
     */
    public void searchPackages(String keyword, @NonNull PackagesCallback callback) {
        Logger.d(TAG, "Searching packages: %s", keyword);
        
        String url = CRM_BASE_URL + ENDPOINT_PACKAGES + "/search?q=" + keyword + "&status=Published";
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("X-Source", "sanbot-voice-agent")
                .get()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Logger.e(e, "Failed to search packages");
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    
                    if (response.isSuccessful()) {
                        PackagesResponse packagesResponse = gson.fromJson(responseBody, PackagesResponse.class);
                        if (packagesResponse == null) {
                            packagesResponse = new PackagesResponse();
                        }
                        packagesResponse.success = true;
                        callback.onSuccess(packagesResponse);
                    } else {
                        callback.onError("Search failed: " + response.code());
                    }
                } catch (Exception e) {
                    callback.onError("Error processing response: " + e.getMessage());
                }
            }
        });
    }
    
    // ============================================
    // DATA CLASSES - Lead
    // ============================================
    
    /**
     * Lead data structure matching the leads table schema
     */
    public static class LeadData {
        // Required
        public String name;
        
        // Contact
        public String email;
        public String mobile;
        
        // Travel details
        public String destination;
        public String travel_date;           // yyyy-MM-dd format
        public String journey_start_date;    // yyyy-MM-dd
        public String journey_end_date;      // yyyy-MM-dd
        public Integer duration_nights;
        public Integer duration_days;
        
        // Travelers
        public Integer adults = 0;
        public Integer children = 0;
        public Integer infants = 0;
        
        // Journey details
        public String journey_type;          // "one_way", "round_trip"
        public String arrival_city;
        public String departure_city;
        public String nearest_airport;
        
        // Accommodation
        public String hotel_type;            // "budget", "standard", "luxury", "premium"
        public Integer total_rooms;
        public Integer extra_mattress = 0;
        public String meal_plan;             // "EP", "CP", "MAP", "AP"
        
        // Special
        public String special_requirement;
        public Boolean videography_photography = false;
        
        // Source tracking
        public String source = "Voice Agent";
        public String source_type = "voice_agent";
        public String utm_source = "sanbot";
        public String utm_medium = "voice";
        public String utm_campaign;
        
        // Internal
        public Integer company_id;           // Set from Constants
        public String status = "new";
        public String priority = "medium";
        public String notes;
        public String remarks;
        public String ai_summary;
        
        // Estimated value
        public Double estimated_value = 0.0;
        
        /**
         * Create LeadData from conversation context
         */
        public static LeadData fromConversation(
                String name, String email, String mobile,
                String destination, String travelDate,
                int adults, int children, int nights) {
            
            LeadData lead = new LeadData();
            lead.name = name;
            lead.email = email;
            lead.mobile = mobile;
            lead.destination = destination;
            lead.travel_date = travelDate;
            lead.journey_start_date = travelDate;
            lead.adults = adults;
            lead.children = children;
            lead.duration_nights = nights;
            lead.duration_days = nights + 1;
            lead.company_id = Constants.CRM_COMPANY_ID;
            lead.source = "Voice Agent";
            lead.source_type = "voice_agent";
            
            return lead;
        }
    }
    
    public static class LeadResponse {
        public boolean success;
        public String message;
        public Long id;
        public LeadData data;
    }
    
    // ============================================
    // DATA CLASSES - Package
    // ============================================
    
    public static class PackageFilters {
        public String destination;
        public String packageType;     // "with_flight", "without_flight"
        public double minPrice;
        public double maxPrice;
        public String duration;
        public int minNights;
        public int maxNights;
        public int limit = 10;
    }
    
    public static class PackagesResponse {
        public boolean success;
        public java.util.List<PackageDetail> packages;
        public int total;
        public int page;
        public int limit;
    }
    
    /**
     * Package detail structure matching the packages table schema
     */
    public static class PackageDetail {
        public int id;
        public Integer company_id;
        public String packageName;
        public String package_name;          // Alternative field name
        public String packageType;
        public String package_type;
        public String flightRouteType;
        public String flight_route_type;
        
        // Destinations (JSONB array)
        public java.util.List<String> destinations;
        
        public String description;
        
        // Tags (JSONB array)
        public java.util.List<String> tags;
        
        // Inclusions (JSONB array)
        public java.util.List<String> inclusions;
        
        // Exclusions (JSONB array)
        public java.util.List<String> exclusions;
        
        // Hotels (JSONB)
        public Object selectedHotels;
        public Object selected_hotels;
        
        // Vehicles (JSONB)
        public Object selectedVehicles;
        public Object selected_vehicles;
        
        // Pricing
        public double basePrice;
        public double base_price;
        public double sellingPrice;
        public double selling_price;
        public String currency = "INR";
        
        // Validity
        public String validFrom;
        public String valid_from;
        public String validTill;
        public String valid_till;
        
        // Capacity
        public int minPax = 2;
        public int min_pax = 2;
        public int maxPax = 10;
        public int max_pax = 10;
        
        // Duration
        public String duration;
        public int totalNights;
        public int total_nights;
        public int totalDays;
        public int total_days;
        
        public String status;
        
        /**
         * Get display name (handles both field naming conventions)
         */
        public String getDisplayName() {
            return packageName != null ? packageName : package_name;
        }
        
        /**
         * Get price for display
         */
        public double getPrice() {
            double price = sellingPrice > 0 ? sellingPrice : selling_price;
            return price > 0 ? price : (basePrice > 0 ? basePrice : base_price);
        }
        
        /**
         * Get nights count
         */
        public int getNights() {
            return totalNights > 0 ? totalNights : total_nights;
        }
        
        /**
         * Get days count
         */
        public int getDays() {
            return totalDays > 0 ? totalDays : total_days;
        }
        
        /**
         * Format package for voice response
         */
        public String toVoiceDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(getDisplayName());
            
            if (getNights() > 0) {
                sb.append(", ").append(getNights()).append(" nights");
                if (getDays() > 0) {
                    sb.append(" ").append(getDays()).append(" days");
                }
            }
            
            if (getPrice() > 0) {
                sb.append(", starting from ").append(currency).append(" ");
                sb.append(String.format("%.0f", getPrice()));
                sb.append(" per person");
            }
            
            if (destinations != null && !destinations.isEmpty()) {
                sb.append(". Destinations include ");
                sb.append(String.join(", ", destinations));
            }
            
            return sb.toString();
        }
        
        /**
         * Format detailed description for voice
         */
        public String toDetailedVoiceDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(getDisplayName()).append(". ");
            
            if (description != null && !description.isEmpty()) {
                // Truncate long descriptions for voice
                String desc = description.length() > 200 ? 
                    description.substring(0, 200) + "..." : description;
                sb.append(desc).append(" ");
            }
            
            if (getNights() > 0) {
                sb.append("This is a ").append(getNights()).append(" night");
                if (getDays() > 0) {
                    sb.append(", ").append(getDays()).append(" day");
                }
                sb.append(" package. ");
            }
            
            if (getPrice() > 0) {
                sb.append("The price starts from ").append(currency).append(" ");
                sb.append(String.format("%.0f", getPrice()));
                sb.append(" per person. ");
            }
            
            if (inclusions != null && !inclusions.isEmpty()) {
                sb.append("Inclusions are: ");
                int maxInclusions = Math.min(5, inclusions.size());
                for (int i = 0; i < maxInclusions; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(inclusions.get(i));
                }
                if (inclusions.size() > 5) {
                    sb.append(", and more");
                }
                sb.append(". ");
            }
            
            return sb.toString();
        }
    }
}