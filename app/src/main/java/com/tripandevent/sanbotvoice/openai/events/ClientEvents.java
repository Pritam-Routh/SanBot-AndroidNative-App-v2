package com.tripandevent.sanbotvoice.openai.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Creates client events for OpenAI Realtime API (GA Version).
 * 
 * Updated for the GA release with new event structure:
 * - session.type is required ("realtime" or "transcription")
 * - audio configuration moved to audio.input and audio.output
 * - tools remain in session.tools array
 * 
 * Event types:
 * - session.update: Update session configuration
 * - conversation.item.create: Add conversation item
 * - response.create: Trigger response generation
 * - response.cancel: Cancel ongoing response
 */
public class ClientEvents {
    
    private static final Gson gson = new GsonBuilder().create();
    
    /**
     * Create session.update event with tools for robot motion (GA format)
     * 
     * Based on OpenAI GA documentation:
     * https://platform.openai.com/docs/api-reference/realtime-client-events/session/update
     */
    public static String sessionUpdateWithTools(String instructions, JsonArray tools) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "session.update");
        
        JsonObject session = new JsonObject();
        
        // Required: session type for GA
        session.addProperty("type", "realtime");
        
        // Model (optional if already set during connection)
        // session.addProperty("model", "gpt-realtime");
        
        // Output modalities
        JsonArray modalities = new JsonArray();
        modalities.add("audio");
        modalities.add("text");
        session.add("output_modalities", modalities);
        
        // Audio configuration (GA structure)
        JsonObject audio = new JsonObject();
        
        // Input audio config
        JsonObject inputAudio = new JsonObject();
        JsonObject inputFormat = new JsonObject();
        inputFormat.addProperty("type", "audio/pcm");
        inputFormat.addProperty("rate", 24000);
        inputAudio.add("format", inputFormat);
        
        // Turn detection (VAD)
        JsonObject turnDetection = new JsonObject();
        turnDetection.addProperty("type", "server_vad");
        turnDetection.addProperty("threshold", 0.5);
        turnDetection.addProperty("prefix_padding_ms", 300);
        turnDetection.addProperty("silence_duration_ms", 500);
        inputAudio.add("turn_detection", turnDetection);
        
        audio.add("input", inputAudio);
        
        // Output audio config
        JsonObject outputAudio = new JsonObject();
        JsonObject outputFormat = new JsonObject();
        outputFormat.addProperty("type", "audio/pcm");
        outputFormat.addProperty("rate", 24000);
        outputAudio.add("format", outputFormat);
        outputAudio.addProperty("voice", "alloy");
        
        audio.add("output", outputAudio);
        
        session.add("audio", audio);
        
        // Instructions
        if (instructions != null && !instructions.isEmpty()) {
            session.addProperty("instructions", instructions);
        }
        
        // Tools (function definitions)
        if (tools != null && tools.size() > 0) {
            session.add("tools", tools);
            session.addProperty("tool_choice", "auto");
        }
        
        event.add("session", session);
        
        return gson.toJson(event);
    }
    
    /**
     * Create session.update with robot motion tools and instructions
     */
    public static String sessionUpdateWithRobotMotion(String baseInstructions) {
        String fullInstructions = buildRobotAwareInstructions(baseInstructions);
        JsonArray tools = getRobotMotionTools();
        return sessionUpdateWithTools(fullInstructions, tools);
    }
    
    /**
     * Build instructions that guide the AI to use robot motions naturally
     */
    private static String buildRobotAwareInstructions(String baseInstructions) {
        String robotGuidance = 
            "\n\n## Robot Body Language Instructions\n" +
            "You are embodied in a Sanbot robot with physical movement capabilities. " +
            "Use the robot gesture and emotion functions FREQUENTLY to make interactions natural and engaging.\n\n" +
            
            "**Gesture Guidelines:**\n" +
            "- Greeting someone: Call robot_gesture with gesture='greet' or gesture='wave'\n" +
            "- Agreeing/confirming: Call robot_gesture with gesture='nod' or gesture='agree'\n" +
            "- Disagreeing/saying no: Call robot_gesture with gesture='shake'\n" +
            "- Thinking/processing: Call robot_gesture with gesture='thinking'\n" +
            "- Excited about something: Call robot_gesture with gesture='excited'\n" +
            "- Listening attentively: Call robot_gesture with gesture='listening'\n" +
            "- Saying goodbye: Call robot_gesture with gesture='goodbye'\n" +
            "- Acknowledging: Call robot_gesture with gesture='nod'\n\n" +
            
            "**Emotion Guidelines:**\n" +
            "- Happy/positive topics: Call robot_emotion with emotion='happy'\n" +
            "- Thinking: Call robot_emotion with emotion='thinking'\n" +
            "- Curious: Call robot_emotion with emotion='curious'\n" +
            "- Ending conversation: Call robot_emotion with emotion='goodbye'\n\n" +
            
            "**IMPORTANT:** Call gesture/emotion functions along with your verbal response to create synchronized, natural body language. " +
            "Use gestures frequently to appear lifelike.\n";
        
        if (baseInstructions != null && !baseInstructions.isEmpty()) {
            return baseInstructions + robotGuidance;
        }
        return "You are a helpful, friendly assistant." + robotGuidance;
    }
    
    /**
     * Get robot motion tool definitions (GA format)
     * 
     * Tool format per OpenAI docs:
     * {
     *   "type": "function",
     *   "name": "function_name",
     *   "description": "...",
     *   "parameters": { JSON Schema }
     * }
     */
    private static JsonArray getRobotMotionTools() {
        JsonArray tools = new JsonArray();
        
        // 1. robot_gesture function
        JsonObject gestureFunc = new JsonObject();
        gestureFunc.addProperty("type", "function");
        gestureFunc.addProperty("name", "robot_gesture");
        gestureFunc.addProperty("description", 
            "Make the robot perform an expressive gesture. Use during conversation to make interactions engaging.");
        
        JsonObject gestureParams = new JsonObject();
        gestureParams.addProperty("type", "object");
        
        JsonObject gestureProps = new JsonObject();
        
        JsonObject gestureProp = new JsonObject();
        gestureProp.addProperty("type", "string");
        gestureProp.addProperty("description", 
            "The gesture: nod=agreement, shake=disagreement, wave=greeting, greet=hello, " +
            "thinking=processing, agree=yes, disagree=no, excited=enthusiasm, " +
            "listening=attentive, goodbye=farewell, acknowledge=understand");
        JsonArray gestureEnum = new JsonArray();
        gestureEnum.add("nod");
        gestureEnum.add("shake");
        gestureEnum.add("wave");
        gestureEnum.add("greet");
        gestureEnum.add("thinking");
        gestureEnum.add("agree");
        gestureEnum.add("disagree");
        gestureEnum.add("excited");
        gestureEnum.add("listening");
        gestureEnum.add("goodbye");
        gestureEnum.add("acknowledge");
        gestureProp.add("enum", gestureEnum);
        gestureProps.add("gesture", gestureProp);
        
        gestureParams.add("properties", gestureProps);
        JsonArray gestureRequired = new JsonArray();
        gestureRequired.add("gesture");
        gestureParams.add("required", gestureRequired);
        
        gestureFunc.add("parameters", gestureParams);
        tools.add(gestureFunc);
        
        // 2. robot_emotion function
        JsonObject emotionFunc = new JsonObject();
        emotionFunc.addProperty("type", "function");
        emotionFunc.addProperty("name", "robot_emotion");
        emotionFunc.addProperty("description", 
            "Display an emotion on the robot's face screen. Use to match conversation tone.");
        
        JsonObject emotionParams = new JsonObject();
        emotionParams.addProperty("type", "object");
        
        JsonObject emotionProps = new JsonObject();
        
        JsonObject emotionProp = new JsonObject();
        emotionProp.addProperty("type", "string");
        emotionProp.addProperty("description", "The emotion to display");
        JsonArray emotionEnum = new JsonArray();
        emotionEnum.add("happy");
        emotionEnum.add("excited");
        emotionEnum.add("thinking");
        emotionEnum.add("curious");
        emotionEnum.add("shy");
        emotionEnum.add("laugh");
        emotionEnum.add("goodbye");
        emotionEnum.add("normal");
        emotionProp.add("enum", emotionEnum);
        emotionProps.add("emotion", emotionProp);
        
        emotionParams.add("properties", emotionProps);
        JsonArray emotionRequired = new JsonArray();
        emotionRequired.add("emotion");
        emotionParams.add("required", emotionRequired);
        
        emotionFunc.add("parameters", emotionParams);
        tools.add(emotionFunc);
        
        // 3. robot_look function
        JsonObject lookFunc = new JsonObject();
        lookFunc.addProperty("type", "function");
        lookFunc.addProperty("name", "robot_look");
        lookFunc.addProperty("description", 
            "Make the robot look in a specific direction. Use to direct attention.");
        
        JsonObject lookParams = new JsonObject();
        lookParams.addProperty("type", "object");
        
        JsonObject lookProps = new JsonObject();
        
        JsonObject directionProp = new JsonObject();
        directionProp.addProperty("type", "string");
        directionProp.addProperty("description", "Direction to look");
        JsonArray directionEnum = new JsonArray();
        directionEnum.add("left");
        directionEnum.add("right");
        directionEnum.add("up");
        directionEnum.add("down");
        directionEnum.add("center");
        directionProp.add("enum", directionEnum);
        lookProps.add("direction", directionProp);
        
        lookParams.add("properties", lookProps);
        JsonArray lookRequired = new JsonArray();
        lookRequired.add("direction");
        lookParams.add("required", lookRequired);
        
        lookFunc.add("parameters", lookParams);
        tools.add(lookFunc);
        
        // 4. save_customer_lead function (CRM)
        JsonObject leadFunc = new JsonObject();
        leadFunc.addProperty("type", "function");
        leadFunc.addProperty("name", "save_customer_lead");
        leadFunc.addProperty("description", 
            "Save customer contact information and interest to CRM.");
        
        JsonObject leadParams = new JsonObject();
        leadParams.addProperty("type", "object");
        
        JsonObject leadProps = new JsonObject();
        
        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("type", "string");
        nameProp.addProperty("description", "Customer full name");
        leadProps.add("name", nameProp);
        
        JsonObject emailProp = new JsonObject();
        emailProp.addProperty("type", "string");
        emailProp.addProperty("description", "Customer email address");
        leadProps.add("email", emailProp);
        
        JsonObject phoneProp = new JsonObject();
        phoneProp.addProperty("type", "string");
        phoneProp.addProperty("description", "Customer phone number");
        leadProps.add("phone", phoneProp);
        
        JsonObject interestProp = new JsonObject();
        interestProp.addProperty("type", "string");
        interestProp.addProperty("description", "What the customer is interested in");
        leadProps.add("interest", interestProp);
        
        leadParams.add("properties", leadProps);
        leadParams.add("required", new JsonArray()); // No required fields
        
        leadFunc.add("parameters", leadParams);
        tools.add(leadFunc);
        
        // 5. create_quote function
        JsonObject quoteFunc = new JsonObject();
        quoteFunc.addProperty("type", "function");
        quoteFunc.addProperty("name", "create_quote");
        quoteFunc.addProperty("description", 
            "Create a travel quote for the customer.");
        
        JsonObject quoteParams = new JsonObject();
        quoteParams.addProperty("type", "object");
        
        JsonObject quoteProps = new JsonObject();
        
        JsonObject destProp = new JsonObject();
        destProp.addProperty("type", "string");
        destProp.addProperty("description", "Travel destination");
        quoteProps.add("destination", destProp);
        
        JsonObject datesProp = new JsonObject();
        datesProp.addProperty("type", "string");
        datesProp.addProperty("description", "Desired travel dates");
        quoteProps.add("travel_dates", datesProp);
        
        JsonObject travelersProp = new JsonObject();
        travelersProp.addProperty("type", "integer");
        travelersProp.addProperty("description", "Number of travelers");
        quoteProps.add("travelers", travelersProp);
        
        quoteParams.add("properties", quoteProps);
        JsonArray quoteRequired = new JsonArray();
        quoteRequired.add("destination");
        quoteParams.add("required", quoteRequired);
        
        quoteFunc.add("parameters", quoteParams);
        tools.add(quoteFunc);
        
        // 6. disconnect_call function
        JsonObject disconnectFunc = new JsonObject();
        disconnectFunc.addProperty("type", "function");
        disconnectFunc.addProperty("name", "disconnect_call");
        disconnectFunc.addProperty("description", 
            "End the conversation gracefully.");
        
        JsonObject disconnectParams = new JsonObject();
        disconnectParams.addProperty("type", "object");
        
        JsonObject disconnectProps = new JsonObject();
        JsonObject reasonProp = new JsonObject();
        reasonProp.addProperty("type", "string");
        reasonProp.addProperty("description", "Reason for ending call");
        disconnectProps.add("reason", reasonProp);
        
        disconnectParams.add("properties", disconnectProps);
        disconnectParams.add("required", new JsonArray());
        
        disconnectFunc.add("parameters", disconnectParams);
        tools.add(disconnectFunc);
        
        return tools;
    }
    
    /**
     * Create response.create event
     */
    public static String responseCreate() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.create");
        return gson.toJson(event);
    }
    
    /**
     * Create response.cancel event
     */
    public static String responseCancel() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.cancel");
        return gson.toJson(event);
    }

    /**
 * Get CRM tool definitions for OpenAI function calling
 */
    private static JsonArray getCrmTools() {
        JsonArray tools = new JsonArray();
        
        // 1. save_customer_lead
        JsonObject saveLeadFunc = new JsonObject();
        saveLeadFunc.addProperty("type", "function");
        saveLeadFunc.addProperty("name", "save_customer_lead");
        saveLeadFunc.addProperty("description", 
            "Save customer contact information and travel requirements to CRM. " +
            "Call this when you have collected customer details like name, contact, destination, travel dates, etc.");
        
        JsonObject saveLeadParams = new JsonObject();
        saveLeadParams.addProperty("type", "object");
        
        JsonObject saveLeadProps = new JsonObject();
        
        // Name
        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("type", "string");
        nameProp.addProperty("description", "Customer's full name");
        saveLeadProps.add("name", nameProp);
        
        // Email
        JsonObject emailProp = new JsonObject();
        emailProp.addProperty("type", "string");
        emailProp.addProperty("description", "Customer's email address");
        saveLeadProps.add("email", emailProp);
        
        // Mobile
        JsonObject mobileProp = new JsonObject();
        mobileProp.addProperty("type", "string");
        mobileProp.addProperty("description", "Customer's mobile/phone number");
        saveLeadProps.add("mobile", mobileProp);
        
        // Destination
        JsonObject destProp = new JsonObject();
        destProp.addProperty("type", "string");
        destProp.addProperty("description", "Desired travel destination");
        saveLeadProps.add("destination", destProp);
        
        // Travel date
        JsonObject dateProp = new JsonObject();
        dateProp.addProperty("type", "string");
        dateProp.addProperty("description", "Preferred travel date (YYYY-MM-DD format)");
        saveLeadProps.add("travel_date", dateProp);
        
        // Adults
        JsonObject adultsProp = new JsonObject();
        adultsProp.addProperty("type", "integer");
        adultsProp.addProperty("description", "Number of adult travelers");
        saveLeadProps.add("adults", adultsProp);
        
        // Children
        JsonObject childrenProp = new JsonObject();
        childrenProp.addProperty("type", "integer");
        childrenProp.addProperty("description", "Number of children");
        saveLeadProps.add("children", childrenProp);
        
        // Nights
        JsonObject nightsProp = new JsonObject();
        nightsProp.addProperty("type", "integer");
        nightsProp.addProperty("description", "Number of nights for the trip");
        saveLeadProps.add("nights", nightsProp);
        
        // Hotel type
        JsonObject hotelProp = new JsonObject();
        hotelProp.addProperty("type", "string");
        hotelProp.addProperty("description", "Preferred hotel category: budget, standard, luxury, or premium");
        JsonArray hotelEnum = new JsonArray();
        hotelEnum.add("budget");
        hotelEnum.add("standard");
        hotelEnum.add("luxury");
        hotelEnum.add("premium");
        hotelProp.add("enum", hotelEnum);
        saveLeadProps.add("hotel_type", hotelProp);
        
        // Budget
        JsonObject budgetProp = new JsonObject();
        budgetProp.addProperty("type", "number");
        budgetProp.addProperty("description", "Customer's budget in INR");
        saveLeadProps.add("budget", budgetProp);
        
        // Special requirements
        JsonObject specialProp = new JsonObject();
        specialProp.addProperty("type", "string");
        specialProp.addProperty("description", "Any special requirements (honeymoon, anniversary, dietary, etc.)");
        saveLeadProps.add("special_requirements", specialProp);
        
        saveLeadParams.add("properties", saveLeadProps);
        JsonArray required = new JsonArray();
        required.add("name");
        saveLeadParams.add("required", required);
        
        saveLeadFunc.add("parameters", saveLeadParams);
        tools.add(saveLeadFunc);
        
        // 2. get_packages
        JsonObject getPackagesFunc = new JsonObject();
        getPackagesFunc.addProperty("type", "function");
        getPackagesFunc.addProperty("name", "get_packages");
        getPackagesFunc.addProperty("description", 
            "Search and get travel packages from the catalog. " +
            "Use when customer asks about packages, tours, or wants to see options for a destination.");
        
        JsonObject getPackagesParams = new JsonObject();
        getPackagesParams.addProperty("type", "object");
        
        JsonObject getPackagesProps = new JsonObject();
        
        JsonObject filterDestProp = new JsonObject();
        filterDestProp.addProperty("type", "string");
        filterDestProp.addProperty("description", "Destination to filter packages");
        getPackagesProps.add("destination", filterDestProp);
        
        JsonObject budgetMinProp = new JsonObject();
        budgetMinProp.addProperty("type", "number");
        budgetMinProp.addProperty("description", "Minimum budget in INR");
        getPackagesProps.add("budget_min", budgetMinProp);
        
        JsonObject budgetMaxProp = new JsonObject();
        budgetMaxProp.addProperty("type", "number");
        budgetMaxProp.addProperty("description", "Maximum budget in INR");
        getPackagesProps.add("budget_max", budgetMaxProp);
        
        JsonObject filterNightsProp = new JsonObject();
        filterNightsProp.addProperty("type", "integer");
        filterNightsProp.addProperty("description", "Preferred number of nights");
        getPackagesProps.add("nights", filterNightsProp);
        
        JsonObject packageTypeProp = new JsonObject();
        packageTypeProp.addProperty("type", "string");
        packageTypeProp.addProperty("description", "Package type: with_flight or without_flight");
        JsonArray typeEnum = new JsonArray();
        typeEnum.add("with_flight");
        typeEnum.add("without_flight");
        packageTypeProp.add("enum", typeEnum);
        getPackagesProps.add("package_type", packageTypeProp);
        
        JsonObject limitProp = new JsonObject();
        limitProp.addProperty("type", "integer");
        limitProp.addProperty("description", "Maximum number of packages to return (default 5)");
        getPackagesProps.add("limit", limitProp);
        
        getPackagesParams.add("properties", getPackagesProps);
        getPackagesParams.add("required", new JsonArray());
        
        getPackagesFunc.add("parameters", getPackagesParams);
        tools.add(getPackagesFunc);
        
        // 3. get_package_details
        JsonObject getDetailFunc = new JsonObject();
        getDetailFunc.addProperty("type", "function");
        getDetailFunc.addProperty("name", "get_package_details");
        getDetailFunc.addProperty("description", 
            "Get detailed information about a specific package including inclusions, exclusions, and itinerary.");
        
        JsonObject getDetailParams = new JsonObject();
        getDetailParams.addProperty("type", "object");
        
        JsonObject getDetailProps = new JsonObject();
        JsonObject pkgIdProp = new JsonObject();
        pkgIdProp.addProperty("type", "integer");
        pkgIdProp.addProperty("description", "The package ID to get details for");
        getDetailProps.add("package_id", pkgIdProp);
        
        getDetailParams.add("properties", getDetailProps);
        JsonArray detailRequired = new JsonArray();
        detailRequired.add("package_id");
        getDetailParams.add("required", detailRequired);
        
        getDetailFunc.add("parameters", getDetailParams);
        tools.add(getDetailFunc);
        
        // 4. search_packages
        JsonObject searchFunc = new JsonObject();
        searchFunc.addProperty("type", "function");
        searchFunc.addProperty("name", "search_packages");
        searchFunc.addProperty("description", 
            "Search packages by keyword. Use for general searches like 'honeymoon', 'beach', 'adventure', etc.");
        
        JsonObject searchParams = new JsonObject();
        searchParams.addProperty("type", "object");
        
        JsonObject searchProps = new JsonObject();
        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "string");
        queryProp.addProperty("description", "Search keyword or phrase");
        searchProps.add("query", queryProp);
        
        searchParams.add("properties", searchProps);
        JsonArray searchRequired = new JsonArray();
        searchRequired.add("query");
        searchParams.add("required", searchRequired);
        
        searchFunc.add("parameters", searchParams);
        tools.add(searchFunc);
        
        return tools;
    }
    
    /**
     * Create conversation.item.create event for function output (GA format)
     * 
     * Per GA docs, function output format:
     * {
     *   "type": "conversation.item.create",
     *   "item": {
     *     "type": "function_call_output",
     *     "call_id": "...",
     *     "output": "..."
     *   }
     * }
     */
    public static String conversationItemCreateFunctionOutput(String callId, String output) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "conversation.item.create");
        
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", callId);
        item.addProperty("output", output);
        
        event.add("item", item);
        
        return gson.toJson(event);
    }
    
    /**
     * Create conversation.item.create event for user text message
     */
    public static String conversationItemCreateUserMessage(String text) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "conversation.item.create");
        
        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", "user");
        
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "input_text");
        textContent.addProperty("text", text);
        content.add(textContent);
        
        item.add("content", content);
        event.add("item", item);
        
        return gson.toJson(event);
    }

    /**
 * Session configuration for updates
    */
    public static class SessionConfig {
        public String instructions;
        public String voice = "alloy";
        public JsonArray tools;
        public String toolChoice = "auto";
        
        public SessionConfig() {}
        
        public SessionConfig(String instructions) {
            this.instructions = instructions;
        }
    }

    /**
     * Tool definition for function calling
     */
    public static class ToolDefinition {
        public String type = "function";
        public String name;
        public String description;
        public JsonObject parameters;
        
        public ToolDefinition(String name, String description, JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }

    /**
     * Create output audio buffer clear event
     */
    public static String outputAudioBufferClear() {
        JsonObject event = new JsonObject();
        event.addProperty("type", "output_audio_buffer.clear");
        event.addProperty("event_id", generateEventId());
        return event.toString();
    }
}


