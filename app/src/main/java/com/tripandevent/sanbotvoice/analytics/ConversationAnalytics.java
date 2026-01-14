package com.tripandevent.sanbotvoice.analytics;

import android.content.Context;
import android.content.SharedPreferences;

import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks conversation analytics
 */
public class ConversationAnalytics {
    
    private static final String TAG = "Analytics";
    private static final String PREFS_NAME = "conversation_analytics";
    
    private final SharedPreferences prefs;
    
    private String currentSessionId;
    private long sessionStartTime;
    private int userMessageCount;
    private int aiMessageCount;
    private int errorCount;
    private List<Long> responseTimes;
    private long lastUserMessageTime;
    
    public ConversationAnalytics(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.responseTimes = new ArrayList<>();
    }
    
    public void startSession() {
        currentSessionId = UUID.randomUUID().toString();
        sessionStartTime = System.currentTimeMillis();
        userMessageCount = 0;
        aiMessageCount = 0;
        errorCount = 0;
        responseTimes.clear();
        lastUserMessageTime = 0;
        Logger.d(TAG, "Session started: %s", currentSessionId);
    }
    
    public SessionSummary endSession() {
        if (currentSessionId == null) return null;
        
        long duration = System.currentTimeMillis() - sessionStartTime;
        long avgResponseTime = 0;
        
        if (!responseTimes.isEmpty()) {
            long sum = 0;
            for (Long time : responseTimes) sum += time;
            avgResponseTime = sum / responseTimes.size();
        }
        
        SessionSummary summary = new SessionSummary();
        summary.sessionId = currentSessionId;
        summary.durationMs = duration;
        summary.userMessageCount = userMessageCount;
        summary.aiMessageCount = aiMessageCount;
        summary.errorCount = errorCount;
        summary.avgResponseTimeMs = avgResponseTime;
        
        updateAggregateStats(summary);
        Logger.d(TAG, "Session ended: %s", summary.toString());
        
        currentSessionId = null;
        return summary;
    }
    
    public void recordUserMessage() {
        userMessageCount++;
        lastUserMessageTime = System.currentTimeMillis();
    }
    
    public void recordAiResponse() {
        aiMessageCount++;
        if (lastUserMessageTime > 0) {
            responseTimes.add(System.currentTimeMillis() - lastUserMessageTime);
            lastUserMessageTime = 0;
        }
    }
    
    public void recordError(String errorType) {
        errorCount++;
        Logger.d(TAG, "Error recorded: %s", errorType);
    }
    
    public long getCurrentSessionDuration() {
        if (sessionStartTime == 0) return 0;
        return System.currentTimeMillis() - sessionStartTime;
    }
    
    public AggregateStats getAggregateStats() {
        AggregateStats stats = new AggregateStats();
        stats.totalSessions = prefs.getInt("total_sessions", 0);
        stats.totalDurationMs = prefs.getLong("total_duration_ms", 0);
        stats.totalUserMessages = prefs.getInt("total_user_messages", 0);
        stats.totalAiMessages = prefs.getInt("total_ai_messages", 0);
        stats.totalErrors = prefs.getInt("total_errors", 0);
        stats.avgResponseTimeMs = prefs.getLong("avg_response_time_ms", 0);
        return stats;
    }
    
    private void updateAggregateStats(SessionSummary session) {
        SharedPreferences.Editor editor = prefs.edit();
        
        int totalSessions = prefs.getInt("total_sessions", 0) + 1;
        long totalDuration = prefs.getLong("total_duration_ms", 0) + session.durationMs;
        int totalUserMessages = prefs.getInt("total_user_messages", 0) + session.userMessageCount;
        int totalAiMessages = prefs.getInt("total_ai_messages", 0) + session.aiMessageCount;
        int totalErrors = prefs.getInt("total_errors", 0) + session.errorCount;
        
        long prevAvgResponseTime = prefs.getLong("avg_response_time_ms", 0);
        long newAvgResponseTime = (prevAvgResponseTime * (totalSessions - 1) + session.avgResponseTimeMs) / totalSessions;
        
        editor.putInt("total_sessions", totalSessions);
        editor.putLong("total_duration_ms", totalDuration);
        editor.putInt("total_user_messages", totalUserMessages);
        editor.putInt("total_ai_messages", totalAiMessages);
        editor.putInt("total_errors", totalErrors);
        editor.putLong("avg_response_time_ms", newAvgResponseTime);
        editor.apply();
    }
    
    public static class SessionSummary {
        public String sessionId;
        public long durationMs;
        public int userMessageCount;
        public int aiMessageCount;
        public int errorCount;
        public long avgResponseTimeMs;
        
        @Override
        public String toString() {
            return String.format("Session: duration=%dms, messages=%d/%d, errors=%d, avgResponse=%dms",
                durationMs, userMessageCount, aiMessageCount, errorCount, avgResponseTimeMs);
        }
    }
    
    public static class AggregateStats {
        public int totalSessions;
        public long totalDurationMs;
        public int totalUserMessages;
        public int totalAiMessages;
        public int totalErrors;
        public long avgResponseTimeMs;
    }
}