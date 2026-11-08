# Publishing Mura on Google Play — checklist and ready answers

Mura 2.7.9 (versionCode 21) · package `com.mura.mesh` · targetSdk 36 · minSdk 28
Developer: DedSec Cyber Security Research Institute
Privacy policy: https://www.dedseec.com/privacy-policy-and-disclaimer/ (Play Console only — the app itself shows a disclaimer instead of a link)

> Policies change. Check each answer against the current Play Console wording before you submit.

## 1. Build the release bundle

1. **Create an upload key** (once; keep it backed up and secret):
   `keytool -genkeypair -v -keystore mura-upload.jks -alias mura -keyalg RSA -keysize 4096 -validity 10000`
2. **Create `keystore.properties`** next to `settings.gradle.kts`. It's already in `.gitignore`, so it won't be committed:
   ```
   storeFile=C:/Users/you/keys/mura-upload.jks
   storePassword=…
   keyAlias=mura
   keyPassword=…
   ```
3. **Build the bundle:** Android Studio › *Build › Generate Signed App Bundle*, or run `./gradlew bundleRelease`. The output is `app/build/outputs/bundle/release/app-release.aab`.
4. **Turn on Play App Signing** when you create the app. Google keeps the app-signing key; you keep the upload key.
5. **Test the release build on a real phone before uploading.** Release builds are shrunk with R8, so open every screen, send a message over Bluetooth and over Tor, and make a call. If anything crashes only in release, send the stack trace.
6. **Check 16 KB page-size support.** Play requires it for apps targeting Android 15+. Open the AAB in Android Studio's *APK Analyzer* and confirm that `libtor.so` shows no 16 KB alignment warning. Play Console also warns after upload. If there's a warning, update `tor-android` to its newest release.

## 2. Store listing (suggested text)

- **App name:** Mura — Private Mesh Messenger
- **Short description (≤80):** Encrypted chat over Bluetooth & Tor. No servers, no numbers, no tracking.
- **Full description:**
  Mura is a private messenger made by DedSec Cyber Security Research Institute, only for privacy lovers.
  • No phone number, no email, no account, no servers.
  • Works without internet: messages hop phone-to-phone over Bluetooth.
  • Works online through Tor onion services — your IP address stays hidden, and nobody can see who you talk to.
  • End-to-end encrypted with forward secrecy (Double Ratchet), sealed sender, encrypted storage.
  • Groups, voice calls, voice notes, photos, files, polls, status updates, disappearing messages, locked chats, panic erase.
  • On-device translation, themes and wallpapers.
  • Tor-only: Mura never uses the normal internet, with Snowflake and obfs4 bridges for censored networks.
  • Messages reach friends even when you're never online at the same time, carried sealed by mutual friends.
  • Privacy tools: optional calculator disguise for the app icon, traffic padding, removal of location and phone model from videos, incognito keyboard.
- **Category:** Communication · **Tags:** messaging, privacy, encryption.
- **Contact email and website:** your DedSec contact details.
- **Screenshots:** at least two phone screenshots (1080×1920 or larger), plus a 512×512 icon and a 1024×500 feature graphic.

## 3. App content declarations

| Item | Answer |
|---|---|
| Privacy policy | https://www.dedseec.com/privacy-policy-and-disclaimer/ |
| Ads | No ads |
| App access | All features are available without login. Note for reviewers: "No account needed. Open the app, create a name, allow Bluetooth. To test messaging, install on two devices and scan each other's QR code (Add › Scan their code)." |
| Content rating | Fill in the questionnaire: communication app; users can interact and share content (user-to-user messaging). |
| Target audience | 13+ (or 18+). Not designed for children. |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |

## 4. Data safety form

Google's guidance treats four kinds of data as **not "collected"**:
- data processed only on the device;
- data that is end-to-end encrypted so that only the sender and recipient can read it;
- data the user explicitly chooses to send somewhere they expect it to go;
- data processed ephemerally.

Mura has no server, so DedSec never receives anything.

| Question | Suggested answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No.** Messages, calls, files and statuses travel end-to-end encrypted directly between users' phones (Bluetooth or Tor). Nothing reaches the developer. |
| Is all user data encrypted in transit? | **Yes** |
| Do you provide a way for users to request that their data be deleted? | Data exists only on the user's phone. Settings › Privacy › **Panic erase** deletes everything, as does uninstalling. |
| Optional backup file | Created only when the user asks, encrypted on the phone with the user's password, and saved to a folder the user picks. Nothing is sent anywhere, so it's not collection. |

## 5. Permissions — Play Console declarations

| Permission / feature | Why Mura needs it (paste into the declaration) |
|---|---|
| **Foreground service: connectedDevice** | "Keeps the Bluetooth mesh running so messages from nearby users are delivered in real time, without any server." Demo video: the persistent "Mura mesh is running" notification while a message arrives with the app closed. |
| **Foreground service: location** (Android 9–11 only) | "Android 9–11 requires location for Bluetooth LE scanning. Mura never reads or stores the user's location for this." |
| **Foreground service: microphone** | "Voice calls continue while the screen is off or the user switches apps." |
| **Foreground service: dataSync** | "Writes the user's encrypted chat backup file to the folder they chose, when they enable automatic backups." |
| **USE_FULL_SCREEN_INTENT** | "Incoming voice calls (calling app)." |
| **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS** | "Mura has no server and cannot use Firebase Cloud Messaging, by design for privacy. Delivery depends on the app staying reachable over Bluetooth and Tor, so real-time messaging requires an exemption." This matches Play's acceptable use for chat apps that can't use FCM. |
| **Location** | Bluetooth scanning on Android 9–11, plus the optional "share my location" message (sent end-to-end encrypted). Background location is not requested. |
| **Camera** | Scanning contact QR codes, taking photos to send. |
| **Microphone** | Voice notes and calls. |
| **DETECT_SCREEN_CAPTURE** | Alerts the other person when a screenshot is taken in a chat. |

## 5b. The calculator disguise and Play policy

Play's Deceptive Behavior policy forbids apps that mislead *users*. The disguise is off by default, the user turns it on, and it hides the app from other people. Calculator-vault apps on Play work the same way. To stay clearly within policy:
- Mention the disguise in the store description, as the text above does.
- Keep Mura's real name in the Play listing, the app info screen and the privacy policy.

## 6. Export compliance (encryption)

Mura uses strong encryption (X25519, Ed25519, AES-256-GCM, Tor). On Google Play you confirm compliance with US export rules when distributing. Mass-market messaging encryption is generally covered by an exemption, but check whether a self-classification report applies to you, and check any rules in your own country. You may want to ask a lawyer; this isn't legal advice.

## 7. After launch

- Use staged rollouts, starting at 10%.
- Mura has no crash reporting, on purpose. Ask users to send you logs, or use Play Console's built-in crash reports (Android vitals). Those come from Google Play, not from Mura.
- Bump `versionCode` for every upload.
