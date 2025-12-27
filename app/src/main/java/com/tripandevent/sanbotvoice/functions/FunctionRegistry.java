package com.tripandevent.sanbotvoice.functions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tripandevent.sanbotvoice.openai.events.ClientEvents;
import com.tripandevent.sanbotvoice.utils.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all available functions (tools) for OpenAI Realtime API.
 * 
 * This defines what functions the AI can call during a conversation.
 * Each function has a name, description, and parameter schema.
 */
public class FunctionRegistry {
    
    private final Map<String, FunctionHandler> handlers = new HashMap<>();
    
    /**
     * Initialize registry with all available functions
     */
    public FunctionRegistry() {
        // Register all function handlers
        registerHandler(Constants.FUNCTION_SAVE_CUSTOMER_LEAD, new SaveCustomerLeadFunction());
        registerHandler(Constants.FUNCTION_CREATE_QUOTE, new CreateQuoteFunction());
        registerHandler(Constants.FUNCTION_DISCONNECT_CALL, new DisconnectCallFunction());
    }
    
    /**
     * Register a function handler
     */
    public void registerHandler(String functionName, FunctionHandler handler) {
        handlers.put(functionName, handler);
    }
    
    /**
     * Get handler for a function
     */
    public FunctionHandler getHandler(String functionName) {
        return handlers.get(functionName);
    }
    
    /**
     * Check if a function is registered
     */
    public boolean hasFunction(String functionName) {
        return handlers.containsKey(functionName);
    }
    
    /**
     * Get all tool definitions for session configuration
     */
    public List<ClientEvents.ToolDefinition> getToolDefinitions() {
        List<ClientEvents.ToolDefinition> tools = new ArrayList<>();
        
        // Save Customer Lead function
        tools.add(new ClientEvents.ToolDefinition(
            Constants.FUNCTION_SAVE_CUSTOMER_LEAD,
            "Save customer contact information to the CRM. Call this when you have collected " +
            "the customer's name, email, or phone number. You can call this multiple times " +
            "as you collect more information.",
            buildSaveLeadParameters()
        ));
        
        // Create Quote function
        tools.add(new ClientEvents.ToolDefinition(
            Constants.FUNCTION_CREATE_QUOTE,
            "Generate and save a price quote for the customer. Call this when the customer " +
            "requests a quote or when you have enough information to provide pricing.",
            buildCreateQuoteParameters()
        ));
        
        // Disconnect Call function
        tools.add(new ClientEvents.ToolDefinition(
            Constants.FUNCTION_DISCONNECT_CALL,
            "End the current conversation. Call this when the customer says goodbye, " +
            "indicates they want to end the conversation, or when the interaction is complete.",
            buildDisconnectParameters()
        ));
        
        return tools;
    }
    
    /**
     * Build parameters schema for save_customer_lead function
     */
    private JsonObject buildSaveLeadParameters() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // Name property
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Customer's full name");
        properties.add("name", name);
        
        // Email property
        JsonObject email = new JsonObject();
        email.addProperty("type", "string");
        email.addProperty("description", "Customer's email address");
        properties.add("email", email);
        
        // Phone property
        JsonObject phone = new JsonObject();
        phone.addProperty("type", "string");
        phone.addProperty("description", "Customer's phone number");
        properties.add("phone", phone);
        
        // Company property
        JsonObject company = new JsonObject();
        company.addProperty("type", "string");
        company.addProperty("description", "Customer's company or organization name");
        properties.add("company", company);
        
        // Interests property
        JsonObject interests = new JsonObject();
        interests.addProperty("type", "string");
        interests.addProperty("description", "What the customer is interested in");
        properties.add("interests", interests);
        
        // Notes property
        JsonObject notes = new JsonObject();
        notes.addProperty("type", "string");
        notes.addProperty("description", "Additional notes about the customer or conversation");
        properties.add("notes", notes);
        
        params.add("properties", properties);
        
        // At least one contact method required
        JsonArray required = new JsonArray();
        required.add("name");
        params.add("required", required);
        
        return params;
    }
    
    /**
     * Build parameters schema for create_quote function
     */
    private JsonObject buildCreateQuoteParameters() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // Customer name
        JsonObject customerName = new JsonObject();
        customerName.addProperty("type", "string");
        customerName.addProperty("description", "Customer's name for the quote");
        properties.add("customerName", customerName);
        
        // Customer email
        JsonObject customerEmail = new JsonObject();
        customerEmail.addProperty("type", "string");
        customerEmail.addProperty("description", "Customer's email to send the quote");
        properties.add("customerEmail", customerEmail);
        
        // Items array
        JsonObject items = new JsonObject();
        items.addProperty("type", "array");
        items.addProperty("description", "List of items/services to quote");
        
        JsonObject itemSchema = new JsonObject();
        itemSchema.addProperty("type", "object");
        
        JsonObject itemProperties = new JsonObject();
        
        JsonObject description = new JsonObject();
        description.addProperty("type", "string");
        description.addProperty("description", "Description of the item or service");
        itemProperties.add("description", description);
        
        JsonObject quantity = new JsonObject();
        quantity.addProperty("type", "integer");
        quantity.addProperty("description", "Quantity");
        itemProperties.add("quantity", quantity);
        
        JsonObject unitPrice = new JsonObject();
        unitPrice.addProperty("type", "number");
        unitPrice.addProperty("description", "Price per unit");
        itemProperties.add("unitPrice", unitPrice);
        
        itemSchema.add("properties", itemProperties);
        items.add("items", itemSchema);
        
        properties.add("items", items);
        
        // Notes
        JsonObject notes = new JsonObject();
        notes.addProperty("type", "string");
        notes.addProperty("description", "Additional notes for the quote");
        properties.add("notes", notes);
        
        params.add("properties", properties);
        
        JsonArray required = new JsonArray();
        required.add("customerName");
        required.add("items");
        params.add("required", required);
        
        return params;
    }
    
    /**
     * Build parameters schema for disconnect_call function
     */
    private JsonObject buildDisconnectParameters() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        
        JsonObject properties = new JsonObject();
        
        // Reason property
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        reason.addProperty("description", "Reason for ending the call");
        JsonArray reasonEnum = new JsonArray();
        reasonEnum.add("customer_goodbye");
        reasonEnum.add("task_completed");
        reasonEnum.add("customer_request");
        reasonEnum.add("other");
        reason.add("enum", reasonEnum);
        properties.add("reason", reason);
        
        // Summary property
        JsonObject summary = new JsonObject();
        summary.addProperty("type", "string");
        summary.addProperty("description", "Brief summary of the conversation");
        properties.add("summary", summary);
        
        params.add("properties", properties);
        
        JsonArray required = new JsonArray();
        required.add("reason");
        params.add("required", required);
        
        return params;
    }
}
