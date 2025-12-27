package com.tripandevent.sanbotvoice.api.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Model for quote request to be generated and saved.
 */
public class QuoteRequest {
    
    @SerializedName("customerName")
    private String customerName;
    
    @SerializedName("customerEmail")
    private String customerEmail;
    
    @SerializedName("customerPhone")
    private String customerPhone;
    
    @SerializedName("items")
    private List<QuoteItem> items;
    
    @SerializedName("totalAmount")
    private double totalAmount;
    
    @SerializedName("currency")
    private String currency;
    
    @SerializedName("notes")
    private String notes;
    
    @SerializedName("sessionId")
    private String sessionId;
    
    @SerializedName("validUntil")
    private String validUntil;
    
    private QuoteRequest(Builder builder) {
        this.customerName = builder.customerName;
        this.customerEmail = builder.customerEmail;
        this.customerPhone = builder.customerPhone;
        this.items = builder.items;
        this.totalAmount = builder.totalAmount;
        this.currency = builder.currency;
        this.notes = builder.notes;
        this.sessionId = builder.sessionId;
        this.validUntil = builder.validUntil;
    }
    
    // Getters
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerPhone() { return customerPhone; }
    public List<QuoteItem> getItems() { return items; }
    public double getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getNotes() { return notes; }
    public String getSessionId() { return sessionId; }
    public String getValidUntil() { return validUntil; }
    
    /**
     * Quote line item
     */
    public static class QuoteItem {
        @SerializedName("description")
        private String description;
        
        @SerializedName("quantity")
        private int quantity;
        
        @SerializedName("unitPrice")
        private double unitPrice;
        
        @SerializedName("total")
        private double total;
        
        public QuoteItem(String description, int quantity, double unitPrice) {
            this.description = description;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.total = quantity * unitPrice;
        }
        
        public String getDescription() { return description; }
        public int getQuantity() { return quantity; }
        public double getUnitPrice() { return unitPrice; }
        public double getTotal() { return total; }
    }
    
    /**
     * Builder for QuoteRequest
     */
    public static class Builder {
        private String customerName;
        private String customerEmail;
        private String customerPhone;
        private List<QuoteItem> items;
        private double totalAmount;
        private String currency = "USD";
        private String notes;
        private String sessionId;
        private String validUntil;
        
        public Builder customerName(String customerName) {
            this.customerName = customerName;
            return this;
        }
        
        public Builder customerEmail(String customerEmail) {
            this.customerEmail = customerEmail;
            return this;
        }
        
        public Builder customerPhone(String customerPhone) {
            this.customerPhone = customerPhone;
            return this;
        }
        
        public Builder items(List<QuoteItem> items) {
            this.items = items;
            // Calculate total
            if (items != null) {
                this.totalAmount = items.stream()
                    .mapToDouble(QuoteItem::getTotal)
                    .sum();
            }
            return this;
        }
        
        public Builder totalAmount(double totalAmount) {
            this.totalAmount = totalAmount;
            return this;
        }
        
        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }
        
        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }
        
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        
        public Builder validUntil(String validUntil) {
            this.validUntil = validUntil;
            return this;
        }
        
        public QuoteRequest build() {
            return new QuoteRequest(this);
        }
    }
}
