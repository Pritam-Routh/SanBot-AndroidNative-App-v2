package com.tripandevent.sanbotvoice.api;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * CRM API Client for TripAndEvent CRM System
 * 
 * Base URL: https://crm.tripandevent.com/api
 * 
 * Endpoints:
 * - POST /auth/login - Get access token
 * - POST /leads - Create new lead
 * - GET /packages - Get packages list
 * 
 * Authentication: Bearer token from login
 */
public class CrmApiClient {
    
    private static final String TAG = "CrmApiClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    // ============================================
    // AUTHENTICATION SWITCH
    // ============================================
    
    /**
     * Set to false to disable CRM API calls until credentials are available.
     * When false, all API calls will return mock/simulated responses.
     * 
     * TODO: Set to true once you have CRM credentials
     */
    public static final boolean CRM_ENABLED = false;
    
    /**
     * CRM Credentials - Update these when you receive them
     */
    private static final String CRM_EMAIL = "your_actual_email";
    private static final String CRM_PASSWORD = "your_actual_password";
    
    // ============================================
    // SINGLETON & INITIALIZATION
    // ============================================
    
    private static CrmApiClient instance;
    private static Context appContext;
    
    private final OkHttpClient httpClient;
    private final Gson gson;
    
    // Authentication state
    private String accessToken;
    private long tokenExpiresAt;
    private boolean isAuthenticating = false;
    
    // Preferences for token persistence
    private static final String PREFS_NAME = "crm_auth";
    private static final String PREF_ACCESS_TOKEN = "access_token";
    private static final String PREF_TOKEN_EXPIRES = "token_expires_at";
    
    private CrmApiClient(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        
        this.gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd")
                .create();
        
        // Load saved token
        loadSavedToken();
        
        if (!CRM_ENABLED) {
            Logger.w(TAG, "CRM API is DISABLED. All calls will return mock responses.");
        }
    }
    
    public static synchronized CrmApiClient getInstance() {
        if (instance == null) {
            instance = new CrmApiClient(null);
        }
        return instance;
    }
    
    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new CrmApiClient(context);
        }
    }
    
    public static synchronized CrmApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new CrmApiClient(context);
        }
        return instance;
    }
    
    /**
     * Check if CRM is enabled
     */
    public boolean isEnabled() {
        return CRM_ENABLED;
    }
    
    // ============================================
    // AUTHENTICATION
    // ============================================
    
    /**
     * Login to get access token
     */
    public void login(String email, String password, AuthCallback callback) {
        // If CRM is disabled, return mock success
        if (!CRM_ENABLED) {
            Logger.w(TAG, "CRM disabled - skipping login");
            callback.onSuccess("mock_token_crm_disabled");
            return;
        }
        
        if (isAuthenticating) {
            callback.onError("Authentication already in progress");
            return;
        }
        
        isAuthenticating = true;
        
        LoginRequest loginRequest = new LoginRequest(email, password);
        String jsonBody = gson.toJson(loginRequest);
        
        Request request = new Request.Builder()
                .url(Constants.CRM_BASE_URL + "/auth/login")
                .post(RequestBody.create(jsonBody, JSON))
                .addHeader("Content-Type", "application/json")
                .build();
        
        Logger.d(TAG, "Logging in to CRM...");
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                isAuthenticating = false;
                Logger.e(e, "Login failed");
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                isAuthenticating = false;
                String body = response.body() != null ? response.body().string() : "";
                
                if (response.isSuccessful()) {
                    try {
                        LoginResponse loginResponse = gson.fromJson(body, LoginResponse.class);
                        if (loginResponse != null && loginResponse.getToken() != null) {
                            accessToken = loginResponse.getToken();
                            tokenExpiresAt = System.currentTimeMillis() + (23 * 60 * 60 * 1000);
                            saveToken();
                            Logger.i(TAG, "Login successful");
                            callback.onSuccess(accessToken);
                        } else {
                            callback.onError("Invalid login response");
                        }
                    } catch (Exception e) {
                        Logger.e(e, "Failed to parse login response");
                        callback.onError("Failed to parse response: " + e.getMessage());
                    }
                } else {
                    Logger.e(TAG, "Login failed: %d - %s", response.code(), body);
                    callback.onError("Login failed: " + response.code());
                }
            }
        });
    }
    
    /**
     * Login with default credentials
     */
    public void loginWithDefaultCredentials(AuthCallback callback) {
        if (!CRM_ENABLED || CRM_EMAIL.isEmpty() || CRM_PASSWORD.isEmpty()) {
            Logger.w(TAG, "CRM disabled or no credentials - using mock token");
            callback.onSuccess("mock_token");
            return;
        }
        login(CRM_EMAIL, CRM_PASSWORD, callback);
    }
    
    /**
     * Check if we have a valid token
     */
    public boolean hasValidToken() {
        if (!CRM_ENABLED) return true; // Always "valid" when disabled
        return accessToken != null && System.currentTimeMillis() < tokenExpiresAt;
    }
    
    /**
     * Ensure authenticated before API calls
     */
    private void ensureAuthenticated(Runnable onAuthenticated, SimpleCallback onError) {
        if (!CRM_ENABLED) {
            // CRM disabled - skip authentication
            onAuthenticated.run();
            return;
        }
        
        if (hasValidToken()) {
            onAuthenticated.run();
        } else {
            loginWithDefaultCredentials(new AuthCallback() {
                @Override
                public void onSuccess(String token) {
                    onAuthenticated.run();
                }
                
                @Override
                public void onError(String error) {
                    onError.onResult(error);
                }
            });
        }
    }
    
    private void loadSavedToken() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        accessToken = prefs.getString(PREF_ACCESS_TOKEN, null);
        tokenExpiresAt = prefs.getLong(PREF_TOKEN_EXPIRES, 0);
        
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            Logger.d(TAG, "Loaded saved token");
        } else {
            accessToken = null;
            tokenExpiresAt = 0;
        }
    }
    
    private void saveToken() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_ACCESS_TOKEN, accessToken)
                .putLong(PREF_TOKEN_EXPIRES, tokenExpiresAt)
                .apply();
    }
    
    public void clearToken() {
        accessToken = null;
        tokenExpiresAt = 0;
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
        }
    }
    
    // ============================================
    // LEADS API
    // ============================================
    
    /**
     * Create a new lead in CRM
     * POST /api/leads
     */
    public void createLead(LeadData lead, LeadCallback callback) {
        // If CRM is disabled, return mock success
        if (!CRM_ENABLED) {
            Logger.w(TAG, "CRM disabled - returning mock lead response");
            LeadResponse mockResponse = new LeadResponse();
            mockResponse.success = true;
            mockResponse.message = "Lead saved (CRM disabled - mock response)";
            mockResponse.id = (int) (System.currentTimeMillis() % 100000);
            mockResponse.data = lead;
            callback.onSuccess(mockResponse);
            return;
        }
        
        ensureAuthenticated(new Runnable() {
            @Override
            public void run() {
                String jsonBody = gson.toJson(lead);
                Logger.d(TAG, "Creating lead: %s", jsonBody);
                
                Request request = new Request.Builder()
                        .url(Constants.CRM_BASE_URL + "/leads")
                        .post(RequestBody.create(jsonBody, JSON))
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();
                
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Logger.e(e, "Create lead failed");
                        callback.onError("Network error: " + e.getMessage());
                    }
                    
                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String body = response.body() != null ? response.body().string() : "";
                        
                        if (response.isSuccessful()) {
                            try {
                                LeadResponse leadResponse = gson.fromJson(body, LeadResponse.class);
                                Logger.i(TAG, "Lead created: ID=%s", leadResponse != null ? leadResponse.id : "unknown");
                                callback.onSuccess(leadResponse);
                            } catch (Exception e) {
                                Logger.e(e, "Failed to parse lead response");
                                callback.onError("Failed to parse response");
                            }
                        } else if (response.code() == 401) {
                            clearToken();
                            Logger.w(TAG, "Token expired, retrying...");
                            createLead(lead, callback);
                        } else {
                            Logger.e(TAG, "Create lead failed: %d - %s", response.code(), body);
                            callback.onError("Failed: " + response.code() + " - " + body);
                        }
                    }
                });
            }
        }, new SimpleCallback() {
            @Override
            public void onResult(String error) {
                callback.onError("Authentication failed: " + error);
            }
        });
    }
    
    // ============================================
    // PACKAGES API
    // ============================================
    
    /**
     * Get packages from CRM
     * GET /api/packages
     */
    public void getPackages(PackageFilters filters, PackagesCallback callback) {
        // If CRM is disabled, return mock packages
        if (!CRM_ENABLED) {
            Logger.w(TAG, "CRM disabled - returning mock packages");
            PackagesResponse mockResponse = new PackagesResponse();
            mockResponse.success = true;
            mockResponse.packages = createMockPackages();
            mockResponse.total = mockResponse.packages.size();
            callback.onSuccess(mockResponse);
            return;
        }
        
        ensureAuthenticated(new Runnable() {
            @Override
            public void run() {
                StringBuilder url = new StringBuilder(Constants.CRM_BASE_URL + "/packages");
                
                boolean hasParams = false;
                if (filters != null) {
                    if (filters.destination != null && !filters.destination.isEmpty()) {
                        url.append(hasParams ? "&" : "?").append("destination=").append(filters.destination);
                        hasParams = true;
                    }
                    if (filters.packageType != null && !filters.packageType.isEmpty()) {
                        url.append(hasParams ? "&" : "?").append("packageType=").append(filters.packageType);
                        hasParams = true;
                    }
                    if (filters.minPrice > 0) {
                        url.append(hasParams ? "&" : "?").append("minPrice=").append(filters.minPrice);
                        hasParams = true;
                    }
                    if (filters.maxPrice > 0) {
                        url.append(hasParams ? "&" : "?").append("maxPrice=").append(filters.maxPrice);
                        hasParams = true;
                    }
                    if (filters.minNights > 0) {
                        url.append(hasParams ? "&" : "?").append("minNights=").append(filters.minNights);
                        hasParams = true;
                    }
                    if (filters.maxNights > 0) {
                        url.append(hasParams ? "&" : "?").append("maxNights=").append(filters.maxNights);
                        hasParams = true;
                    }
                    if (filters.limit > 0) {
                        url.append(hasParams ? "&" : "?").append("limit=").append(filters.limit);
                    }
                }
                
                Logger.d(TAG, "Getting packages: %s", url);
                
                Request request = new Request.Builder()
                        .url(url.toString())
                        .get()
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();
                
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Logger.e(e, "Get packages failed");
                        callback.onError("Network error: " + e.getMessage());
                    }
                    
                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String body = response.body() != null ? response.body().string() : "";
                        
                        if (response.isSuccessful()) {
                            try {
                                PackagesResponse packagesResponse = gson.fromJson(body, PackagesResponse.class);
                                Logger.i(TAG, "Packages fetched: %d", 
                                        packagesResponse != null && packagesResponse.packages != null 
                                                ? packagesResponse.packages.size() : 0);
                                callback.onSuccess(packagesResponse);
                            } catch (Exception e) {
                                Logger.e(e, "Failed to parse packages response");
                                callback.onError("Failed to parse response");
                            }
                        } else if (response.code() == 401) {
                            clearToken();
                            Logger.w(TAG, "Token expired, retrying...");
                            getPackages(filters, callback);
                        } else {
                            Logger.e(TAG, "Get packages failed: %d - %s", response.code(), body);
                            callback.onError("Failed: " + response.code());
                        }
                    }
                });
            }
        }, new SimpleCallback() {
            @Override
            public void onResult(String error) {
                callback.onError("Authentication failed: " + error);
            }
        });
    }
    
    /**
     * Get package by ID
     * GET /api/packages/:id
     */
    public void getPackageById(int packageId, PackageDetailCallback callback) {
        // If CRM is disabled, return mock package
        if (!CRM_ENABLED) {
            Logger.w(TAG, "CRM disabled - returning mock package detail");
            List<PackageDetail> mockPackages = createMockPackages();
            if (!mockPackages.isEmpty()) {
                PackageDetail mock = mockPackages.get(0);
                mock.id = packageId;
                callback.onSuccess(mock);
            } else {
                callback.onError("Package not found (CRM disabled)");
            }
            return;
        }
        
        ensureAuthenticated(new Runnable() {
            @Override
            public void run() {
                String url = Constants.CRM_BASE_URL + "/packages/" + packageId;
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();
                
                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Logger.e(e, "Get package failed");
                        callback.onError("Network error: " + e.getMessage());
                    }
                    
                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String body = response.body() != null ? response.body().string() : "";
                        
                        if (response.isSuccessful()) {
                            try {
                                PackageDetail pkg = gson.fromJson(body, PackageDetail.class);
                                callback.onSuccess(pkg);
                            } catch (Exception e) {
                                callback.onError("Failed to parse response");
                            }
                        } else if (response.code() == 401) {
                            clearToken();
                            getPackageById(packageId, callback);
                        } else {
                            callback.onError("Failed: " + response.code());
                        }
                    }
                });
            }
        }, new SimpleCallback() {
            @Override
            public void onResult(String error) {
                callback.onError("Authentication failed: " + error);
            }
        });
    }
    
    /**
     * Search packages by keyword
     */
    public void searchPackages(String query, PackagesCallback callback) {
        PackageFilters filters = new PackageFilters();
        filters.destination = query;
        filters.limit = 10;
        getPackages(filters, callback);
    }
    
    // ============================================
    // MOCK DATA (for testing when CRM is disabled)
    // ============================================
    
    private List<PackageDetail> createMockPackages() {
        java.util.ArrayList<PackageDetail> packages = new java.util.ArrayList<>();
        
        PackageDetail pkg1 = new PackageDetail();
        pkg1.id = 1;
        pkg1.packageName = "Goa Beach Paradise";
        pkg1.description = "Enjoy beautiful beaches of Goa with this amazing package";
        pkg1.sellingPrice = 25000;
        pkg1.currency = "INR";
        pkg1.totalNights = 4;
        pkg1.totalDays = 5;
        pkg1.minPax = 2;
        pkg1.maxPax = 6;
        pkg1.destinations = java.util.Arrays.asList("North Goa", "South Goa");
        pkg1.inclusions = java.util.Arrays.asList("Hotel Stay", "Breakfast", "Airport Transfer", "Sightseeing");
        packages.add(pkg1);
        
        PackageDetail pkg2 = new PackageDetail();
        pkg2.id = 2;
        pkg2.packageName = "Kerala Backwaters Escape";
        pkg2.description = "Experience the serene backwaters of Kerala";
        pkg2.sellingPrice = 35000;
        pkg2.currency = "INR";
        pkg2.totalNights = 5;
        pkg2.totalDays = 6;
        pkg2.minPax = 2;
        pkg2.maxPax = 4;
        pkg2.destinations = java.util.Arrays.asList("Kochi", "Alleppey", "Munnar");
        pkg2.inclusions = java.util.Arrays.asList("Hotel Stay", "All Meals", "Houseboat", "Transfers");
        packages.add(pkg2);
        
        PackageDetail pkg3 = new PackageDetail();
        pkg3.id = 3;
        pkg3.packageName = "Rajasthan Royal Tour";
        pkg3.description = "Explore the royal heritage of Rajasthan";
        pkg3.sellingPrice = 45000;
        pkg3.currency = "INR";
        pkg3.totalNights = 6;
        pkg3.totalDays = 7;
        pkg3.minPax = 2;
        pkg3.maxPax = 8;
        pkg3.destinations = java.util.Arrays.asList("Jaipur", "Udaipur", "Jodhpur");
        pkg3.inclusions = java.util.Arrays.asList("Heritage Hotels", "Breakfast & Dinner", "Private Car", "Guide");
        packages.add(pkg3);
        
        return packages;
    }
    
    // ============================================
    // DATA CLASSES
    // ============================================
    
    public static class LoginRequest {
        public String email;
        public String password;
        
        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }
    
    public static class LoginResponse {
        @SerializedName("accessToken")
        public String accessToken;
        
        @SerializedName("access_token")
        public String access_token;
        
        @SerializedName("token")
        public String token;
        
        public String getToken() {
            if (accessToken != null) return accessToken;
            if (access_token != null) return access_token;
            return token;
        }
    }
    
    /**
     * Lead data matching CRM API schema (camelCase)
     */
    public static class LeadData {
        public String name;
        public String email;
        public String mobile;
        public String destination;
        public String nearestAirport;
        public String arrivalCity;
        public String departureCity;
        public String travelDate;
        public String expectedDate;
        public String journeyStartDate;
        public String journeyEndDate;
        public Integer durationNights;
        public Integer durationDays;
        public Integer adults;
        public Integer children;
        public Integer infants;
        public String hotelType;
        public Integer totalRooms;
        public Integer extraMattress;
        public String mealPlan;
        public Boolean videographyPhotography;
        public String specialRequirement;
        public String journeyType;
        public Integer assignedTo;
        public String source;
        public String sourceType;
        public String utmSource;
        public String utmMedium;
        public String utmCampaign;
        public String aiSummary;
        public String notes;
        public String remarks;
        public Double estimatedValue;
    }
    
    public static class LeadResponse {
        public boolean success;
        public String message;
        public Integer id;
        public LeadData data;
    }
    
    public static class PackageFilters {
        public String destination;
        public String packageType;
        public double minPrice;
        public double maxPrice;
        public int minNights;
        public int maxNights;
        public int limit = 10;
    }
    
    public static class PackagesResponse {
        public boolean success;
        public List<PackageDetail> packages;
        public int total;
        public int page;
        public int limit;
    }
    
    public static class PackageDetail {
        public int id;
        
        @SerializedName("packageName")
        public String packageName;
        
        @SerializedName("package_name")
        public String package_name;
        
        @SerializedName("packageType")
        public String packageType;
        
        @SerializedName("package_type")
        public String package_type;
        
        public String description;
        
        @SerializedName("basePrice")
        public double basePrice;
        
        @SerializedName("base_price")
        public double base_price;
        
        @SerializedName("sellingPrice")
        public double sellingPrice;
        
        @SerializedName("selling_price")
        public double selling_price;
        
        public String currency;
        
        @SerializedName("totalNights")
        public int totalNights;
        
        @SerializedName("total_nights")
        public int total_nights;
        
        @SerializedName("totalDays")
        public int totalDays;
        
        @SerializedName("total_days")
        public int total_days;
        
        @SerializedName("minPax")
        public int minPax;
        
        @SerializedName("min_pax")
        public int min_pax;
        
        @SerializedName("maxPax")
        public int maxPax;
        
        @SerializedName("max_pax")
        public int max_pax;
        
        public List<String> destinations;
        public List<String> inclusions;
        public List<String> exclusions;
        public List<String> tags;
        public String status;
        
        public String getDisplayName() {
            return packageName != null ? packageName : package_name;
        }
        
        public double getPrice() {
            double price = sellingPrice > 0 ? sellingPrice : selling_price;
            if (price <= 0) {
                price = basePrice > 0 ? basePrice : base_price;
            }
            return price;
        }
        
        public int getNights() {
            return totalNights > 0 ? totalNights : total_nights;
        }
        
        public int getDays() {
            return totalDays > 0 ? totalDays : total_days;
        }
        
        public String toVoiceDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(getDisplayName());
            sb.append(", ").append(getNights()).append(" nights ").append(getDays()).append(" days");
            sb.append(", starting from ").append(currency != null ? currency : "INR").append(" ");
            sb.append(String.format("%.0f", getPrice())).append(" per person");
            
            if (destinations != null && !destinations.isEmpty()) {
                sb.append(", covering ");
                sb.append(String.join(", ", destinations));
            }
            
            return sb.toString();
        }
        
        public String toDetailedVoiceDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(getDisplayName()).append(". ");
            
            if (description != null && !description.isEmpty()) {
                String shortDesc = description.length() > 150 
                        ? description.substring(0, 150) + "..." 
                        : description;
                sb.append(shortDesc).append(" ");
            }
            
            sb.append("This is a ").append(getNights()).append(" nights ").append(getDays()).append(" days package");
            sb.append(" starting from ").append(currency != null ? currency : "INR").append(" ");
            sb.append(String.format("%.0f", getPrice())).append(" per person. ");
            
            if (inclusions != null && !inclusions.isEmpty()) {
                sb.append("The package includes: ");
                int maxInclusions = Math.min(inclusions.size(), 4);
                for (int i = 0; i < maxInclusions; i++) {
                    sb.append(inclusions.get(i));
                    if (i < maxInclusions - 1) sb.append(", ");
                }
                if (inclusions.size() > 4) {
                    sb.append(" and more");
                }
                sb.append(". ");
            }
            
            int minP = minPax > 0 ? minPax : min_pax;
            int maxP = maxPax > 0 ? maxPax : max_pax;
            if (minP > 0 && maxP > 0) {
                sb.append("This package is ideal for ").append(minP).append(" to ").append(maxP).append(" travelers.");
            }
            
            return sb.toString();
        }
    }
    
    // ============================================
    // CALLBACKS
    // ============================================
    
    /**
     * Simple callback for single result (functional interface)
     */
    public interface SimpleCallback {
        void onResult(String result);
    }
    
    public interface AuthCallback {
        void onSuccess(String token);
        void onError(String error);
    }
    
    public interface LeadCallback {
        void onSuccess(LeadResponse response);
        void onError(String error);
    }
    
    public interface PackagesCallback {
        void onSuccess(PackagesResponse response);
        void onError(String error);
    }
    
    public interface PackageDetailCallback {
        void onSuccess(PackageDetail packageDetail);
        void onError(String error);
    }
}