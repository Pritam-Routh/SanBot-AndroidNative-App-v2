package com.tripandevent.sanbotvoice.functions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tripandevent.sanbotvoice.openai.events.ClientEvents;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for function handlers.
 * Manages the mapping between function names and their handlers.
 */
public class FunctionRegistry {
    
    private static final String TAG = "FunctionRegistry";
    
    private final Map<String, FunctionHandler> handlers = new HashMap<>();
    
    public FunctionRegistry() {
        // Register default handlers
    }
    
    /**
     * Register a function handler
     * 
     * @param functionName Name of the function
     * @param handler Handler for the function
     */
    public void registerHandler(String functionName, FunctionHandler handler) {
        handlers.put(functionName, handler);
        Logger.d(TAG, "Registered handler for: %s", functionName);
    }
    
    /**
     * Get handler for a function
     * 
     * @param functionName Name of the function
     * @return Handler or null if not found
     */
    public FunctionHandler getHandler(String functionName) {
        return handlers.get(functionName);
    }
    
    /**
     * Check if a handler exists for a function
     */
    public boolean hasHandler(String functionName) {
        return handlers.containsKey(functionName);
    }
    
    /**
     * Get all registered function names
     */
    public List<String> getRegisteredFunctions() {
        return new ArrayList<>(handlers.keySet());
    }
    
    /**
     * Get tool definitions for OpenAI API
     * Returns a JsonArray of tool definitions
     */
    public JsonArray getToolDefinitionsAsJsonArray() {
        JsonArray tools = new JsonArray();
        
        // save_customer_lead
        tools.add(createToolDefinition(
            "save_customer_lead",
            "Save customer contact information and travel requirements to CRM",
            createLeadParams()
        ));
        
        // get_packages
        tools.add(createToolDefinition(
            "get_packages",
            "Search and get travel packages from the catalog based on filters",
            createPackageParams()
        ));
        
        // disconnect_call
        tools.add(createToolDefinition(
            "disconnect_call",
            "End the conversation when customer is done or wants to leave",
            createEmptyParams()
        ));
        
        return tools;
    }
    
    /**
     * Get tool definitions as List
     * For compatibility with code that expects List<ToolDefinition>
     */
    public List<ClientEvents.ToolDefinition> getToolDefinitions() {
        List<ClientEvents.ToolDefinition> tools = new ArrayList<>();
        
        tools.add(new ClientEvents.ToolDefinition(
            "save_customer_lead",
            "Save customer contact information and travel requirements to CRM",
            createLeadParams()
        ));
        
        tools.add(new ClientEvents.ToolDefinition(
            "get_packages",
            "Search and get travel packages from the catalog based on filters",
            createPackageParams()
        ));
        
        tools.add(new ClientEvents.ToolDefinition(
            "disconnect_call",
            "End the conversation when customer is done or wants to leave",
            createEmptyParams()
        ));
        
        return tools;
    }
    
    /**
     * Create a tool definition JsonObject
     */
    private JsonObject createToolDefinition(String name, String description, JsonObject parameters) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("parameters", parameters);
        return tool;
    }
    
    /**
     * Create parameters for save_customer_lead
     */
    private JsonObject createLeadParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        
        JsonObject props = new JsonObject();
        
        // name
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Customer's full name");
        props.add("name", name);
        
        // email
        JsonObject email = new JsonObject();
        email.addProperty("type", "string");
        email.addProperty("description", "Customer's email address");
        props.add("email", email);
        
        // mobile
        JsonObject mobile = new JsonObject();
        mobile.addProperty("type", "string");
        mobile.addProperty("description", "Customer's mobile/phone number");
        props.add("mobile", mobile);
        
        // destination
        JsonObject destination = new JsonObject();
        destination.addProperty("type", "string");
        destination.addProperty("description", "Desired travel destination");
        props.add("destination", destination);
        
        // travel_date
        JsonObject travelDate = new JsonObject();
        travelDate.addProperty("type", "string");
        travelDate.addProperty("description", "Preferred travel date (YYYY-MM-DD format)");
        props.add("travel_date", travelDate);
        
        // adults
        JsonObject adults = new JsonObject();
        adults.addProperty("type", "integer");
        adults.addProperty("description", "Number of adult travelers");
        props.add("adults", adults);
        
        // children
        JsonObject children = new JsonObject();
        children.addProperty("type", "integer");
        children.addProperty("description", "Number of children");
        props.add("children", children);
        
        // nights
        JsonObject nights = new JsonObject();
        nights.addProperty("type", "integer");
        nights.addProperty("description", "Number of nights for the trip");
        props.add("nights", nights);
        
        // hotel_type
        JsonObject hotelType = new JsonObject();
        hotelType.addProperty("type", "string");
        hotelType.addProperty("description", "Preferred hotel category");
        JsonArray hotelEnum = new JsonArray();
        hotelEnum.add("budget");
        hotelEnum.add("standard");
        hotelEnum.add("luxury");
        hotelEnum.add("premium");
        hotelType.add("enum", hotelEnum);
        props.add("hotel_type", hotelType);
        
        // budget
        JsonObject budget = new JsonObject();
        budget.addProperty("type", "number");
        budget.addProperty("description", "Customer's budget in INR");
        props.add("budget", budget);
        
        // special_requirements
        JsonObject special = new JsonObject();
        special.addProperty("type", "string");
        special.addProperty("description", "Any special requirements (honeymoon, anniversary, dietary, etc.)");
        props.add("special_requirements", special);
        
        params.add("properties", props);
        
        // Required fields
        JsonArray required = new JsonArray();
        required.add("name");
        params.add("required", required);
        
        return params;
    }
    
    /**
     * Create parameters for get_packages
     */
    private JsonObject createPackageParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        
        JsonObject props = new JsonObject();
        
        // destination
        JsonObject destination = new JsonObject();
        destination.addProperty("type", "string");
        destination.addProperty("description", "Destination to filter packages");
        props.add("destination", destination);
        
        // budget_min
        JsonObject budgetMin = new JsonObject();
        budgetMin.addProperty("type", "number");
        budgetMin.addProperty("description", "Minimum budget in INR");
        props.add("budget_min", budgetMin);
        
        // budget_max
        JsonObject budgetMax = new JsonObject();
        budgetMax.addProperty("type", "number");
        budgetMax.addProperty("description", "Maximum budget in INR");
        props.add("budget_max", budgetMax);
        
        // nights
        JsonObject nights = new JsonObject();
        nights.addProperty("type", "integer");
        nights.addProperty("description", "Preferred number of nights");
        props.add("nights", nights);
        
        // package_type
        JsonObject packageType = new JsonObject();
        packageType.addProperty("type", "string");
        packageType.addProperty("description", "Package type: with_flight or without_flight");
        JsonArray typeEnum = new JsonArray();
        typeEnum.add("with_flight");
        typeEnum.add("without_flight");
        packageType.add("enum", typeEnum);
        props.add("package_type", packageType);
        
        // limit
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "Maximum number of packages to return (default 5)");
        props.add("limit", limit);
        
        params.add("properties", props);
        params.add("required", new JsonArray());
        
        return params;
    }
    
    /**
     * Create empty parameters object
     */
    private JsonObject createEmptyParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", new JsonObject());
        params.add("required", new JsonArray());
        return params;
    }
}