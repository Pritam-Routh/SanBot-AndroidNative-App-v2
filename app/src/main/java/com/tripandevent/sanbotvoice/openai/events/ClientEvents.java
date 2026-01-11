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
        JsonArray tools = getRobotMotionTools();
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
        return sb.toString();
    }
    
    /**
     * Get robot motion tool definitions
     */
    private static JsonArray getRobotMotionTools() {
        JsonArray tools = new JsonArray();
        
        // 1. robot_gesture
        tools.add(createTool("robot_gesture", 
            "Execute a robot gesture for natural body language",
            createGestureParams()));
        
        // 2. robot_emotion
        tools.add(createTool("robot_emotion",
            "Show emotion on robot's face display",
            createEmotionParams()));
        
        // 3. robot_look
        tools.add(createTool("robot_look",
            "Move robot's head to look in a direction",
            createLookParams()));
        
        // 4. save_customer_lead
        tools.add(createTool("save_customer_lead",
            "Save customer contact information and travel requirements",
            createLeadParams()));
        
        // 5. get_packages
        tools.add(createTool("get_packages",
            "Search and get travel packages based on filters",
            createPackageFilterParams()));
        
        // 6. disconnect_call
        tools.add(createTool("disconnect_call",
            "End the conversation when customer is done",
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
    
    private static JsonObject createLeadParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        
        addStringProp(props, "name", "Customer's full name");
        addStringProp(props, "email", "Customer's email");
        addStringProp(props, "mobile", "Customer's phone number");
        addStringProp(props, "destination", "Travel destination");
        addStringProp(props, "travel_date", "Travel date (YYYY-MM-DD)");
        addIntProp(props, "adults", "Number of adults");
        addIntProp(props, "children", "Number of children");
        addIntProp(props, "nights", "Number of nights");
        addStringProp(props, "hotel_type", "Hotel category: budget, standard, luxury");
        addNumberProp(props, "budget", "Budget in INR");
        addStringProp(props, "special_requirements", "Special requirements");
        
        params.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("name");
        params.add("required", required);
        return params;
    }
    
    private static JsonObject createPackageFilterParams() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        JsonObject props = new JsonObject();
        
        addStringProp(props, "destination", "Destination to filter");
        addNumberProp(props, "budget_min", "Minimum budget");
        addNumberProp(props, "budget_max", "Maximum budget");
        addIntProp(props, "nights", "Preferred number of nights");
        addIntProp(props, "limit", "Max packages to return");
        
        params.add("properties", props);
        params.add("required", new JsonArray());
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