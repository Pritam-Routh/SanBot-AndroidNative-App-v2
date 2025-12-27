package com.tripandevent.sanbotvoice.openai.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tripandevent.sanbotvoice.utils.Constants;

import java.util.List;
import java.util.UUID;

/**
 * Builder for OpenAI Realtime API client events.
 * Creates properly formatted JSON events to send via WebRTC DataChannel.
 */
public class ClientEvents {
    
    private static final Gson gson = new GsonBuilder().create();
    
    private ClientEvents() {}
    
    /**
     * Generate unique event ID
     */
    private static String generateEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    // ============================================
    // SESSION EVENTS
    // ============================================
    
    /**
     * Create session.update event with full configuration
     */
    public static String sessionUpdate(SessionConfig config) {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.SESSION_UPDATE);
        
        JsonObject session = new JsonObject();
        session.addProperty("type", "realtime");
        session.addProperty("model", Constants.OPENAI_REALTIME_MODEL);
        
        // Modalities
        JsonArray modalities = new JsonArray();
        modalities.add("audio");
        modalities.add("text");
        session.add("output_modalities", modalities);
        
        // Instructions
        if (config.instructions != null) {
            session.addProperty("instructions", config.instructions);
        }
        
        // Audio configuration
        JsonObject audio = new JsonObject();
        
        // Input audio config
        JsonObject inputAudio = new JsonObject();
        JsonObject inputFormat = new JsonObject();
        inputFormat.addProperty("type", Constants.AUDIO_FORMAT);
        inputFormat.addProperty("rate", Constants.AUDIO_SAMPLE_RATE);
        inputAudio.add("format", inputFormat);
        
        // Turn detection (VAD)
        if (config.vadEnabled) {
            JsonObject turnDetection = new JsonObject();
            turnDetection.addProperty("type", "semantic_vad");
            inputAudio.add("turn_detection", turnDetection);
        }
        audio.add("input", inputAudio);
        
        // Output audio config
        JsonObject outputAudio = new JsonObject();
        JsonObject outputFormat = new JsonObject();
        outputFormat.addProperty("type", Constants.AUDIO_FORMAT);
        outputAudio.add("format", outputFormat);
        outputAudio.addProperty("voice", config.voice != null ? config.voice : Constants.OPENAI_VOICE);
        audio.add("output", outputAudio);
        
        session.add("audio", audio);
        
        // Tools (functions)
        if (config.tools != null && !config.tools.isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolDefinition tool : config.tools) {
                tools.add(tool.toJson());
            }
            session.add("tools", tools);
            session.addProperty("tool_choice", "auto");
        }
        
        event.add("session", session);
        return gson.toJson(event);
    }
    
    // ============================================
    // INPUT AUDIO BUFFER EVENTS
    // ============================================
    
    /**
     * Create input_audio_buffer.clear event
     * Used to clear audio buffer, especially for push-to-talk
     */
    public static String inputAudioBufferClear() {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.INPUT_AUDIO_BUFFER_CLEAR);
        return gson.toJson(event);
    }
    
    /**
     * Create input_audio_buffer.commit event
     * Commits the audio in the buffer and starts transcription
     */
    public static String inputAudioBufferCommit() {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.INPUT_AUDIO_BUFFER_COMMIT);
        return gson.toJson(event);
    }
    
    // ============================================
    // CONVERSATION EVENTS
    // ============================================
    
    /**
     * Create conversation.item.create event for text message
     */
    public static String conversationItemCreateText(String role, String text) {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.CONVERSATION_ITEM_CREATE);
        
        JsonObject item = new JsonObject();
        item.addProperty("type", EventTypes.ItemType.MESSAGE);
        item.addProperty("role", role);
        
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", EventTypes.ContentType.INPUT_TEXT);
        textContent.addProperty("text", text);
        content.add(textContent);
        
        item.add("content", content);
        event.add("item", item);
        
        return gson.toJson(event);
    }
    
    /**
     * Create conversation.item.create event for function call output
     */
    public static String conversationItemCreateFunctionOutput(String callId, String output) {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.CONVERSATION_ITEM_CREATE);
        
        JsonObject item = new JsonObject();
        item.addProperty("type", EventTypes.ItemType.FUNCTION_CALL_OUTPUT);
        item.addProperty("call_id", callId);
        item.addProperty("output", output);
        
        event.add("item", item);
        return gson.toJson(event);
    }
    
    /**
     * Create conversation.item.truncate event
     * Used to remove unplayed audio after interruption
     */
    public static String conversationItemTruncate(String itemId, int contentIndex, int audioEndMs) {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.CONVERSATION_ITEM_TRUNCATE);
        event.addProperty("item_id", itemId);
        event.addProperty("content_index", contentIndex);
        event.addProperty("audio_end_ms", audioEndMs);
        return gson.toJson(event);
    }
    
    // ============================================
    // RESPONSE EVENTS
    // ============================================
    
    /**
     * Create response.create event to trigger model response
     */
    public static String responseCreate() {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.RESPONSE_CREATE);
        return gson.toJson(event);
    }
    
    /**
     * Create response.create event with specific modalities
     */
    public static String responseCreate(boolean includeAudio, boolean includeText) {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.RESPONSE_CREATE);
        
        JsonObject response = new JsonObject();
        JsonArray modalities = new JsonArray();
        if (includeAudio) modalities.add("audio");
        if (includeText) modalities.add("text");
        response.add("output_modalities", modalities);
        
        event.add("response", response);
        return gson.toJson(event);
    }
    
    /**
     * Create response.cancel event to stop current response
     */
    public static String responseCancel() {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.RESPONSE_CANCEL);
        return gson.toJson(event);
    }
    
    // ============================================
    // OUTPUT AUDIO BUFFER EVENTS (WebRTC)
    // ============================================
    
    /**
     * Create output_audio_buffer.clear event
     * Clears the server's output audio buffer (for interruption handling in WebRTC)
     */
    public static String outputAudioBufferClear() {
        JsonObject event = new JsonObject();
        event.addProperty("event_id", generateEventId());
        event.addProperty("type", EventTypes.Client.OUTPUT_AUDIO_BUFFER_CLEAR);
        return gson.toJson(event);
    }
    
    // ============================================
    // HELPER CLASSES
    // ============================================
    
    /**
     * Session configuration holder
     */
    public static class SessionConfig {
        public String instructions;
        public String voice;
        public boolean vadEnabled = true;
        public List<ToolDefinition> tools;
        
        public SessionConfig() {}
        
        public SessionConfig setInstructions(String instructions) {
            this.instructions = instructions;
            return this;
        }
        
        public SessionConfig setVoice(String voice) {
            this.voice = voice;
            return this;
        }
        
        public SessionConfig setVadEnabled(boolean enabled) {
            this.vadEnabled = enabled;
            return this;
        }
        
        public SessionConfig setTools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }
    }
    
    /**
     * Tool/function definition for session configuration
     */
    public static class ToolDefinition {
        public String name;
        public String description;
        public JsonObject parameters;
        
        public ToolDefinition(String name, String description, JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
        
        public JsonObject toJson() {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", name);
            tool.addProperty("description", description);
            tool.add("parameters", parameters);
            return tool;
        }
    }
}
