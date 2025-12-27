package com.tripandevent.sanbotvoice.core;

/**
 * State machine for voice agent conversation.
 */
public enum ConversationState {
    /**
     * Initial state - not connected
     */
    IDLE,
    
    /**
     * Connecting to OpenAI Realtime API
     */
    CONNECTING,
    
    /**
     * Connected and configuring session
     */
    CONFIGURING,
    
    /**
     * Ready for conversation
     */
    READY,
    
    /**
     * User is speaking
     */
    LISTENING,
    
    /**
     * Processing user input
     */
    PROCESSING,
    
    /**
     * AI is speaking
     */
    SPEAKING,
    
    /**
     * Executing a function call
     */
    EXECUTING_FUNCTION,
    
    /**
     * Disconnecting
     */
    DISCONNECTING,
    
    /**
     * Error state
     */
    ERROR;
    
    /**
     * Check if conversation is active
     */
    public boolean isActive() {
        return this == READY || this == LISTENING || this == PROCESSING || 
               this == SPEAKING || this == EXECUTING_FUNCTION;
    }
    
    /**
     * Check if can accept user input
     */
    public boolean canListen() {
        return this == READY || this == LISTENING;
    }
    
    /**
     * Check if connected to API
     */
    public boolean isConnected() {
        return this != IDLE && this != CONNECTING && this != ERROR && 
               this != DISCONNECTING;
    }
}
