# Sanbot Voice Agent

A production-grade Android application for Sanbot robots (Sanbot Elf & Sanbot S5) that enables real-time voice conversations using OpenAI's GPT Realtime API with WebRTC.

## Features

- **Low-latency voice conversations** using WebRTC for near-zero latency
- **Automatic speech detection** with Voice Activity Detection (VAD)
- **Function calling** for CRM integration (save leads, create quotes)
- **Production-ready** error handling, logging, and monitoring
- **Sanbot SDK integration** for robot-specific features
- **Ephemeral token security** - API keys never stored on device

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     SANBOT VOICE AGENT                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  MainActivity ──▶ VoiceAgentService ──▶ WebRTCManager          │
│       │                   │                    │                │
│       │                   │                    ▼                │
│       │                   │           OpenAI Realtime API      │
│       │                   │           (WebRTC Connection)       │
│       │                   │                    │                │
│       │                   ▼                    │                │
│       │          FunctionExecutor ◀───────────┘                │
│       │                   │                                     │
│       │                   ▼                                     │
│       │          TripAndEvent Backend                          │
│       │          (bot.tripandevent.com)                        │
│       │                                                         │
│       └──────────▶ UI Updates                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Android Studio Hedgehog or later
- JDK 17
- Sanbot SDK (SanbotOpenSDK.aar)
- Sanbot robot running Android 7.0+ (Elf) or Android 12 (S5)

## Setup

### 1. Clone the repository

```bash
git clone <repository-url>
cd SanbotVoiceAgent
```

### 2. Add Sanbot SDK

Place the Sanbot SDK AAR file in `app/libs/`:

```
app/libs/SanbotOpenSDK.aar
```

### 3. Configure Backend

The app currently uses a test backend by default:

**Test Backend (current):**
```properties
# gradle.properties
TRIPANDEVENT_BASE_URL=https://openai-realtime-backend-v1.onrender.com
```

**Production Backend (when ready):**
```properties
# gradle.properties
TRIPANDEVENT_BASE_URL=https://bot.tripandevent.com
TRIPANDEVENT_API_TOKEN=your_api_token_here
```

Also update `Constants.java`:
```java
// Set to false for production backend
public static final boolean USE_TEST_BACKEND = false;
```

### 4. Build and run

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Backend Modes

### Test Backend
- URL: `https://openai-realtime-backend-v1.onrender.com`
- Endpoints:
  - `GET /token` - Get ephemeral token
  - `POST /session` - SDP exchange (backend proxies to OpenAI)
- No auth required
- Function calls (save_customer_lead, etc.) are no-ops in test mode

### Production Backend
- URL: `https://bot.tripandevent.com`
- Endpoints:
  - `POST /api/trpc/voice.getToken` - Get ephemeral token
  - `POST /api/trpc/sanbot.saveCustomerLead` - Save customer lead
  - `POST /api/trpc/sanbot.createQuote` - Create quote
  - `POST /api/trpc/sanbot.logDisconnect` - Log session end
- Requires Bearer token authentication
- Full function calling support

## Project Structure

```
app/src/main/java/com/tripandevent/sanbotvoice/
├── SanbotVoiceApp.java          # Application class
├── MainActivity.java             # Main UI
├── core/
│   ├── VoiceAgentService.java   # Foreground service
│   └── ConversationState.java   # State machine
├── webrtc/
│   ├── WebRTCManager.java       # WebRTC connection
│   └── WebRTCConfig.java        # Configuration
├── openai/events/
│   ├── ClientEvents.java        # Events sent to API
│   ├── ServerEvents.java        # Events from API
│   └── EventTypes.java          # Event type constants
├── api/
│   ├── ApiClient.java           # HTTP client
│   ├── TokenManager.java        # Ephemeral tokens
│   ├── TripAndEventApi.java     # API interface
│   └── models/                  # Data models
├── functions/
│   ├── FunctionRegistry.java    # Tool definitions
│   ├── FunctionExecutor.java    # Function execution
│   ├── SaveCustomerLeadFunction.java
│   ├── CreateQuoteFunction.java
│   └── DisconnectCallFunction.java
└── utils/
    ├── Constants.java           # App constants
    └── Logger.java              # Logging utility
```

## How It Works

### Connection Flow

1. **Token Request**: App requests ephemeral token from TripAndEvent backend
2. **WebRTC Setup**: Create PeerConnection, audio tracks, data channel
3. **SDP Exchange**: Send offer to OpenAI, receive answer via backend
4. **Session Config**: Configure AI instructions and available tools
5. **Conversation**: Real-time audio via WebRTC, events via DataChannel

### Function Calling

The AI can call these functions during conversation:

| Function | Description |
|----------|-------------|
| `save_customer_lead` | Save customer contact info to CRM |
| `create_quote` | Generate and save a price quote |
| `disconnect_call` | End the conversation gracefully |

### Audio Processing

- **Input**: Device microphone → WebRTC LocalTrack → OpenAI
- **Output**: OpenAI → WebRTC RemoteTrack → Device speaker
- **Format**: PCM 16-bit mono @ 24kHz

## Configuration

### Voice Options

Available voices: `alloy`, `ash`, `ballad`, `coral`, `echo`, `sage`, `shimmer`, `verse`, `marin`, `cedar`

Recommended: `marin` or `cedar`

### System Instructions

Customize the AI's behavior in `VoiceAgentService.getSystemInstructions()`.

## Troubleshooting

### No audio output

1. Check device volume
2. Verify RECORD_AUDIO permission granted
3. Check WebRTC connection state in logs

### Connection failures

1. Verify network connectivity
2. Check API token validity
3. Review logs for specific errors

### High latency

1. Ensure stable WiFi connection
2. Check if VAD is properly configured
3. Verify WebRTC ICE connection succeeded

## Security Considerations

- **API Keys**: Never stored on device; ephemeral tokens only
- **Network**: All connections use HTTPS/WSS
- **Permissions**: Minimal required permissions

## Testing

### Unit Tests

```bash
./gradlew test
```

### On-Device Testing

1. Install on Sanbot robot
2. Launch app
3. Tap "Start Conversation"
4. Speak to the AI
5. Tap "End Conversation" or say goodbye

## Production Deployment

1. Generate release keystore
2. Configure signing in `build.gradle`
3. Build release APK: `./gradlew assembleRelease`
4. Test thoroughly on target devices
5. Deploy via ADB or MDM

## License

Proprietary - TripAndEvent

## Support

For technical support, contact the development team.
