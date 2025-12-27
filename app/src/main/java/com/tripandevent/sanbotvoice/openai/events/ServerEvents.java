package com.tripandevent.sanbotvoice.openai.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for OpenAI Realtime API server events.
 * Converts JSON events received via WebRTC DataChannel to typed objects.
 */
public class ServerEvents {
    
    private static final Gson gson = new GsonBuilder().create();
    
    private ServerEvents() {}
    
    /**
     * Parse a server event from JSON string
     */
    public static ParsedEvent parse(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : null;
            String eventId = obj.has("event_id") ? obj.get("event_id").getAsString() : null;
            
            return new ParsedEvent(type, eventId, obj);
        } catch (Exception e) {
            Logger.e("ServerEvents", "Failed to parse event: %s", e.getMessage());
            return new ParsedEvent(null, null, null);
        }
    }
    
    /**
     * Parsed server event
     */
    public static class ParsedEvent {
        public final String type;
        public final String eventId;
        public final JsonObject raw;
        
        public ParsedEvent(String type, String eventId, JsonObject raw) {
            this.type = type;
            this.eventId = eventId;
            this.raw = raw;
        }
        
        public boolean isValid() {
            return type != null && raw != null;
        }
        
        // ============================================
        // SESSION EVENTS
        // ============================================
        
        public boolean isSessionCreated() {
            return EventTypes.Server.SESSION_CREATED.equals(type);
        }
        
        public boolean isSessionUpdated() {
            return EventTypes.Server.SESSION_UPDATED.equals(type);
        }
        
        // ============================================
        // ERROR EVENTS
        // ============================================
        
        public boolean isError() {
            return EventTypes.Server.ERROR.equals(type);
        }
        
        public ErrorInfo getErrorInfo() {
            if (!isError()) return null;
            try {
                ErrorInfo error = new ErrorInfo();
                if (raw.has("error")) {
                    JsonObject errObj = raw.getAsJsonObject("error");
                    error.type = errObj.has("type") ? errObj.get("type").getAsString() : null;
                    error.code = errObj.has("code") ? errObj.get("code").getAsString() : null;
                    error.message = errObj.has("message") ? errObj.get("message").getAsString() : null;
                    error.param = errObj.has("param") ? errObj.get("param").getAsString() : null;
                }
                return error;
            } catch (Exception e) {
                return null;
            }
        }
        
        // ============================================
        // INPUT AUDIO EVENTS
        // ============================================
        
        public boolean isSpeechStarted() {
            return EventTypes.Server.INPUT_AUDIO_BUFFER_SPEECH_STARTED.equals(type);
        }
        
        public boolean isSpeechStopped() {
            return EventTypes.Server.INPUT_AUDIO_BUFFER_SPEECH_STOPPED.equals(type);
        }
        
        public boolean isInputCommitted() {
            return EventTypes.Server.INPUT_AUDIO_BUFFER_COMMITTED.equals(type);
        }
        
        // ============================================
        // RESPONSE EVENTS
        // ============================================
        
        public boolean isResponseCreated() {
            return EventTypes.Server.RESPONSE_CREATED.equals(type);
        }
        
        public boolean isResponseDone() {
            return EventTypes.Server.RESPONSE_DONE.equals(type);
        }
        
        public boolean isResponseCancelled() {
            return EventTypes.Server.RESPONSE_CANCELLED.equals(type);
        }
        
        public ResponseInfo getResponseInfo() {
            try {
                ResponseInfo info = new ResponseInfo();
                if (raw.has("response")) {
                    JsonObject respObj = raw.getAsJsonObject("response");
                    info.id = respObj.has("id") ? respObj.get("id").getAsString() : null;
                    info.status = respObj.has("status") ? respObj.get("status").getAsString() : null;
                    
                    // Parse output items
                    if (respObj.has("output")) {
                        JsonArray outputArray = respObj.getAsJsonArray("output");
                        info.outputItems = new ArrayList<>();
                        for (JsonElement elem : outputArray) {
                            info.outputItems.add(parseOutputItem(elem.getAsJsonObject()));
                        }
                    }
                }
                return info;
            } catch (Exception e) {
                Logger.e("ServerEvents", "Failed to parse response info: %s", e.getMessage());
                return null;
            }
        }
        
        // ============================================
        // TRANSCRIPT EVENTS
        // ============================================
        
        public boolean isTranscriptDelta() {
            return EventTypes.Server.RESPONSE_OUTPUT_AUDIO_TRANSCRIPT_DELTA.equals(type);
        }
        
        public boolean isTranscriptDone() {
            return EventTypes.Server.RESPONSE_OUTPUT_AUDIO_TRANSCRIPT_DONE.equals(type);
        }
        
        public String getTranscriptDelta() {
            if (!isTranscriptDelta()) return null;
            return raw.has("delta") ? raw.get("delta").getAsString() : null;
        }
        
        public String getTranscript() {
            if (!isTranscriptDone()) return null;
            return raw.has("transcript") ? raw.get("transcript").getAsString() : null;
        }
        
        // ============================================
        // TEXT EVENTS
        // ============================================
        
        public boolean isTextDelta() {
            return EventTypes.Server.RESPONSE_OUTPUT_TEXT_DELTA.equals(type);
        }
        
        public boolean isTextDone() {
            return EventTypes.Server.RESPONSE_OUTPUT_TEXT_DONE.equals(type);
        }
        
        public String getTextDelta() {
            if (!isTextDelta()) return null;
            return raw.has("delta") ? raw.get("delta").getAsString() : null;
        }
        
        public String getText() {
            if (!isTextDone()) return null;
            return raw.has("text") ? raw.get("text").getAsString() : null;
        }
        
        // ============================================
        // FUNCTION CALL EVENTS
        // ============================================
        
        public boolean isFunctionCallArgumentsDelta() {
            return EventTypes.Server.RESPONSE_FUNCTION_CALL_ARGUMENTS_DELTA.equals(type);
        }
        
        public boolean isFunctionCallArgumentsDone() {
            return EventTypes.Server.RESPONSE_FUNCTION_CALL_ARGUMENTS_DONE.equals(type);
        }
        
        public FunctionCallInfo getFunctionCallInfo() {
            try {
                FunctionCallInfo info = new FunctionCallInfo();
                info.callId = raw.has("call_id") ? raw.get("call_id").getAsString() : null;
                info.name = raw.has("name") ? raw.get("name").getAsString() : null;
                info.arguments = raw.has("arguments") ? raw.get("arguments").getAsString() : null;
                return info;
            } catch (Exception e) {
                return null;
            }
        }
        
        // ============================================
        // CONVERSATION ITEM EVENTS
        // ============================================
        
        public boolean isConversationItemAdded() {
            return EventTypes.Server.CONVERSATION_ITEM_ADDED.equals(type);
        }
        
        public boolean isConversationItemDone() {
            return EventTypes.Server.CONVERSATION_ITEM_DONE.equals(type);
        }
        
        // ============================================
        // RATE LIMITS
        // ============================================
        
        public boolean isRateLimitsUpdated() {
            return EventTypes.Server.RATE_LIMITS_UPDATED.equals(type);
        }
        
        // ============================================
        // HELPER METHODS
        // ============================================
        
        private OutputItem parseOutputItem(JsonObject obj) {
            OutputItem item = new OutputItem();
            item.id = obj.has("id") ? obj.get("id").getAsString() : null;
            item.type = obj.has("type") ? obj.get("type").getAsString() : null;
            item.status = obj.has("status") ? obj.get("status").getAsString() : null;
            
            // Function call specific fields
            if (EventTypes.ItemType.FUNCTION_CALL.equals(item.type)) {
                item.name = obj.has("name") ? obj.get("name").getAsString() : null;
                item.callId = obj.has("call_id") ? obj.get("call_id").getAsString() : null;
                item.arguments = obj.has("arguments") ? obj.get("arguments").getAsString() : null;
            }
            
            return item;
        }
    }
    
    // ============================================
    // DATA CLASSES
    // ============================================
    
    public static class ErrorInfo {
        public String type;
        public String code;
        public String message;
        public String param;
        
        @Override
        public String toString() {
            return "Error{type=" + type + ", code=" + code + ", message=" + message + "}";
        }
    }
    
    public static class ResponseInfo {
        public String id;
        public String status;
        public List<OutputItem> outputItems;
        
        public boolean isCompleted() {
            return EventTypes.ResponseStatus.COMPLETED.equals(status);
        }
        
        public boolean hasFunctionCall() {
            if (outputItems == null) return false;
            for (OutputItem item : outputItems) {
                if (EventTypes.ItemType.FUNCTION_CALL.equals(item.type)) {
                    return true;
                }
            }
            return false;
        }
        
        public OutputItem getFunctionCall() {
            if (outputItems == null) return null;
            for (OutputItem item : outputItems) {
                if (EventTypes.ItemType.FUNCTION_CALL.equals(item.type)) {
                    return item;
                }
            }
            return null;
        }
    }
    
    public static class OutputItem {
        public String id;
        public String type;
        public String status;
        public String name;      // Function name
        public String callId;    // Function call ID
        public String arguments; // Function arguments JSON
    }
    
    public static class FunctionCallInfo {
        public String callId;
        public String name;
        public String arguments;
    }
}
