package com.tripandevent.sanbotvoice.openai.events;

/**
 * Constants for OpenAI Realtime API event types.
 * Reference: https://platform.openai.com/docs/api-reference/realtime
 */
public final class EventTypes {
    
    private EventTypes() {}
    
    // ============================================
    // CLIENT EVENTS (sent by app)
    // ============================================
    
    public static final class Client {
        private Client() {}
        
        // Session events
        public static final String SESSION_UPDATE = "session.update";
        
        // Input audio buffer events
        public static final String INPUT_AUDIO_BUFFER_APPEND = "input_audio_buffer.append";
        public static final String INPUT_AUDIO_BUFFER_COMMIT = "input_audio_buffer.commit";
        public static final String INPUT_AUDIO_BUFFER_CLEAR = "input_audio_buffer.clear";
        
        // Conversation events
        public static final String CONVERSATION_ITEM_CREATE = "conversation.item.create";
        public static final String CONVERSATION_ITEM_TRUNCATE = "conversation.item.truncate";
        public static final String CONVERSATION_ITEM_DELETE = "conversation.item.delete";
        
        // Response events
        public static final String RESPONSE_CREATE = "response.create";
        public static final String RESPONSE_CANCEL = "response.cancel";
        
        // Output audio buffer events (WebRTC)
        public static final String OUTPUT_AUDIO_BUFFER_CLEAR = "output_audio_buffer.clear";
    }
    
    // ============================================
    // SERVER EVENTS (received from OpenAI)
    // ============================================
    
    public static final class Server {
        private Server() {}
        
        // Error events
        public static final String ERROR = "error";
        
        // Session events
        public static final String SESSION_CREATED = "session.created";
        public static final String SESSION_UPDATED = "session.updated";
        
        // Conversation events
        public static final String CONVERSATION_CREATED = "conversation.created";
        public static final String CONVERSATION_ITEM_ADDED = "conversation.item.added";
        public static final String CONVERSATION_ITEM_DONE = "conversation.item.done";
        public static final String CONVERSATION_ITEM_DELETED = "conversation.item.deleted";
        public static final String CONVERSATION_ITEM_TRUNCATED = "conversation.item.truncated";
        
        // Input audio buffer events
        public static final String INPUT_AUDIO_BUFFER_COMMITTED = "input_audio_buffer.committed";
        public static final String INPUT_AUDIO_BUFFER_CLEARED = "input_audio_buffer.cleared";
        public static final String INPUT_AUDIO_BUFFER_SPEECH_STARTED = "input_audio_buffer.speech_started";
        public static final String INPUT_AUDIO_BUFFER_SPEECH_STOPPED = "input_audio_buffer.speech_stopped";
        
        // Response events
        public static final String RESPONSE_CREATED = "response.created";
        public static final String RESPONSE_DONE = "response.done";
        public static final String RESPONSE_CANCELLED = "response.cancelled";
        
        // Response output item events
        public static final String RESPONSE_OUTPUT_ITEM_ADDED = "response.output_item.added";
        public static final String RESPONSE_OUTPUT_ITEM_DONE = "response.output_item.done";
        
        // Response content part events
        public static final String RESPONSE_CONTENT_PART_ADDED = "response.content_part.added";
        public static final String RESPONSE_CONTENT_PART_DONE = "response.content_part.done";
        
        // Response text events
        public static final String RESPONSE_OUTPUT_TEXT_DELTA = "response.output_text.delta";
        public static final String RESPONSE_OUTPUT_TEXT_DONE = "response.output_text.done";
        
        // Response audio events
        public static final String RESPONSE_OUTPUT_AUDIO_DELTA = "response.output_audio.delta";
        public static final String RESPONSE_OUTPUT_AUDIO_DONE = "response.output_audio.done";
        
        // Response audio transcript events
        public static final String RESPONSE_OUTPUT_AUDIO_TRANSCRIPT_DELTA = "response.output_audio_transcript.delta";
        public static final String RESPONSE_OUTPUT_AUDIO_TRANSCRIPT_DONE = "response.output_audio_transcript.done";
        
        // Function call events
        public static final String RESPONSE_FUNCTION_CALL_ARGUMENTS_DELTA = "response.function_call_arguments.delta";
        public static final String RESPONSE_FUNCTION_CALL_ARGUMENTS_DONE = "response.function_call_arguments.done";
        
        // Rate limits
        public static final String RATE_LIMITS_UPDATED = "rate_limits.updated";
    }
    
    // ============================================
    // ITEM TYPES
    // ============================================
    
    public static final class ItemType {
        private ItemType() {}
        
        public static final String MESSAGE = "message";
        public static final String FUNCTION_CALL = "function_call";
        public static final String FUNCTION_CALL_OUTPUT = "function_call_output";
    }
    
    // ============================================
    // CONTENT TYPES
    // ============================================
    
    public static final class ContentType {
        private ContentType() {}
        
        public static final String INPUT_TEXT = "input_text";
        public static final String INPUT_AUDIO = "input_audio";
        public static final String OUTPUT_TEXT = "output_text";
        public static final String OUTPUT_AUDIO = "output_audio";
    }
    
    // ============================================
    // ROLES
    // ============================================
    
    public static final class Role {
        private Role() {}
        
        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";
        public static final String SYSTEM = "system";
    }
    
    // ============================================
    // RESPONSE STATUS
    // ============================================
    
    public static final class ResponseStatus {
        private ResponseStatus() {}
        
        public static final String IN_PROGRESS = "in_progress";
        public static final String COMPLETED = "completed";
        public static final String CANCELLED = "cancelled";
        public static final String FAILED = "failed";
        public static final String INCOMPLETE = "incomplete";
    }
}
