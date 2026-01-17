package com.tripandevent.sanbotvoice.openai.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Client events for OpenAI Realtime API (GA version).
 * Creates JSON events to send to the server.
 */
public class ClientEvents {
    
    private static int eventCounter = 0;
    
    /**
     * Generate unique event ID
     */
    private static synchronized String generateEventId() {
        return "evt_" + System.currentTimeMillis() + "_" + (++eventCounter);
    }
    
    // ============================================
    // INNER CLASSES
    // ============================================
    
    /**
     * Session configuration class
     */
    public static class SessionConfig {
        public String instructions;
        public String voice = "alloy";
        public JsonArray tools;
        public String toolChoice = "auto";
        public String inputAudioFormat = "pcm16";
        public String outputAudioFormat = "pcm16";
        public int sampleRate = 24000;
        
        public SessionConfig() {}
        
        public SessionConfig(String instructions) {
            this.instructions = instructions;
        }
    }
    
    /**
     * Tool definition class for function calling
     * Used by FunctionRegistry to define available tools
     */
    public static class ToolDefinition {
        public String type = "function";
        public String name;
        public String description;
        public JsonObject parameters;
        
        public ToolDefinition() {}
        
        public ToolDefinition(String name, String description, JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
        
        /**
         * Convert to JsonObject for API
         */
        public JsonObject toJson() {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", type);
            tool.addProperty("name", name);
            tool.addProperty("description", description);
            if (parameters != null) {
                tool.add("parameters", parameters);
            }
            return tool;
        }
    }
    
    // ============================================
    // SESSION EVENTS
    // ============================================
    
    /**
     * Create session.update event from SessionConfig
     */
    public static String sessionUpdate(SessionConfig config) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "session.update");
        event.addProperty("event_id", generateEventId());
        
        JsonObject session = new JsonObject();
        
        // GA API format
        session.addProperty("type", "realtime");
        
        // Instructions
        if (config.instructions != null) {
            session.addProperty("instructions", config.instructions);
        }
        
        // Audio input configuration
        JsonObject audio = new JsonObject();
        JsonObject input = new JsonObject();
        JsonObject inputFormat = new JsonObject();
        inputFormat.addProperty("type", "audio/" + config.inputAudioFormat);
        inputFormat.addProperty("sample_rate", config.sampleRate);
        input.add("format", inputFormat);
        
        // Turn detection
        JsonObject turnDetection = new JsonObject();
        turnDetection.addProperty("type", "server_vad");
        turnDetection.addProperty("threshold", 0.5);
        turnDetection.addProperty("prefix_padding_ms", 300);
        turnDetection.addProperty("silence_duration_ms", 500);
        input.add("turn_detection", turnDetection);
        
        audio.add("input", input);
        
        // Audio output configuration
        JsonObject output = new JsonObject();
        JsonObject outputFormat = new JsonObject();
        outputFormat.addProperty("type", "audio/" + config.outputAudioFormat);
        outputFormat.addProperty("sample_rate", config.sampleRate);
        output.add("format", outputFormat);
        output.addProperty("voice", config.voice);
        audio.add("output", output);
        
        session.add("audio", audio);
        
        // Tools
        if (config.tools != null && config.tools.size() > 0) {
            session.add("tools", config.tools);
            session.addProperty("tool_choice", config.toolChoice);
        }
        
        event.add("session", session);
        
        return event.toString();
    }
    
    /**
     * Create session.update with tools and robot motion support
     */
    public static String sessionUpdateWithTools(String instructions, JsonArray tools) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "session.update");
        event.addProperty("event_id", generateEventId());
        
        JsonObject session = new JsonObject();
        session.addProperty("type", "realtime");
        session.addProperty("instructions", instructions);
        
        // Audio configuration
        JsonObject audio = new JsonObject();
        
        // Input
        JsonObject input = new JsonObject();
        JsonObject inputFormat = new JsonObject();
        inputFormat.addProperty("type", "audio/pcm16");
        inputFormat.addProperty("sample_rate", 24000);
        input.add("format", inputFormat);
        
        JsonObject turnDetection = new JsonObject();
        turnDetection.addProperty("type", "server_vad");
        turnDetection.addProperty("threshold", 0.5);
        turnDetection.addProperty("prefix_padding_ms", 300);
        turnDetection.addProperty("silence_duration_ms", 500);
        input.add("turn_detection", turnDetection);
        audio.add("input", input);
        
        // Output
        JsonObject output = new JsonObject();
        JsonObject outputFormat = new JsonObject();
        outputFormat.addProperty("type", "audio/pcm16");
        outputFormat.addProperty("sample_rate", 24000);
        output.add("format", outputFormat);
        output.addProperty("voice", "alloy");
        audio.add("output", output);
        
        session.add("audio", audio);
        
        // Tools
        if (tools != null && tools.size() > 0) {
            session.add("tools", tools);
            session.addProperty("tool_choice", "auto");
        }
        
        event.add("session", session);
        
        return event.toString();
    }
    
    /**
     * Create session.update with robot motion tools
     */
    public static String sessionUpdateWithRobotMotion(String baseInstructions) {
        String robotInstructions = buildRobotAwareInstructions(baseInstructions);
        JsonArray tools = getAllTools();
        return sessionUpdateWithTools(robotInstructions, tools);
    }
    
    /**
     * Build instructions that include robot motion guidance
     */
    private static String buildRobotAwareInstructions(String baseInstructions) {
        StringBuilder sb = new StringBuilder();
        sb.append(baseInstructions);
        sb.append("\n\n");
        sb.append("## Robot Interaction Guidelines\n");
        sb.append("You are embodied in a Sanbot robot. Use gestures and emotions to make interactions natural:\n\n");
        sb.append("- Use robot_gesture frequently: 'greet' when meeting, 'nod' for agreement, 'thinking' when processing, ");
        sb.append("'excited' for enthusiasm, 'wave' for goodbye\n");
        sb.append("- Use robot_emotion to show feelings: 'happy', 'thinking', 'curious', 'excited'\n");
        sb.append("- Use robot_look to show attention: 'left', 'right', 'up', 'center'\n");
        sb.append("- Call gestures naturally as you speak, not after\n");
        sb.append("- Match gestures to conversation tone\n");
        sb.append("\n## CRM Integration\n");
        sb.append("- When customer provides contact info (name, phone, email), use save_customer_lead to save to CRM\n");
        sb.append("- When customer asks about packages/tours, use get_packages to fetch available options\n");
        sb.append("- Collect travel details progressively: destination, dates, travelers, hotel preference, budget\n");
        return sb.toString();
    }
    
    /**
     * Get all tool definitions including CRM and robot tools
     */
    private static JsonArray getAllTools() {
        JsonArray tools = new JsonArray();
        
        // Robot gesture tools
        tools.add(createTool("robot_gesture", 
            "Execute a robot gesture for natural body language",
            createGestureParams()));
        
        tools.add(createTool("robot_emotion",
            "Show emotion on robot's face display",
            createEmotionParams()));
        
        tools.add(createTool("robot_look",
            "Move robot's head to look in a direction",
            createLookParams()));
        
        // CRM tools with updated schema
        tools.add(createTool("save_customer_lead",
            "Save customer contact information and travel requirements to CRM. Call this when customer provides their name, phone, or email.",
            createLeadParams()));
        
        tools.add(createTool("get_packages",
            "Search and get travel packages from the catalog based on destination, budget, or duration",
            createPackageFilterParams()));
        
        tools.add(createTool("get_package_details",
            "Get detailed information about a specific package by its ID",
            createPackageDetailParams()));
        
        tools.add(createTool("disconnect_call",
            "End the conversation when customer is done or wants to leave",
            createEmptyParams()));
        
        return tools;
    }
    
    private static JsonObject createTool(String name, String description, JsonObject parameters) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("parameters", parameters);
        return tool;
    }
    
    private static JsonObject createGestureParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject gesture = new JsonObject();
        gesture.addProperty("type", "string");
        gesture.addProperty("description", "Gesture to perform");
        JsonArray gestureEnum = new JsonArray();
        gestureEnum.add("greet");
        gestureEnum.add("nod");
        gestureEnum.add("shake");
        gestureEnum.add("wave");
        gestureEnum.add("thinking");
        gestureEnum.add("excited");
        gestureEnum.add("listening");
        gestureEnum.add("goodbye");
        gestureEnum.add("acknowledge");
        gesture.add("enum", gestureEnum);
        props.add("gesture", gesture);
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("gesture");
        params.add("required", required);
        return params;
    }
    
    private static JsonObject createEmotionParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject emotion = new JsonObject();
        emotion.addProperty("type", "string");
        emotion.addProperty("description", "Emotion to display");
        JsonArray emotionEnum = new JsonArray();
        emotionEnum.add("happy");
        emotionEnum.add("excited");
        emotionEnum.add("thinking");
        emotionEnum.add("curious");
        emotionEnum.add("normal");
        emotion.add("enum", emotionEnum);
        props.add("emotion", emotion);
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("emotion");
        params.add("required", required);
        return params;
    }
    
    private static JsonObject createLookParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject direction = new JsonObject();
        direction.addProperty("type", "string");
        direction.addProperty("description", "Direction to look");
        JsonArray dirEnum = new JsonArray();
        dirEnum.add("left");
        dirEnum.add("right");
        dirEnum.add("up");
        dirEnum.add("down");
        dirEnum.add("center");
        direction.add("enum", dirEnum);
        props.add("direction", direction);
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("direction");
        params.add("required", required);
        return params;
    }
    
    /**
     * Create lead parameters matching CRM API schema
     * POST /api/leads
     */
    private static JsonObject createLeadParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        
        // Contact Information
        addStringProp(props, "name", "Customer's full name (required)");
        addStringProp(props, "email", "Customer's email address");
        addStringProp(props, "mobile", "Customer's mobile/phone number with country code");
        
        // Location
        addStringProp(props, "destination", "Desired travel destination");
        addStringProp(props, "nearestAirport", "Nearest airport code (e.g., DEL, BOM, BLR)");
        addStringProp(props, "arrivalCity", "City of arrival");
        addStringProp(props, "departureCity", "City of departure");
        
        // Dates
        addStringProp(props, "travelDate", "Preferred travel date (YYYY-MM-DD format)");
        addStringProp(props, "journeyStartDate", "Journey start date (YYYY-MM-DD format)");
        addStringProp(props, "journeyEndDate", "Journey end date (YYYY-MM-DD format)");
        
        // Duration
        addIntProp(props, "durationNights", "Number of nights");
        addIntProp(props, "durationDays", "Number of days");
        
        // Travelers
        addIntProp(props, "adults", "Number of adult travelers");
        addIntProp(props, "children", "Number of children");
        addIntProp(props, "infants", "Number of infants");
        
        // Accommodation
        JsonObject hotelType = new JsonObject();
        hotelType.addProperty("type", "string");
        hotelType.addProperty("description", "Preferred hotel category");
        JsonArray hotelEnum = new JsonArray();
        hotelEnum.add("2 Star");
        hotelEnum.add("3 Star");
        hotelEnum.add("4 Star");
        hotelEnum.add("5 Star");
        hotelEnum.add("Resort");
        hotelEnum.add("Villa");
        hotelEnum.add("Homestay");
        hotelType.add("enum", hotelEnum);
        props.add("hotelType", hotelType);
        
        addIntProp(props, "totalRooms", "Number of rooms required");
        addIntProp(props, "extraMattress", "Number of extra mattresses needed");
        
        JsonObject mealPlan = new JsonObject();
        mealPlan.addProperty("type", "string");
        mealPlan.addProperty("description", "Meal plan preference");
        JsonArray mealEnum = new JsonArray();
        mealEnum.add("EP");   // European Plan - Room only
        mealEnum.add("CP");   // Continental Plan - Breakfast
        mealEnum.add("MAP");  // Modified American Plan - Breakfast + Dinner
        mealEnum.add("AP");   // American Plan - All meals
        mealPlan.add("enum", mealEnum);
        props.add("mealPlan", mealPlan);
        
        // Extras
        addBooleanProp(props, "videographyPhotography", "Whether customer wants videography/photography");
        addStringProp(props, "specialRequirement", "Special requirements or preferences");
        
        // Journey
        JsonObject journeyType = new JsonObject();
        journeyType.addProperty("type", "string");
        journeyType.addProperty("description", "Type of journey/trip");
        JsonArray journeyEnum = new JsonArray();
        journeyEnum.add("honeymoon");
        journeyEnum.add("family");
        journeyEnum.add("corporate");
        journeyEnum.add("adventure");
        journeyEnum.add("pilgrimage");
        journeyEnum.add("leisure");
        journeyEnum.add("anniversary");
        journeyType.add("enum", journeyEnum);
        props.add("journeyType", journeyType);
        
        // Budget
        addNumberProp(props, "budget", "Customer's budget in INR");
        
        params.add("properties", props);
        
        // Required fields
        JsonArray required = new JsonArray();
        required.add("name");
        params.add("required", required);
        
        return params;
    }
    
    private static JsonObject createPackageFilterParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        
        addStringProp(props, "destination", "Destination to filter packages");
        addNumberProp(props, "budget_min", "Minimum budget in INR");
        addNumberProp(props, "budget_max", "Maximum budget in INR");
        addIntProp(props, "nights", "Preferred number of nights");
        addIntProp(props, "min_nights", "Minimum nights");
        addIntProp(props, "max_nights", "Maximum nights");
        
        JsonObject packageType = new JsonObject();
        packageType.addProperty("type", "string");
        packageType.addProperty("description", "Package type: with or without flight");
        JsonArray typeEnum = new JsonArray();
        typeEnum.add("with_flight");
        typeEnum.add("without_flight");
        packageType.add("enum", typeEnum);
        props.add("package_type", packageType);
        
        addIntProp(props, "limit", "Maximum number of packages to return (default 5)");
        
        params.add("properties", props);
        params.add("required", new JsonArray());
        
        return params;
    }
    
    private static JsonObject createPackageDetailParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        
        addIntProp(props, "package_id", "The ID of the package to get details for");
        
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("package_id");
        params.add("required", required);
        
        return params;
    }
    
    private static JsonObject createEmptyParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", new JsonObject());
        params.add("required", new JsonArray());
        return params;
    }
    
    private static void addStringProp(JsonObject props, String name, String desc) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", desc);
        props.add(name, prop);
    }
    
    private static void addIntProp(JsonObject props, String name, String desc) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "integer");
        prop.addProperty("description", desc);
        props.add(name, prop);
    }
    
    private static void addNumberProp(JsonObject props, String name, String desc) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "number");
        prop.addProperty("description", desc);
        props.add(name, prop);
    }
    
    private static void addBooleanProp(JsonObject props, String name, String desc) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "boolean");
        prop.addProperty("description", desc);
        props.add(name, prop);
    }
    
    // ============================================
    // AUDIO EVENTS
    // ============================================
    
    /**
     * Create input_audio_buffer.append event
     */
    public static String inputAudioBufferAppend(String base64Audio) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "input_audio_buffer.append");
        event.addProperty("event_id", generateEventId());
        event.addProperty("audio", base64Audio);
        return event.toString();
    }
    
    /**
     * Create input_audio_buffer.commit event
     */
    public static String inputAudioBufferCommit() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "input_audio_buffer.commit");
        event.addProperty("event_id", generateEventId());
        return event.toString();
    }
    
    /**
     * Create input_audio_buffer.clear event
     */
    public static String inputAudioBufferClear() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "input_audio_buffer.clear");
        event.addProperty("event_id", generateEventId());
        return event.toString();
    }
    
    /**
     * Create output_audio_buffer.clear event
     */
    public static String outputAudioBufferClear() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "output_audio_buffer.clear");
        event.addProperty("event_id", generateEventId());
        return event.toString();
    }
    
    // ============================================
    // CONVERSATION EVENTS
    // ============================================
    
    /**
     * Create conversation.item.create for text message
     */
    public static String conversationItemCreateText(String role, String text) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "conversation.item.create");
        event.addProperty("event_id", generateEventId());
        
        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", role);
        
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "input_text");
        textContent.addProperty("text", text);
        content.add(textContent);
        
        item.add("content", content);
        event.add("item", item);
        
        return event.toString();
    }
    
    /**
     * Create conversation.item.create for function output (GA format)
     */
    public static String conversationItemCreateFunctionOutput(String callId, String output) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "conversation.item.create");
        event.addProperty("event_id", generateEventId());
        
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", callId);
        item.addProperty("output", output);
        
        event.add("item", item);
        
        return event.toString();
    }
    
    /**
     * Create conversation.item.delete event
     */
    public static String conversationItemDelete(String itemId) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "conversation.item.delete");
        event.addProperty("event_id", generateEventId());
        event.addProperty("item_id", itemId);
        return event.toString();
    }
    
    /**
     * Create conversation.item.truncate event
     */
    public static String conversationItemTruncate(String itemId, int contentIndex, int audioEndMs) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "conversation.item.truncate");
        event.addProperty("event_id", generateEventId());
        event.addProperty("item_id", itemId);
        event.addProperty("content_index", contentIndex);
        event.addProperty("audio_end_ms", audioEndMs);
        return event.toString();
    }
    
    // ============================================
    // RESPONSE EVENTS
    // ============================================
    
    /**
     * Create response.create event
     */
    public static String responseCreate() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.create");
        event.addProperty("event_id", generateEventId());
        return event.toString();
    }
    
    /**
     * Create response.create event with modalities
     */
    public static String responseCreate(boolean includeAudio, boolean includeText) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.create");
        event.addProperty("event_id", generateEventId());
        
        JsonObject response = new JsonObject();
        JsonArray modalities = new JsonArray();
        if (includeAudio) modalities.add("audio");
        if (includeText) modalities.add("text");
        response.add("modalities", modalities);
        
        event.add("response", response);
        
        return event.toString();
    }
    
    /**
     * Create response.cancel event
     */
    public static String responseCancel() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.cancel");
        event.addProperty("event_id", generateEventId());
        return event.toString();
    }
}