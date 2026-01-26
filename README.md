# SanBot Voice Agent

An AI-powered voice conversation Android app for **Sanbot robots** that integrates OpenAI's Realtime API over WebRTC for near-zero latency sales conversations, with optional avatar video (LiveAvatar / HeyGen) and robot gesture control.

## Overview

The system consists of two parts:

1. **Android App** — Runs on Sanbot robots, handles WebRTC audio, avatar display, and robot gestures
2. **Backend** — Node.js HTTP server + Python LiveKit agent for token management, avatar sessions, and AI orchestration

```
┌──────────────────────────────────────────────────────────────┐
│                    ANDROID APP (Sanbot)                       │
│                                                              │
│  MainActivity ──► VoiceAgentService ──► WebRTCManager        │
│       │                  │                   │               │
│       │                  ▼                   ▼               │
│       │          FunctionExecutor    OpenAI Realtime API      │
│       │           │          │       (WebRTC + DataChannel)   │
│       │           ▼          ▼                               │
│       │       CRM API   Robot Gestures                       │
│       │                                                      │
│       └──► LiveAvatar / HeyGen Avatar (video overlay)        │
└──────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────────┐
│                    BACKEND SERVERS                            │
│                                                              │
│  Node.js (Express)          Python (LiveKit Agent)           │
│  ├── /token                 ├── OpenAI Realtime plugin       │
│  ├── /liveavatar/*          ├── CRM function tools           │
│  ├── /heygen/*              └── Robot action tools           │
│  └── /orchestrated/*                                         │
└──────────────────────────────────────────────────────────────┘
```

## Features

- **Real-time voice conversations** via WebRTC with OpenAI Realtime API
- **Avatar video** — LiveAvatar (audio-based, lowest latency) or HeyGen (text-based)
- **Sanbot robot integration** — gestures, emotions, and motion synced to conversation
- **AI function calling** — save customer leads, create quotes, control robot
- **Ephemeral token security** — API keys never stored on device
- **Multiple modes** — Standard, HeyGen Full, and Orchestrated (single LiveKit room)

## Supported Modes

| Mode | Description |
|------|-------------|
| **Standard** | OpenAI WebRTC + LiveAvatar video + Sanbot gestures |
| **HeyGen Full** | HeyGen handles voice + avatar (no OpenAI WebRTC) |
| **Orchestrated** | Single LiveKit room with OpenAI Agent + HeyGen BYOLI |

---

## Prerequisites

### Android App

- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34
- Sanbot robot running Android 7.0+ (Elf) or Android 12+ (S5)

### Backend

- Node.js 18+
- Python 3.11+
- Docker & Docker Compose (optional, for containerized deployment)
- API keys: OpenAI, LiveAvatar/HeyGen (optional), LiveKit (optional)

---

## Quick Start

### 1. Start the Backend

See [OpenAI-realtime-backend-V1/README.md](OpenAI-realtime-backend-V1/README.md) for detailed backend setup.

**With Docker (recommended):**

```bash
cd OpenAI-realtime-backend-V1
cp .env.example .env
# Edit .env with your API keys
docker compose up --build
```

**Without Docker:**

```bash
# Terminal 1 — Node.js server
cd OpenAI-realtime-backend-V1
cp .env.example .env
# Edit .env with your API keys
npm install
npm start                    # Runs on port 3051

# Terminal 2 — Python agent (only needed for Orchestrated mode)
cd OpenAI-realtime-backend-V1/agent
pip install -r requirements.txt
python agent.py start
```

### 2. Configure the Android App

Edit `gradle.properties` to point to your backend:

```properties
# Backend URL (use your machine's IP or a dev tunnel)
TRIPANDEVENT_BASE_URL=http://YOUR_SERVER_IP:3051

# Feature flags
ENABLE_LIVEAVATAR=true
ENABLE_OPENAI_WEBRTC=true
ENABLE_ORCHESTRATED_MODE=true
ENABLE_HEYGEN_AVATAR=false
```

### 3. Build and Run the App

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run it directly.

---

## Android App Configuration

### Feature Flags (`gradle.properties`)

| Flag | Default | Description |
|------|---------|-------------|
| `ENABLE_LIVEAVATAR` | `true` | Audio-based avatar (lowest latency) |
| `ENABLE_OPENAI_WEBRTC` | `true` | Use OpenAI Realtime via WebRTC |
| `ENABLE_ORCHESTRATED_MODE` | `true` | Single LiveKit room with OpenAI Agent |
| `ENABLE_HEYGEN_AVATAR` | `false` | HeyGen text-based avatar |
| `ENABLE_TRANSCRIPT_DISPLAY` | `true` | Show conversation transcripts on screen |
| `ENABLE_DEBUG_LOGGING` | `true` | Verbose logging |

### Voice Options

Available voices: `alloy`, `ash`, `ballad`, `coral`, `echo`, `sage`, `shimmer`, `verse`, `marin`, `cedar`

Set in `gradle.properties`:

```properties
OPENAI_VOICE=marin
```

---

## Project Structure

```
SanBot-AndroidNative-App-v2/
├── app/                                 # Android application
│   └── src/main/java/com/tripandevent/sanbotvoice/
│       ├── MainActivity.java            # Main UI
│       ├── SanbotVoiceApp.java          # Application lifecycle
│       ├── core/                        # VoiceAgentService, ConversationState
│       ├── webrtc/                      # WebRTC connection management
│       ├── openai/events/               # OpenAI Realtime event models
│       ├── api/                         # Backend HTTP client (Retrofit)
│       ├── functions/                   # AI function calling (CRM, disconnect)
│       ├── audio/                       # Audio processing, VAD, boosting
│       ├── heygen/                      # HeyGen avatar integration
│       ├── liveavatar/                  # LiveAvatar integration
│       ├── orchestration/              # LiveKit orchestrated mode
│       ├── sanbot/                      # Sanbot SDK (gestures, emotions)
│       ├── ui/                          # Avatar view, voice orb
│       ├── config/                      # AgentConfig builder
│       └── utils/                       # Constants, Logger
│
├── OpenAI-realtime-backend-V1/          # Backend servers
│   ├── server.js                        # Node.js Express server
│   ├── agent/                           # Python LiveKit agent
│   │   ├── agent.py                     # AI agent with function tools
│   │   ├── crm_functions.py             # CRM API integration
│   │   └── heygen_byoli.py             # HeyGen BYOLI support
│   ├── heygen/                          # HeyGen session management
│   ├── liveavatar/                      # LiveAvatar session management
│   ├── orchestration/                   # Orchestrated mode logic
│   ├── docker-compose.yml               # Docker services
│   └── .env.example                     # Environment template
│
├── audio-orb/                           # Audio visualization library
├── liveavatar-web-sdk-master/           # LiveAvatar SDK
└── server-sdk-kotlin-main/              # Sanbot SDK (Kotlin)
```

---

## How It Works

### Connection Flow

1. App requests an **ephemeral token** from the Node.js backend
2. App creates a **WebRTC PeerConnection** with OpenAI Realtime API
3. Audio streams bidirectionally over WebRTC; events flow over DataChannel
4. The AI agent responds with voice and can call **functions** (save leads, create quotes, control robot)
5. If avatar is enabled, audio/text is forwarded to LiveAvatar/HeyGen for lip-synced video

### AI Function Calling

| Function | Description |
|----------|-------------|
| `save_customer_lead` | Save customer contact info to CRM |
| `create_quote` | Generate a price quote for a travel package |
| `disconnect_call` | End the conversation gracefully |
| `robot_action` | Trigger Sanbot gestures (wave, nod, greet, etc.) |
| `find_packages` | Search travel packages by destination |

### Audio Format

- PCM 16-bit mono @ 24kHz (matches OpenAI Realtime spec)

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| No audio output | Check device volume, verify `RECORD_AUDIO` permission, check WebRTC state in logs |
| Connection fails | Verify network, check backend is running, confirm API token validity |
| High latency | Ensure stable WiFi, check VAD config, verify ICE connection |
| Avatar not showing | Confirm `ENABLE_LIVEAVATAR=true` in gradle.properties, check LiveAvatar API key |
| Backend unreachable | Verify `TRIPANDEVENT_BASE_URL` matches your server, check firewall/port |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Android | Java + Kotlin, Android SDK 34 |
| WebRTC | stream-webrtc-android 1.1.0 |
| Video Streaming | LiveKit Android SDK 2.5.0 |
| AI/LLM | OpenAI Realtime API (gpt-4o-realtime) |
| HTTP Client | Retrofit 2.9.0 + OkHttp 4.12.0 |
| Backend (HTTP) | Node.js 18+ / Express 4 |
| Backend (Agent) | Python 3.11+ / LiveKit Agents |
| Containerization | Docker + Docker Compose |

---

## License

Proprietary - TripAndEvent
