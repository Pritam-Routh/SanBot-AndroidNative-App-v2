# AGENT.md - HeyGen LiveAvatar Integration Changelog

This document tracks all changes made during the HeyGen LiveAvatar + OpenAI Realtime API integration.

## Project Overview

Integration of HeyGen's LiveAvatar API to provide a real-time talking avatar that lip-syncs to AI responses, working alongside the existing Sanbot robot gestures.

### Key Design Decisions

| Decision | Choice |
|----------|--------|
| Avatar + Robot | Both together (synchronized) |
| Fallback Strategy | Audio-only if HeyGen fails |
| Architecture | Text deltas routed via backend |
| Target Latency | 300-500ms |

---

## Architecture

```
┌─────────────────┐       ┌──────────────────────────────────────────┐
│  Android App    │       │          Backend Server (Node.js)        │
├─────────────────┤       ├──────────────────────────────────────────┤
│                 │       │                                          │
│  User Audio ────┼───────┼──► WebRTC ──► OpenAI Realtime API       │
│                 │       │        ◄──────────────────────────────   │
│  ◄──────────────┼───────┼── Audio Track (AI Voice)                 │
│                 │       │                                          │
│  Text Deltas ───┼───────┼──► HeyGen Manager ──► HeyGen API         │
│                 │       │                       (streaming.task)   │
│  ◄──────────────┼───────┼── LiveKit Video Stream                   │
│  SurfaceView    │       │                                          │
│  (Avatar Video) │       │                                          │
│                 │       │                                          │
│  Robot Gestures │       │                                          │
│  (Synchronized) │       │                                          │
└─────────────────┘       └──────────────────────────────────────────┘
```

---

## Phase 1: Backend HeyGen Integration

### Date: 2026-01-24
### Status: COMPLETED

### New Files Created

#### `OpenAI-realtime-backend-V1/heygen/HeyGenManager.js`
- Purpose: HeyGen API client and session lifecycle management
- Key methods:
  - `createToken()` - Get HeyGen streaming token
  - `createSession(avatarId)` - Create new streaming session
  - `startSession(sessionId)` - Start session, get LiveKit info
  - `sendText(sessionId, text)` - Send text for avatar speech (task_type: repeat)
  - `interrupt(sessionId)` - Interrupt current avatar speech
  - `stopSession(sessionId)` - Clean up session

#### `OpenAI-realtime-backend-V1/heygen/SessionStore.js`
- Purpose: In-memory session state storage
- Maps clientId to HeyGen session data

### Modified Files

#### `OpenAI-realtime-backend-V1/server.js`
- Added 4 new endpoints:
  - `POST /heygen/session` - Create HeyGen session
  - `POST /heygen/stream` - Stream text to avatar
  - `POST /heygen/interrupt` - Interrupt avatar speech
  - `POST /heygen/stop` - Stop session

#### `OpenAI-realtime-backend-V1/package.json`
- Added dependencies: `@heygen/streaming-avatar`, `ws`

#### `OpenAI-realtime-backend-V1/.env.example`
- Added: `HEYGEN_API_KEY`, `HEYGEN_DEFAULT_AVATAR_ID`

---

## Phase 2: Android HeyGen Integration

### Date: 2026-01-24
### Status: COMPLETED

### New Files Created

#### `app/src/main/java/com/tripandevent/sanbotvoice/heygen/HeyGenApiClient.java`
- OkHttp client for backend HeyGen endpoints
- Handles session creation, text streaming, interruption, and stop
- Async callbacks for all operations

#### `app/src/main/java/com/tripandevent/sanbotvoice/heygen/HeyGenSessionManager.java`
- Manages HeyGen session lifecycle and state machine
- States: IDLE -> CREATING -> CONNECTING -> ACTIVE -> STOPPING -> IDLE/ERROR
- Coordinates API client, text buffer, and video manager

#### `app/src/main/java/com/tripandevent/sanbotvoice/heygen/HeyGenVideoManager.java`
- LiveKit room connection and video track handling
- Uses `io.livekit.android.renderer.SurfaceViewRenderer` for video display
- Handles remote video track subscription

#### `app/src/main/java/com/tripandevent/sanbotvoice/heygen/TextDeltaBuffer.java`
- Intelligent text batching for smooth avatar speech
- Flush conditions: sentence boundary, min 3 words, max 300ms delay, max 500 chars
- Thread-safe with Handler-based delayed flush

#### `app/src/main/java/com/tripandevent/sanbotvoice/heygen/HeyGenConfig.java`
- Centralized configuration from BuildConfig
- Includes robot-to-avatar emotion mapping

### Modified Files

#### `app/build.gradle`
- Added: `io.livekit:livekit-android:2.5.0`
- Added BuildConfig fields:
  - `ENABLE_HEYGEN_AVATAR` (boolean)
  - `HEYGEN_AVATAR_ID` (String)
  - `TEXT_DELTA_BATCH_DELAY_MS` (int)
  - `TEXT_DELTA_MIN_WORDS` (int)

#### `settings.gradle`
- Added JitPack repository (`maven { url 'https://jitpack.io' }`)
- Required for LiveKit SDK transitive dependency (audioswitch)

#### `app/src/main/java/com/tripandevent/sanbotvoice/core/VoiceAgentService.java`
- Added HeyGenSessionManager member
- Initialize HeyGen in `initializeHeyGen()` on service creation
- Start HeyGen session in `onConnected()` after OpenAI connects
- Forward text deltas in `handleTranscriptDelta()` to HeyGen buffer
- Flush buffer in `handleResponseDone()` when AI response completes
- Interrupt avatar in `onSpeechStarted()` and AudioProcessor callback for barge-in
- Stop HeyGen session in `stopConversation()`
- Added `VoiceAgentListener.onAvatarVideoReady()` and `onAvatarError()` callbacks
- Added `getHeyGenSessionManager()` and `isHeyGenEnabled()` getters

---

## Phase 3: Android UI Integration

### Date: 2026-01-24
### Status: COMPLETED

### New Files Created

#### `app/src/main/java/com/tripandevent/sanbotvoice/ui/AvatarViewController.java`
- Manages avatar UI state and animations
- Methods: `showLoading()`, `showVideo()`, `showError()`, `hide()`
- Binds HeyGenVideoManager to SurfaceViewRenderer
- Smooth fade animations for show/hide

#### `app/src/main/res/drawable/avatar_background.xml`
- Rounded rectangle background for avatar container
- Dark theme with subtle border

#### `app/src/main/res/drawable/ic_avatar_fallback.xml`
- Vector icon for avatar fallback state

### Modified Files

#### `app/src/main/res/layout/activity_main.xml`
- Added `FrameLayout` avatar container with:
  - `SurfaceViewRenderer` for video
  - `ProgressBar` for loading state
  - `LinearLayout` error overlay for fallback
- Positioned between subtitle and status text
- Height: 35% of screen

#### `app/src/main/java/com/tripandevent/sanbotvoice/MainActivity.java`
- Added avatar UI component references
- Added `AvatarViewController` member
- Initialize avatar views in `initializeAvatarViews()` if HeyGen enabled
- Implement `onAvatarVideoReady()` - bind video manager and show video
- Implement `onAvatarError()` - show error state
- Added `updateAvatarForState()` helper to manage avatar visibility
- Release avatar resources in `onDestroy()`

---

## Phase 4: Testing & Next Steps

### Date: 2026-01-24
### Status: READY FOR TESTING

### How to Test

#### Backend Testing
```bash
cd OpenAI-realtime-backend-V1
npm install
npm run dev

# Test HeyGen endpoints (requires valid API key in .env)
curl -X POST http://localhost:3051/heygen/session \
  -H "Content-Type: application/json" \
  -d '{"clientId": "test-123", "avatarId": null}'
```

#### Android Testing
1. Set `ENABLE_HEYGEN_AVATAR=true` in `gradle.properties`
2. Configure `HEYGEN_AVATAR_ID` (optional)
3. Run: `./gradlew assembleDebug`
4. Install on device and test conversation
5. Avatar should appear when conversation starts
6. Avatar should speak in sync with AI audio
7. Avatar should stop when user speaks (barge-in)

### Pending Tasks
- Unit tests for TextDeltaBuffer
- Integration tests with real HeyGen API
- Performance profiling on Sanbot device
- Edge case handling (network failures, session limits)

---

## Configuration

### Backend (.env)
```env
HEYGEN_API_KEY=<your-heygen-api-key>
HEYGEN_DEFAULT_AVATAR_ID=<default-avatar-id>
OPENAI_API_KEY=<existing>
PORT=3051
```

### Android (gradle.properties)
```properties
ENABLE_HEYGEN_AVATAR=true
HEYGEN_AVATAR_ID=<avatar-id>
TEXT_DELTA_BATCH_DELAY_MS=300
TEXT_DELTA_MIN_WORDS=3
```

---

## Robot + Avatar Motion Mapping

| Robot Function | Avatar Animation |
|----------------|------------------|
| `robot_greet` | Wave gesture |
| `robot_nod` | Nod animation |
| `robot_think` | Thinking expression |
| `robot_happy` | Happy expression |
| `robot_curious` | Raised eyebrow |

---

## Troubleshooting

### Avatar not appearing
1. Check `ENABLE_HEYGEN_AVATAR=true` in gradle.properties
2. Verify HeyGen API key is valid
3. Check backend logs for session creation errors

### Audio-only fallback triggered
- Check backend HeyGen endpoint connectivity
- Verify LiveKit connection (network issues)
- Check HeyGen session limit (3 concurrent for trial)

### Avatar/audio sync issues
- Ensure using `task_type: "repeat"` (not "talk")
- Adjust `TEXT_DELTA_BATCH_DELAY_MS` if needed

### Gradle dependency resolution error
If you see: `Failed to resolve: com.github.davidliu:audioswitch:...`
- This is a transitive dependency of LiveKit SDK hosted on JitPack
- Solution: Ensure `settings.gradle` includes JitPack:
```gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // Required for LiveKit
    }
}
```
- Then sync Gradle in Android Studio

---

## Key Sources

- [HeyGen Streaming API with LiveKit v2](https://docs.heygen.com/docs/streaming-api-integration-with-livekit-v2)
- [LiveKit Android SDK](https://docs.livekit.io/reference/client-sdk-android/index.html)
- [HeyGen Streaming Avatar SDK Reference](https://docs.heygen.com/docs/streaming-avatar-sdk-reference)
