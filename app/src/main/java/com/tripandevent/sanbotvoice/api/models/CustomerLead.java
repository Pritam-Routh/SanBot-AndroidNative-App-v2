package com.tripandevent.sanbotvoice.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * Model for customer lead data to be saved to CRM.
 */
public class CustomerLead {
    
    @SerializedName("name")
    private String name;
    
    @SerializedName("email")
    private String email;
    
    @SerializedName("phone")
    private String phone;
    
    @SerializedName("company")
    private String company;
    
    @SerializedName("interests")
    private String interests;
    
    @SerializedName("notes")
    private String notes;
    
    @SerializedName("sessionId")
    private String sessionId;
    
    @SerializedName("source")
    private String source;
    
    private CustomerLead(Builder builder) {
        this.name = builder.name;
        this.email = builder.email;
        this.phone = builder.phone;
        this.company = builder.company;
        this.interests = builder.interests;
        this.notes = builder.notes;
        this.sessionId = builder.sessionId;
        this.source = builder.source;
    }
    
    // Getters
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getCompany() { return company; }
    public String getInterests() { return interests; }
    public String getNotes() { return notes; }
    public String getSessionId() { return sessionId; }
    public String getSource() { return source; }
    
    /**
     * Builder for CustomerLead
     */
    public static class Builder {
        private String name;
        private String email;
        private String phone;
        private String company;
        private String interests;
        private String notes;
        private String sessionId;
        private String source = "sanbot_voice_agent";
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder email(String email) {
            this.email = email;
            return this;
        }
        
        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }
        
        public Builder company(String company) {
            this.company = company;
            return this;
        }
        
        public Builder interests(String interests) {
            this.interests = interests;
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
        
        public Builder source(String source) {
            this.source = source;
            return this;
        }
        
        public CustomerLead build() {
            return new CustomerLead(this);
        }
    }
    
    @Override
    public String toString() {
        return "CustomerLead{" +
                "name='" + name + '\'' +
                ", email='" + email + '\'' +
                ", phone='" + phone + '\'' +
                ", company='" + company + '\'' +
                ", sessionId='" + sessionId + '\'' +
                '}';
    }
}
