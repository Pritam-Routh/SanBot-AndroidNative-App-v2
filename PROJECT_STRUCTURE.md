# Sanbot Voice Agent - Project Structure

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        SANBOT VOICE AGENT APP                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────────────┐ │
│  │   Main      │───▶│  Voice      │───▶│  WebRTC Manager             │ │
│  │   Activity  │    │  Service    │    │  (PeerConnection, DataChan) │ │
│  └─────────────┘    └─────────────┘    └─────────────────────────────┘ │
│         │                  │                        │                   │
│         ▼                  ▼                        ▼                   │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────────────┐ │
│  │   UI        │    │  Audio      │    │  OpenAI Realtime API        │ │
│  │   Manager   │    │  Manager    │    │  (via WebRTC)               │ │
│  └─────────────┘    └─────────────┘    └─────────────────────────────┘ │
│                            │                        │                   │
│                            ▼                        ▼                   │
│                     ┌─────────────┐    ┌─────────────────────────────┐ │
│                     │  Sanbot SDK │    │  Function Call Handler      │ │
│                     │  Integration│    │  (save_lead, create_quote)  │ │
│                     └─────────────┘    └─────────────────────────────┘ │
│                                                     │                   │
│                                                     ▼                   │
│                                        ┌─────────────────────────────┐ │
│                                        │  TripAndEvent Backend API   │ │
│                                        │  (bot.tripandevent.com)     │ │
│                                        └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

## Directory Structure

```
SanbotVoiceAgent/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/tripandevent/sanbotvoice/
│   │   │   │   │
│   │   │   │   ├── SanbotVoiceApp.java              # Application class
│   │   │   │   ├── MainActivity.java                # Main entry point
│   │   │   │   │
│   │   │   │   ├── core/                            # Core business logic
│   │   │   │   │   ├── VoiceAgentService.java       # Foreground service for voice
│   │   │   │   │   ├── SessionManager.java          # Manages conversation sessions
│   │   │   │   │   └── ConversationState.java       # State machine for conversations
│   │   │   │   │
│   │   │   │   ├── webrtc/                          # WebRTC implementation
│   │   │   │   │   ├── WebRTCManager.java           # PeerConnection management
│   │   │   │   │   ├── AudioTrackManager.java       # Audio track handling
│   │   │   │   │   ├── DataChannelManager.java      # Event channel management
│   │   │   │   │   └── WebRTCConfig.java            # WebRTC configuration
│   │   │   │   │
│   │   │   │   ├── openai/                          # OpenAI Realtime API
│   │   │   │   │   ├── RealtimeClient.java          # Main client interface
│   │   │   │   │   ├── RealtimeEventHandler.java    # Server event handling
│   │   │   │   │   ├── events/                      # Event models
│   │   │   │   │   │   ├── ClientEvents.java        # Client-sent events
│   │   │   │   │   │   ├── ServerEvents.java        # Server-sent events
│   │   │   │   │   │   └── EventTypes.java          # Event type constants
│   │   │   │   │   └── models/                      # Data models
│   │   │   │   │       ├── SessionConfig.java       # Session configuration
│   │   │   │   │       ├── ConversationItem.java    # Conversation items
│   │   │   │   │       └── FunctionCall.java        # Function call models
│   │   │   │   │
│   │   │   │   ├── api/                             # Backend API integration
│   │   │   │   │   ├── TripAndEventApi.java         # Retrofit interface
│   │   │   │   │   ├── ApiClient.java               # HTTP client setup
│   │   │   │   │   ├── TokenManager.java            # Ephemeral token handling
│   │   │   │   │   └── models/                      # API models
│   │   │   │   │       ├── TokenResponse.java
│   │   │   │   │       ├── CustomerLead.java
│   │   │   │   │       └── QuoteRequest.java
│   │   │   │   │
│   │   │   │   ├── functions/                       # Function call handlers
│   │   │   │   │   ├── FunctionRegistry.java        # Function registration
│   │   │   │   │   ├── FunctionExecutor.java        # Async execution
│   │   │   │   │   ├── SaveCustomerLeadFunction.java
│   │   │   │   │   ├── CreateQuoteFunction.java
│   │   │   │   │   └── DisconnectCallFunction.java
│   │   │   │   │
│   │   │   │   ├── sanbot/                          # Sanbot SDK integration
│   │   │   │   │   ├── SanbotManager.java           # Main SDK manager
│   │   │   │   │   ├── SanbotAudioManager.java      # Audio handling
│   │   │   │   │   ├── SanbotDisplayManager.java    # Display control
│   │   │   │   │   ├── SanbotSpeechManager.java     # Speech synthesis
│   │   │   │   │   └── RobotTypeDetector.java       # Elf vs S5 detection
│   │   │   │   │
│   │   │   │   ├── audio/                           # Audio processing
│   │   │   │   │   ├── AudioCaptureManager.java     # Microphone capture
│   │   │   │   │   ├── AudioPlaybackManager.java    # Speaker output
│   │   │   │   │   └── AudioConfig.java             # Audio settings
│   │   │   │   │
│   │   │   │   ├── ui/                              # User interface
│   │   │   │   │   ├── ConversationActivity.java    # Active conversation UI
│   │   │   │   │   ├── SettingsActivity.java        # Settings screen
│   │   │   │   │   ├── views/                       # Custom views
│   │   │   │   │   │   ├── VoiceAnimationView.java  # Voice visualization
│   │   │   │   │   │   └── TranscriptView.java      # Conversation transcript
│   │   │   │   │   └── adapters/                    # RecyclerView adapters
│   │   │   │   │       └── TranscriptAdapter.java
│   │   │   │   │
│   │   │   │   └── utils/                           # Utilities
│   │   │   │       ├── Logger.java                  # Logging wrapper
│   │   │   │       ├── NetworkUtils.java            # Network helpers
│   │   │   │       ├── JsonUtils.java               # JSON parsing
│   │   │   │       └── Constants.java               # App constants
│   │   │   │
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   ├── activity_conversation.xml
│   │   │   │   │   └── item_transcript.xml
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   └── styles.xml
│   │   │   │   └── drawable/
│   │   │   │       └── ...
│   │   │   │
│   │   │   └── AndroidManifest.xml
│   │   │
│   │   └── test/                                    # Unit tests
│   │
│   └── build.gradle
│
├── gradle/
├── build.gradle                                     # Project-level build
├── settings.gradle
├── gradle.properties
└── README.md
```

## Key Design Decisions

### 1. WebRTC over WebSockets
- Lower latency for real-time voice
- Better handling of network conditions
- Native audio track management

### 2. Foreground Service
- Keeps voice agent alive during interactions
- Proper lifecycle management
- Notification for user awareness

### 3. Ephemeral Token Model
- Never expose API keys in the app
- Tokens fetched from TripAndEvent backend
- Automatic refresh on expiration

### 4. Function Call Architecture
- Registry pattern for extensibility
- Async execution with callbacks
- Proper error handling and fallbacks

### 5. Sanbot SDK Abstraction
- Single codebase for Elf and S5
- Runtime robot type detection
- Graceful fallbacks for missing features

## Data Flow

```
User Speaks → Sanbot Mic → AudioCaptureManager → WebRTC LocalTrack
                                                        │
                                                        ▼
                                              OpenAI Realtime API
                                                        │
                                                        ▼
AI Response ← Sanbot Speaker ← AudioPlaybackManager ← WebRTC RemoteTrack
                                                        │
                                              (if function call)
                                                        │
                                                        ▼
                                              FunctionExecutor
                                                        │
                                                        ▼
                                              TripAndEvent API
```

## Threading Model

- **Main Thread**: UI updates only
- **WebRTC Thread**: Managed by WebRTC library
- **Network Thread**: OkHttp/Retrofit async calls
- **Audio Thread**: Dedicated for audio capture/playback
- **Service Thread**: Background voice processing

## Error Handling Strategy

1. **Network Failures**: Retry with exponential backoff
2. **API Errors**: Log, notify user, graceful degradation
3. **WebRTC Failures**: Attempt reconnection, fallback to offline mode
4. **Sanbot SDK Errors**: Log and continue, no crashes
5. **Function Call Failures**: Return error to model, let it handle gracefully
