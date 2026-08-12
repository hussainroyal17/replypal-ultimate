# ReplyPal Ultimate

> **Say it better. Every time.**

ReplyPal Ultimate is a Kotlin and Jetpack Compose Android application that helps users draft natural replies while retaining deliberate control over their messaging. It supports a complete normal application mode and a user-enabled floating assistant mode within the **same APK**. The user must explicitly paste text or share/select a screenshot; ReplyPal does not monitor other apps, capture the screen continuously, use an AccessibilityService, inject text, or send messages.

| Area | Included implementation |
| --- | --- |
| Normal application | Home dashboard, local saved conversations, chat history, OCR review, reply generation, copy actions, settings, and privacy notice. |
| Floating assistant | Draggable `TYPE_APPLICATION_OVERLAY` bubble, remembered position, compact paste-and-suggest panel, persistent user-visible service notification, and an explicit off switch. |
| Local data | Room entities and DAOs for conversations, incoming/outgoing messages, generated replies, copied state, timestamps, notes, pins, and archive-ready fields. |
| Replies | Five styles—**Flirty**, **Friendly**, **Funny**, **Caring**, and **Confident**—with three alternatives per style. A local fallback works without a backend; a secure HTTPS backend is configurable. |
| Screenshot flow | User shares/selects an image → on-device OCR → editable review → user adds the text to chat → suggestions. |
| Languages | Backend prompt supports Auto, English, Hindi, Gujarati, and Hinglish. On-device screenshot OCR uses Latin and Devanagari ML Kit models; Gujarati-script screenshots should be reviewed and corrected in the provided OCR editor. |
| Branding | Original vector launcher/floating mark, adaptive icon foreground/background/monochrome resources, generated master app mark, and a full-logo asset. |

## Project layout

```text
replypal-ultimate/
├── app/                         # Android Studio module
│   ├── src/main/java/.../
│   │   ├── data/                # Room and DataStore persistence
│   │   ├── domain/              # Repository, AI provider, suggestion generator
│   │   ├── service/             # OCR and floating overlay service
│   │   └── MainActivity.kt      # Material 3 normal-mode interface
│   └── src/main/res/            # Theme, adaptive assets, privacy backup rules
├── backend/                     # Optional FastAPI secure AI proxy
├── branding/                    # Original PNG brand assets
├── .github/workflows/           # Android CI
└── README.md
```

## Run the Android app

Install **Android Studio**, then open this repository root. Set the Android SDK path in `local.properties` if Android Studio does not do so automatically:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Build and install the debug APK:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The checked build target is **Android API 35**, with a minimum supported SDK of **26** (Android 8.0). The debug build intentionally has no AI service URL. It uses the local fallback generator until an HTTPS backend is configured in **Settings → Configure secure AI backend**.

## Configure the secure AI backend

The Android client never contains an LLM-provider secret. The included backend is a deployable FastAPI reference that receives only the conversation context the user chooses to generate with, calls the model server-side, and returns a strictly structured reply payload.

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY="server-only-secret"
export OPENAI_BASE_URL="https://your-openai-compatible-endpoint/v1" # optional
export REPLYPAL_MODEL="gpt-5-mini" # optional
uvicorn app:app --host 0.0.0.0 --port 8000
```

For production, deploy this API behind HTTPS and add authentication, per-user rate limits, CORS restrictions for the Android client, request-size controls, observability that redacts message bodies, and a data-retention policy. In the app, use the HTTPS deployment URL, such as `https://api.example.com`—not the provider endpoint and never an API key.

### Backend API contract

`POST /v1/replies` accepts the contact name, user-provided recent history, latest incoming message, and preferences. It returns:

```json
{
  "suggestions": {
    "flirty": ["…", "…", "…"],
    "friendly": ["…", "…", "…"],
    "funny": ["…", "…", "…"],
    "caring": ["…", "…", "…"],
    "confident": ["…", "…", "…"]
  }
}
```

The strict JSON-schema request on the server is designed to keep the Android-side integration stable even if the underlying model is changed later.

## Floating Assistant workflow

1. Open **Settings** and enable **Floating Assistant**.
2. Android opens the system **Display over other apps** page. Grant it only if you want the floating bubble.
3. Return to ReplyPal and enable the switch again. A small draggable bubble appears with an ongoing notification.
4. In a messaging app, copy a message, tap the bubble, tap/paste into the compact panel, select a suggestion, and copy it.
5. Paste the selected reply yourself into the originating application. ReplyPal does not send or inject it.

The normal app remains completely usable if the overlay permission is denied or the floating mode is off. The overlay uses a documented Android overlay window; no AccessibilityService or screen-capture API is declared. For Android 14+, the service is declared as `specialUse`, includes the required subtype explanation, and should be disclosed in the Google Play Console when distributing a release. [1]

## Screenshot workflow and OCR

ReplyPal exposes an Android `ACTION_SEND` image intent and a normal image picker. The input image is processed locally with ML Kit Text Recognition; the application displays the extracted text before it becomes conversation context. ML Kit supports `InputImage.fromFilePath()` for user-selected content and exposes structured text blocks/lines for future chat-bubble segmentation improvements. [2]

The current first-release implementation uses OCR text in reading order and requires user review, which is the safe choice when the screenshot’s incoming/outgoing alignment is ambiguous. The user can correct names, timestamps, OCR mistakes, and direction before generating a reply. This deliberately avoids silently interpreting a private conversation.

## Privacy and data handling

| Data | Default handling | User control |
| --- | --- | --- |
| Conversations and suggestions | Stored locally in Room; excluded from Android backup/device-transfer rules. | Delete individual conversations; use app data clear for a full local reset. |
| Screenshots | Processed only after an explicit share or selection; the app does not persist a screenshot history. | Do not share an image; edit or discard OCR text before adding it. |
| AI request content | Only sent when the user explicitly generates replies and only when an HTTPS backend is configured. | Leave the backend blank to use the local fallback; clear local app data at any time. |
| Floating mode | Off by default; starts only after a user-enabled setting and overlay approval. | Turn the switch off to stop the service and remove the bubble. |

> **Release note:** Before public distribution, add a complete in-app privacy policy, fill out the Play Console Data safety form according to the actual deployed backend and telemetry configuration, and test all disclosures against the final build. Google Play restricts broad or autonomous AccessibilityService usage; ReplyPal intentionally does not use it. [3]

## Release build procedure

1. Assign a unique production `applicationId` and remove the debug suffix only from the release artifact.
2. Create a signing key and configure the `release` signing config outside source control.
3. Configure and deploy the HTTPS backend, including auth and rate limits.
4. Run physical-device tests for Android 8.0, Android 13, Android 14, and current Android versions. Verify overlay permission denial, notification denial, offline mode, OCR failure, backend failure, deletion, dark mode, and large text accessibility settings.
5. Build an Android App Bundle for Play distribution:

   ```bash
   ./gradlew bundleRelease
   ```

6. Complete Play Console declarations for foreground service type, data safety, and any actual data collection. Do not add AccessibilityService functionality merely to read or operate other apps.

## Validation performed

The project was compiled successfully with `./gradlew assembleDebug` using Android API 35 and Java 17. The resulting debug APK is available at `app/build/outputs/apk/debug/app-debug.apk`.

## References

[1] [Android Developers — Foreground service types are required](https://developer.android.com/about/versions/14/changes/fgs-types-required)

[2] [Google for Developers — Recognize text in images with ML Kit on Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

[3] [Google Play Console Help — Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)


## Conversation-aware update

ReplyPal now treats every saved conversation as an independent context boundary. Each Room `ConversationEntity` owns a unique numeric conversation ID, a short rolling context summary, inferred tone, flirting and humor levels, language/emoji indicators, important facts, and relationship stage. When a message is added, the app refreshes **only that conversation’s** profile; generating replies sends only the selected conversation ID, profile, recent messages, and latest incoming message to the configured backend. The backend prompt explicitly prohibits carrying context between conversations.

The chat interface now makes this model visible with an **“AI understands this conversation”** profile card, a compact context memory preview, explicit **“+ Add conversation message”** sender selection for the contact or the user, the primary **“What should I say next?”** action, and a **“Why this reply?”** explanation for each generated suggestion. The home list exposes each chat’s independent tone/flirting profile rather than presenting a generic reply generator.

The updated local fallback recognizes common check-in messages such as “how’s you” and produces distinct five-style replies shaped by the selected chat’s profile. For production-quality inference across more languages and longer histories, configure the included secure backend; it now receives `conversation_id` and `conversation_profile` and is instructed to use no other chat’s history.
