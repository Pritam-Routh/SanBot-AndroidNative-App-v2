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
 * Parser for OpenAI Realtime API server events (GA Version).
 * 
 * Converts JSON events received via WebRTC DataChannel to typed objects.
 * 
 * GA Changes:
 * - response.audio.delta → response.output_audio.delta
 * - response.text.delta → response.output_text.delta
 * - response.audio_transcript.delta → response.output_audio_transcript.delta
 * - conversation.item.created → conversation.item.added / conversation.item.done
 */
public class ServerEvents {
    
    private static final String TAG = "ServerEvents";
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
            Logger.e(TAG, "Failed to parse event: %s", e.getMessage());
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
            return "session.created".equals(type);
        }
        
        public boolean isSessionUpdated() {
            return "session.updated".equals(type);
        }
        
        /**
         * Get session ID from session.created or session.updated event
         */
        public String getSessionId() {
            try {
                if (raw.has("session")) {
                    JsonObject session = raw.getAsJsonObject("session");
                    if (session.has("id")) {
                        return session.get("id").getAsString();
                    }
                }
            } catch (Exception e) {
                Logger.e(TAG, "Failed to get session ID: %s", e.getMessage());
            }
            return null;
        }
        
        // ============================================
        // ERROR EVENTS
        // ============================================
        
        public boolean isError() {
            return "error".equals(type);
        }
        
        public ErrorInfo getErrorInfo() {
            if (!isError()) return null;
            try {
                ErrorInfo error = new ErrorInfo();
                if (raw.has("error")) {
                    JsonObject errObj = raw.getAsJsonObject("error");
                    error.type = getStringOrNull(errObj, "type");
                    error.code = getStringOrNull(errObj, "code");
                    error.message = getStringOrNull(errObj, "message");
                    error.param = getStringOrNull(errObj, "param");
                    error.eventId = getStringOrNull(errObj, "event_id");
                }
                return error;
            } catch (Exception e) {
                Logger.e(TAG, "Failed to parse error info: %s", e.getMessage());
                return null;
            }
        }
        
        // ============================================
        // INPUT AUDIO EVENTS
        // ============================================
        
        public boolean isSpeechStarted() {
            return "input_audio_buffer.speech_started".equals(type);
        }
        
        public boolean isSpeechStopped() {
            return "input_audio_buffer.speech_stopped".equals(type);
        }
        
        public boolean isInputCommitted() {
            return "input_audio_buffer.committed".equals(type);
        }
        
        public boolean isInputCleared() {
            return "input_audio_buffer.cleared".equals(type);
        }
        
        // ============================================
        // RESPONSE EVENTS
        // ============================================
        
        public boolean isResponseCreated() {
            return "response.created".equals(type);
        }
        
        public boolean isResponseDone() {
            return "response.done".equals(type);
        }
        
        public boolean isResponseCancelled() {
            return "response.cancelled".equals(type);
        }
        
        public ResponseInfo getResponseInfo() {
            try {
                ResponseInfo info = new ResponseInfo();
                if (raw.has("response")) {
                    JsonObject respObj = raw.getAsJsonObject("response");
                    info.id = getStringOrNull(respObj, "id");
                    info.object = getStringOrNull(respObj, "object");
                    info.status = getStringOrNull(respObj, "status");
                    
                    // Parse status details if present
                    if (respObj.has("status_details") && !respObj.get("status_details").isJsonNull()) {
                        JsonObject statusDetails = respObj.getAsJsonObject("status_details");
                        info.statusType = getStringOrNull(statusDetails, "type");
                        info.statusReason = getStringOrNull(statusDetails, "reason");
                    }
                    
                    // Parse output items
                    if (respObj.has("output") && respObj.get("output").isJsonArray()) {
                        JsonArray outputArray = respObj.getAsJsonArray("output");
                        info.outputItems = new ArrayList<>();
                        for (JsonElement elem : outputArray) {
                            if (elem.isJsonObject()) {
                                info.outputItems.add(parseOutputItem(elem.getAsJsonObject()));
                            }
                        }
                    }
                    
                    // Parse usage info if present
                    if (respObj.has("usage") && !respObj.get("usage").isJsonNull()) {
                        JsonObject usageObj = respObj.getAsJsonObject("usage");
                        info.usage = new UsageInfo();
                        info.usage.totalTokens = getIntOrZero(usageObj, "total_tokens");
                        info.usage.inputTokens = getIntOrZero(usageObj, "input_tokens");
                        info.usage.outputTokens = getIntOrZero(usageObj, "output_tokens");
                    }
                }
                return info;
            } catch (Exception e) {
                Logger.e(TAG, "Failed to parse response info: %s", e.getMessage());
                return null;
            }
        }
        
        // ============================================
        // TRANSCRIPT EVENTS (GA naming)
        // ============================================
        
        /**
         * Check for transcript delta event (supports both beta and GA naming)
         */
        public boolean isTranscriptDelta() {
            return "response.output_audio_transcript.delta".equals(type) ||
                   "response.audio_transcript.delta".equals(type); // Beta compatibility
        }
        
        /**
         * Check for transcript done event (supports both beta and GA naming)
         */
        public boolean isTranscriptDone() {
            return "response.output_audio_transcript.done".equals(type) ||
                   "response.audio_transcript.done".equals(type); // Beta compatibility
        }
        
        public String getTranscriptDelta() {
            if (!isTranscriptDelta()) return null;
            return getStringOrNull(raw, "delta");
        }
        
        public String getTranscript() {
            if (!isTranscriptDone()) return null;
            return getStringOrNull(raw, "transcript");
        }
        
        // ============================================
        // TEXT EVENTS (GA naming)
        // ============================================
        
        /**
         * Check for text delta event (supports both beta and GA naming)
         */
        public boolean isTextDelta() {
            return "response.output_text.delta".equals(type) ||
                   "response.text.delta".equals(type); // Beta compatibility
        }
        
        /**
         * Check for text done event (supports both beta and GA naming)
         */
        public boolean isTextDone() {
            return "response.output_text.done".equals(type) ||
                   "response.text.done".equals(type); // Beta compatibility
        }
        
        public String getTextDelta() {
            if (!isTextDelta()) return null;
            return getStringOrNull(raw, "delta");
        }
        
        public String getText() {
            if (!isTextDone()) return null;
            return getStringOrNull(raw, "text");
        }
        
        // ============================================
        // AUDIO EVENTS (GA naming)
        // ============================================
        
        /**
         * Check for audio delta event (supports both beta and GA naming)
         */
        public boolean isAudioDelta() {
            return "response.output_audio.delta".equals(type) ||
                   "response.audio.delta".equals(type); // Beta compatibility
        }
        
        /**
         * Check for audio done event (supports both beta and GA naming)
         */
        public boolean isAudioDone() {
            return "response.output_audio.done".equals(type) ||
                   "response.audio.done".equals(type); // Beta compatibility
        }
        
        /**
         * Get base64-encoded audio chunk
         */
        public String getAudioDelta() {
            if (!isAudioDelta()) return null;
            return getStringOrNull(raw, "delta");
        }

        /**
         * Get item_id from audio delta event (needed for truncation)
         */
        public String getAudioItemId() {
            if (!isAudioDelta() && !isAudioDone()) return null;
            return getStringOrNull(raw, "item_id");
        }

        /**
         * Get content_index from audio delta event (needed for truncation)
         */
        public int getAudioContentIndex() {
            if (!isAudioDelta() && !isAudioDone()) return 0;
            return getIntOrDefault(raw, "content_index", 0);
        }
        
        // ============================================
        // FUNCTION CALL EVENTS
        // ============================================
        
        public boolean isFunctionCallArgumentsDelta() {
            return "response.function_call_arguments.delta".equals(type);
        }
        
        public boolean isFunctionCallArgumentsDone() {
            return "response.function_call_arguments.done".equals(type);
        }
        
        /**
         * Get function call info from function_call_arguments.done event
         */
        public FunctionCallInfo getFunctionCallInfo() {
            try {
                FunctionCallInfo info = new FunctionCallInfo();
                info.callId = getStringOrNull(raw, "call_id");
                info.name = getStringOrNull(raw, "name");
                info.arguments = getStringOrNull(raw, "arguments");
                
                // Also check for item_id and output_index if present
                info.itemId = getStringOrNull(raw, "item_id");
                info.outputIndex = getIntOrDefault(raw, "output_index", -1);
                
                return info;
            } catch (Exception e) {
                Logger.e(TAG, "Failed to parse function call info: %s", e.getMessage());
                return null;
            }
        }
        
        /**
         * Check if response.done contains a function call
         */
        public boolean isResponseWithFunctionCall() {
            if (!"response.done".equals(type)) return false;
            ResponseInfo info = getResponseInfo();
            return info != null && info.hasFunctionCall();
        }
        
        /**
         * Get function call from response.done event (GA format)
         * In GA, function calls are in response.output[] with type="function_call"
         */
        public FunctionCallInfo getFunctionCallFromResponse() {
            if (!isResponseDone()) return null;
            
            ResponseInfo info = getResponseInfo();
            if (info == null || !info.hasFunctionCall()) return null;
            
            OutputItem funcItem = info.getFunctionCall();
            if (funcItem == null) return null;
            
            FunctionCallInfo funcInfo = new FunctionCallInfo();
            funcInfo.callId = funcItem.callId;
            funcInfo.name = funcItem.name;
            funcInfo.arguments = funcItem.arguments;
            funcInfo.itemId = funcItem.id;
            funcInfo.status = funcItem.status;
            
            return funcInfo;
        }
        
        // ============================================
        // CONVERSATION ITEM EVENTS (GA naming)
        // ============================================
        
        /**
         * Check for conversation item added (GA) or created (Beta)
         */
        public boolean isConversationItemAdded() {
            return "conversation.item.added".equals(type) ||
                   "conversation.item.created".equals(type); // Beta compatibility
        }
        
        public boolean isConversationItemDone() {
            return "conversation.item.done".equals(type);
        }
        
        public boolean isConversationItemDeleted() {
            return "conversation.item.deleted".equals(type);
        }
        
        public boolean isConversationItemTruncated() {
            return "conversation.item.truncated".equals(type);
        }
        
        /**
         * Get conversation item details
         */
        public ConversationItemInfo getConversationItemInfo() {
            try {
                ConversationItemInfo info = new ConversationItemInfo();
                info.previousItemId = getStringOrNull(raw, "previous_item_id");
                
                if (raw.has("item") && raw.get("item").isJsonObject()) {
                    JsonObject item = raw.getAsJsonObject("item");
                    info.id = getStringOrNull(item, "id");
                    info.object = getStringOrNull(item, "object");
                    info.type = getStringOrNull(item, "type");
                    info.status = getStringOrNull(item, "status");
                    info.role = getStringOrNull(item, "role");
                    
                    // Function call specific
                    info.name = getStringOrNull(item, "name");
                    info.callId = getStringOrNull(item, "call_id");
                    info.arguments = getStringOrNull(item, "arguments");
                    info.output = getStringOrNull(item, "output");
                }
                return info;
            } catch (Exception e) {
                Logger.e(TAG, "Failed to parse conversation item info: %s", e.getMessage());
                return null;
            }
        }
        
        // ============================================
        // RESPONSE OUTPUT ITEM EVENTS
        // ============================================
        
        public boolean isResponseOutputItemAdded() {
            return "response.output_item.added".equals(type);
        }
        
        public boolean isResponseOutputItemDone() {
            return "response.output_item.done".equals(type);
        }
        
        // ============================================
        // RATE LIMITS
        // ============================================
        
        public boolean isRateLimitsUpdated() {
            return "rate_limits.updated".equals(type);
        }
        
        public RateLimitsInfo getRateLimitsInfo() {
            try {
                RateLimitsInfo info = new RateLimitsInfo();
                if (raw.has("rate_limits") && raw.get("rate_limits").isJsonArray()) {
                    JsonArray limitsArray = raw.getAsJsonArray("rate_limits");
                    info.limits = new ArrayList<>();
                    for (JsonElement elem : limitsArray) {
                        if (elem.isJsonObject()) {
                            JsonObject limitObj = elem.getAsJsonObject();
                            RateLimitItem item = new RateLimitItem();
                            item.name = getStringOrNull(limitObj, "name");
                            item.limit = getIntOrZero(limitObj, "limit");
                            item.remaining = getIntOrZero(limitObj, "remaining");
                            item.resetSeconds = getDoubleOrZero(limitObj, "reset_seconds");
                            info.limits.add(item);
                        }
                    }
                }
                return info;
            } catch (Exception e) {
                Logger.e(TAG, "Failed to parse rate limits: %s", e.getMessage());
                return null;
            }
        }
        
        // ============================================
        // INPUT AUDIO TRANSCRIPTION EVENTS
        // ============================================
        
        public boolean isInputAudioTranscriptionCompleted() {
            return "conversation.item.input_audio_transcription.completed".equals(type);
        }
        
        public boolean isInputAudioTranscriptionFailed() {
            return "conversation.item.input_audio_transcription.failed".equals(type);
        }
        
        /**
         * Get user's transcribed speech
         */
        public String getInputTranscript() {
            if (!isInputAudioTranscriptionCompleted()) return null;
            return getStringOrNull(raw, "transcript");
        }
        
        // ============================================
        // HELPER METHODS
        // ============================================
        
        private OutputItem parseOutputItem(JsonObject obj) {
            OutputItem item = new OutputItem();
            item.id = getStringOrNull(obj, "id");
            item.object = getStringOrNull(obj, "object");
            item.type = getStringOrNull(obj, "type");
            item.status = getStringOrNull(obj, "status");
            item.role = getStringOrNull(obj, "role");
            
            // Function call specific fields
            if ("function_call".equals(item.type)) {
                item.name = getStringOrNull(obj, "name");
                item.callId = getStringOrNull(obj, "call_id");
                item.arguments = getStringOrNull(obj, "arguments");
            }
            
            // Content array (for message types)
            if (obj.has("content") && obj.get("content").isJsonArray()) {
                JsonArray contentArray = obj.getAsJsonArray("content");
                item.content = new ArrayList<>();
                for (JsonElement elem : contentArray) {
                    if (elem.isJsonObject()) {
                        JsonObject contentObj = elem.getAsJsonObject();
                        ContentItem contentItem = new ContentItem();
                        contentItem.type = getStringOrNull(contentObj, "type");
                        contentItem.text = getStringOrNull(contentObj, "text");
                        contentItem.transcript = getStringOrNull(contentObj, "transcript");
                        item.content.add(contentItem);
                    }
                }
            }
            
            return item;
        }
        
        private String getStringOrNull(JsonObject obj, String key) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsString();
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }
        
        private int getIntOrZero(JsonObject obj, String key) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception e) {
                    return 0;
                }
            }
            return 0;
        }
        
        private int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception e) {
                    return defaultValue;
                }
            }
            return defaultValue;
        }
        
        private double getDoubleOrZero(JsonObject obj, String key) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsDouble();
                } catch (Exception e) {
                    return 0.0;
                }
            }
            return 0.0;
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
        public String eventId;
        
        @Override
        public String toString() {
            return "Error{type=" + type + ", code=" + code + ", message=" + message + "}";
        }
    }
    
    public static class ResponseInfo {
        public String id;
        public String object;
        public String status;
        public String statusType;
        public String statusReason;
        public List<OutputItem> outputItems;
        public UsageInfo usage;
        
        public boolean isCompleted() {
            return "completed".equals(status);
        }
        
        public boolean isCancelled() {
            return "cancelled".equals(status);
        }
        
        public boolean isFailed() {
            return "failed".equals(status);
        }
        
        public boolean isIncomplete() {
            return "incomplete".equals(status);
        }
        
        public boolean hasFunctionCall() {
            if (outputItems == null) return false;
            for (OutputItem item : outputItems) {
                if ("function_call".equals(item.type)) {
                    return true;
                }
            }
            return false;
        }
        
        /**
         * Get the first function call from output items
         */
        public OutputItem getFunctionCall() {
            if (outputItems == null) return null;
            for (OutputItem item : outputItems) {
                if ("function_call".equals(item.type)) {
                    return item;
                }
            }
            return null;
        }
        
        /**
         * Get all function calls from output items
         */
        public List<OutputItem> getAllFunctionCalls() {
            List<OutputItem> calls = new ArrayList<>();
            if (outputItems == null) return calls;
            for (OutputItem item : outputItems) {
                if ("function_call".equals(item.type)) {
                    calls.add(item);
                }
            }
            return calls;
        }
    }
    
    public static class OutputItem {
        public String id;
        public String object;  // "realtime.item" in GA
        public String type;    // "message", "function_call", "function_call_output"
        public String status;  // "completed", "in_progress", etc.
        public String role;    // "user", "assistant", "system"
        public String name;      // Function name
        public String callId;    // Function call ID
        public String arguments; // Function arguments JSON
        public List<ContentItem> content;
        
        public boolean isFunctionCall() {
            return "function_call".equals(type);
        }
        
        public boolean isMessage() {
            return "message".equals(type);
        }
    }
    
    public static class ContentItem {
        public String type;      // "output_text", "output_audio", "input_text", etc.
        public String text;      // For text content
        public String transcript; // For audio transcript
    }
    
    public static class FunctionCallInfo {
        public String callId;
        public String name;
        public String arguments;
        public String itemId;
        public int outputIndex = -1;
        public String status;
        
        @Override
        public String toString() {
            return "FunctionCall{name=" + name + ", callId=" + callId + ", args=" + arguments + "}";
        }
    }
    
    public static class ConversationItemInfo {
        public String id;
        public String object;
        public String type;
        public String status;
        public String role;
        public String previousItemId;
        
        // Function call specific
        public String name;
        public String callId;
        public String arguments;
        public String output;
    }
    
    public static class UsageInfo {
        public int totalTokens;
        public int inputTokens;
        public int outputTokens;
    }
    
    public static class RateLimitsInfo {
        public List<RateLimitItem> limits;
    }
    
    public static class RateLimitItem {
        public String name;
        public int limit;
        public int remaining;
        public double resetSeconds;
    }
}