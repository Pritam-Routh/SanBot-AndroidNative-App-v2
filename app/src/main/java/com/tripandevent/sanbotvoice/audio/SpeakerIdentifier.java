package com.tripandevent.sanbotvoice.audio;

import android.content.Context;
import android.content.SharedPreferences;

import com.tripandevent.sanbotvoice.utils.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Speaker identification using voice embeddings.
 */
public class SpeakerIdentifier {
    
    private static final String TAG = "SpeakerIdentifier";
    private static final String PREFS_NAME = "speaker_profiles";
    
    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, SpeakerProfile> profiles;
    
    private static final int SAMPLE_RATE = 24000;
    private static final int FRAME_SIZE = 480;
    private static final int NUM_MFCC = 13;
    private static final float SIMILARITY_THRESHOLD = 0.75f;
    
    public SpeakerIdentifier(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.profiles = new HashMap<>();
        loadProfiles();
    }
    
    public static class SpeakerProfile {
        public String id;
        public String name;
        public float[] voiceEmbedding;
        public float avgPitch;
        public float avgEnergy;
        public long createdAt;
        public int sampleCount;
        
        public SpeakerProfile(String id, String name) {
            this.id = id;
            this.name = name;
            this.voiceEmbedding = new float[NUM_MFCC];
            this.createdAt = System.currentTimeMillis();
            this.sampleCount = 0;
        }
    }
    
    public static class IdentificationResult {
        public String speakerId;
        public String speakerName;
        public float confidence;
        public boolean isNewSpeaker;
        
        public IdentificationResult(String id, String name, float confidence, boolean isNew) {
            this.speakerId = id;
            this.speakerName = name;
            this.confidence = confidence;
            this.isNewSpeaker = isNew;
        }
    }
    
    public IdentificationResult identifySpeaker(short[] audioSamples) {
        if (audioSamples == null || audioSamples.length < FRAME_SIZE) {
            return null;
        }
        
        float[] features = extractFeatures(audioSamples);
        float pitch = estimatePitch(audioSamples);
        float energy = calculateEnergy(audioSamples);
        
        String bestMatchId = null;
        float bestSimilarity = 0;
        
        for (Map.Entry<String, SpeakerProfile> entry : profiles.entrySet()) {
            SpeakerProfile profile = entry.getValue();
            float similarity = calculateSimilarity(features, profile.voiceEmbedding, 
                                                   pitch, profile.avgPitch,
                                                   energy, profile.avgEnergy);
            
            if (similarity > bestSimilarity && similarity >= SIMILARITY_THRESHOLD) {
                bestSimilarity = similarity;
                bestMatchId = entry.getKey();
            }
        }
        
        if (bestMatchId != null) {
            SpeakerProfile profile = profiles.get(bestMatchId);
            updateProfile(profile, features, pitch, energy);
            return new IdentificationResult(profile.id, profile.name, bestSimilarity, false);
        }
        
        return new IdentificationResult(null, "Unknown", 0, true);
    }
    
    public SpeakerProfile registerSpeaker(String name, short[] audioSamples) {
        String id = "speaker_" + System.currentTimeMillis();
        SpeakerProfile profile = new SpeakerProfile(id, name);
        
        float[] features = extractFeatures(audioSamples);
        System.arraycopy(features, 0, profile.voiceEmbedding, 0, features.length);
        profile.avgPitch = estimatePitch(audioSamples);
        profile.avgEnergy = calculateEnergy(audioSamples);
        profile.sampleCount = 1;
        
        profiles.put(id, profile);
        saveProfiles();
        
        Logger.d(TAG, "Registered new speaker: %s (%s)", name, id);
        return profile;
    }
    
    public void trainSpeaker(String speakerId, short[] audioSamples) {
        SpeakerProfile profile = profiles.get(speakerId);
        if (profile == null) return;
        
        float[] features = extractFeatures(audioSamples);
        float pitch = estimatePitch(audioSamples);
        float energy = calculateEnergy(audioSamples);
        
        updateProfile(profile, features, pitch, energy);
        saveProfiles();
    }
    
    public void deleteSpeaker(String speakerId) {
        profiles.remove(speakerId);
        saveProfiles();
    }
    
    public List<SpeakerProfile> getAllSpeakers() {
        return new ArrayList<>(profiles.values());
    }
    
    private float[] extractFeatures(short[] samples) {
        float[] features = new float[NUM_MFCC];
        float[] normalized = new float[samples.length];
        
        for (int i = 0; i < samples.length; i++) {
            normalized[i] = samples[i] / 32768f;
        }
        
        int frameSize = Math.min(FRAME_SIZE, samples.length);
        int numFrames = Math.max(1, samples.length / frameSize);
        
        for (int i = 0; i < NUM_MFCC; i++) {
            float sum = 0;
            for (int frame = 0; frame < numFrames; frame++) {
                int start = frame * frameSize;
                int bandStart = start + (i * frameSize / NUM_MFCC);
                int bandEnd = start + ((i + 1) * frameSize / NUM_MFCC);
                
                float bandEnergy = 0;
                for (int j = bandStart; j < bandEnd && j < samples.length; j++) {
                    bandEnergy += normalized[j] * normalized[j];
                }
                sum += Math.log(bandEnergy + 1e-10);
            }
            features[i] = sum / numFrames;
        }
        
        return features;
    }
    
    private float estimatePitch(short[] samples) {
        int minLag = SAMPLE_RATE / 500;
        int maxLag = SAMPLE_RATE / 50;
        
        float maxCorrelation = 0;
        int bestLag = minLag;
        
        for (int lag = minLag; lag < maxLag && lag < samples.length / 2; lag++) {
            float correlation = 0;
            for (int i = 0; i < samples.length - lag; i++) {
                correlation += (float) samples[i] * samples[i + lag];
            }
            correlation /= (samples.length - lag);
            
            if (correlation > maxCorrelation) {
                maxCorrelation = correlation;
                bestLag = lag;
            }
        }
        
        return (float) SAMPLE_RATE / bestLag;
    }
    
    private float calculateEnergy(short[] samples) {
        long sum = 0;
        for (short sample : samples) {
            sum += (long) sample * sample;
        }
        return (float) Math.sqrt((double) sum / samples.length);
    }
    
    private float calculateSimilarity(float[] features1, float[] features2,
                                     float pitch1, float pitch2,
                                     float energy1, float energy2) {
        float dot = 0, norm1 = 0, norm2 = 0;
        for (int i = 0; i < features1.length; i++) {
            dot += features1[i] * features2[i];
            norm1 += features1[i] * features1[i];
            norm2 += features2[i] * features2[i];
        }
        float mfccSimilarity = dot / (float) (Math.sqrt(norm1) * Math.sqrt(norm2) + 1e-10);
        
        float pitchRatio = Math.min(pitch1, pitch2) / Math.max(pitch1, pitch2);
        float pitchSimilarity = pitchRatio > 0.8f ? pitchRatio : pitchRatio * 0.5f;
        
        float energyRatio = Math.min(energy1, energy2) / Math.max(energy1, energy2);
        
        return 0.6f * mfccSimilarity + 0.3f * pitchSimilarity + 0.1f * energyRatio;
    }
    
    private void updateProfile(SpeakerProfile profile, float[] features, float pitch, float energy) {
        float alpha = 0.1f;
        
        for (int i = 0; i < features.length; i++) {
            profile.voiceEmbedding[i] = (1 - alpha) * profile.voiceEmbedding[i] + alpha * features[i];
        }
        profile.avgPitch = (1 - alpha) * profile.avgPitch + alpha * pitch;
        profile.avgEnergy = (1 - alpha) * profile.avgEnergy + alpha * energy;
        profile.sampleCount++;
    }
    
    private void saveProfiles() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("profile_count", profiles.size());
        
        int index = 0;
        for (SpeakerProfile profile : profiles.values()) {
            String prefix = "profile_" + index + "_";
            editor.putString(prefix + "id", profile.id);
            editor.putString(prefix + "name", profile.name);
            editor.putFloat(prefix + "pitch", profile.avgPitch);
            editor.putFloat(prefix + "energy", profile.avgEnergy);
            editor.putLong(prefix + "created", profile.createdAt);
            editor.putInt(prefix + "samples", profile.sampleCount);
            
            for (int i = 0; i < NUM_MFCC; i++) {
                editor.putFloat(prefix + "emb_" + i, profile.voiceEmbedding[i]);
            }
            index++;
        }
        editor.apply();
    }
    
    private void loadProfiles() {
        int count = prefs.getInt("profile_count", 0);
        
        for (int index = 0; index < count; index++) {
            String prefix = "profile_" + index + "_";
            String id = prefs.getString(prefix + "id", null);
            String name = prefs.getString(prefix + "name", null);
            
            if (id == null || name == null) continue;
            
            SpeakerProfile profile = new SpeakerProfile(id, name);
            profile.avgPitch = prefs.getFloat(prefix + "pitch", 0);
            profile.avgEnergy = prefs.getFloat(prefix + "energy", 0);
            profile.createdAt = prefs.getLong(prefix + "created", 0);
            profile.sampleCount = prefs.getInt(prefix + "samples", 0);
            
            for (int i = 0; i < NUM_MFCC; i++) {
                profile.voiceEmbedding[i] = prefs.getFloat(prefix + "emb_" + i, 0);
            }
            profiles.put(id, profile);
        }
        Logger.d(TAG, "Loaded %d speaker profiles", profiles.size());
    }
}