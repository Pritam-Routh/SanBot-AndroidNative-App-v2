package com.tripandevent.sanbotvoice.webrtc;

import org.webrtc.PeerConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * WebRTC configuration for OpenAI Realtime API connection.
 */
public class WebRTCConfig {
    
    /**
     * Get ICE servers configuration.
     * For OpenAI Realtime API, we don't need STUN/TURN servers
     * as the connection is direct to OpenAI's servers.
     */
    public static List<PeerConnection.IceServer> getIceServers() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        
        // Google's public STUN server as fallback
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
            .createIceServer());
        
        return iceServers;
    }
    
    /**
     * Get PeerConnection configuration
     */
    public static PeerConnection.RTCConfiguration getRTCConfiguration() {
        PeerConnection.RTCConfiguration config = 
            new PeerConnection.RTCConfiguration(getIceServers());
        
        // Connection settings optimized for low latency
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        config.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL;
        
        // ICE settings
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        
        return config;
    }
    
    /**
     * Audio constraints for microphone capture
     */
    public static class AudioConstraints {
        // Enable echo cancellation
        public static final boolean ECHO_CANCELLATION = true;
        
        // Enable automatic gain control
        public static final boolean AUTO_GAIN_CONTROL = true;
        
        // Enable noise suppression
        public static final boolean NOISE_SUPPRESSION = true;
        
        // Enable high-pass filter
        public static final boolean HIGH_PASS_FILTER = true;
        
        // Sample rate (required by OpenAI Realtime API)
        public static final int SAMPLE_RATE = 24000;
        
        // Mono audio
        public static final int CHANNELS = 1;
    }
}
