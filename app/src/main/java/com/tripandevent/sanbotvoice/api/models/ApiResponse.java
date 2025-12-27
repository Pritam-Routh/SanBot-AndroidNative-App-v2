package com.tripandevent.sanbotvoice.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * Generic API response wrapper for tRPC endpoints.
 */
public class ApiResponse<T> {
    
    @SerializedName("result")
    private Result<T> result;
    
    @SerializedName("error")
    private ApiError error;
    
    public T getData() {
        return result != null ? result.data : null;
    }
    
    public ApiError getError() {
        return error;
    }
    
    public boolean isSuccess() {
        return error == null && result != null;
    }
    
    public static class Result<T> {
        @SerializedName("data")
        T data;
    }
    
    public static class ApiError {
        @SerializedName("message")
        private String message;
        
        @SerializedName("code")
        private String code;
        
        public String getMessage() { return message; }
        public String getCode() { return code; }
        
        @Override
        public String toString() {
            return "ApiError{code='" + code + "', message='" + message + "'}";
        }
    }
}
