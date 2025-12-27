package com.tripandevent.sanbotvoice.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tripandevent.sanbotvoice.BuildConfig;
import com.tripandevent.sanbotvoice.utils.Constants;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * API client for TripAndEvent backend.
 * Handles authentication, retries, and error handling.
 */
public class ApiClient {
    
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;
    private static final int MAX_RETRIES = 3;
    
    private static volatile ApiClient instance;
    private final TripAndEventApi api;
    private final OkHttpClient httpClient;
    
    private ApiClient() {
        httpClient = createOkHttpClient();
        Retrofit retrofit = createRetrofit();
        api = retrofit.create(TripAndEventApi.class);
    }
    
    /**
     * Get singleton instance
     */
    public static ApiClient getInstance() {
        if (instance == null) {
            synchronized (ApiClient.class) {
                if (instance == null) {
                    instance = new ApiClient();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get the API interface
     */
    public TripAndEventApi getApi() {
        return api;
    }
    
    /**
     * Get the OkHttpClient for WebRTC SDP exchange
     */
    public OkHttpClient getHttpClient() {
        return httpClient;
    }
    
    /**
     * Get authorization header value
     */
    public String getAuthHeader() {
        return "Bearer " + Constants.TRIPANDEVENT_API_TOKEN;
    }
    
    /**
     * Create OkHttpClient with interceptors
     */
    private OkHttpClient createOkHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(new RetryInterceptor(MAX_RETRIES))
            .addInterceptor(new ErrorHandlingInterceptor());
        
        // Add logging interceptor in debug builds
        if (BuildConfig.ENABLE_DEBUG_LOGGING) {
            HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Logger.d("OkHttp", message)
            );
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(loggingInterceptor);
        }
        
        return builder.build();
    }
    
    /**
     * Create Retrofit instance
     */
    private Retrofit createRetrofit() {
        Gson gson = new GsonBuilder()
            .setLenient()
            .create();
        
        return new Retrofit.Builder()
            .baseUrl(Constants.BACKEND_BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build();
    }
    
    /**
     * Interceptor for automatic retries with exponential backoff
     */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetries;
        
        RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;
            
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    // Close previous response if exists
                    if (response != null) {
                        response.close();
                    }
                    
                    response = chain.proceed(request);
                    
                    // Success or client error (4xx) - don't retry
                    if (response.isSuccessful() || response.code() < 500) {
                        return response;
                    }
                    
                    // Server error (5xx) - retry
                    Logger.w("ApiClient", "Server error %d, attempt %d/%d", 
                            response.code(), attempt + 1, maxRetries + 1);
                    
                } catch (IOException e) {
                    lastException = e;
                    Logger.w("ApiClient", "Network error, attempt %d/%d: %s", 
                            attempt + 1, maxRetries + 1, e.getMessage());
                }
                
                // Wait before retry (exponential backoff)
                if (attempt < maxRetries) {
                    try {
                        long delay = (long) Math.pow(2, attempt) * 1000;
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", e);
                    }
                }
            }
            
            // Return last response or throw last exception
            if (response != null) {
                return response;
            }
            throw lastException != null ? lastException : new IOException("Request failed after retries");
        }
    }
    
    /**
     * Interceptor for centralized error handling and logging
     */
    private static class ErrorHandlingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            
            Logger.d("ApiClient", "Request: %s %s", request.method(), request.url());
            
            long startTime = System.currentTimeMillis();
            Response response;
            
            try {
                response = chain.proceed(request);
            } catch (IOException e) {
                long duration = System.currentTimeMillis() - startTime;
                Logger.e("ApiClient", "Request failed after %dms: %s", duration, e.getMessage());
                throw e;
            }
            
            long duration = System.currentTimeMillis() - startTime;
            Logger.d("ApiClient", "Response: %d in %dms", response.code(), duration);
            
            return response;
        }
    }
}
