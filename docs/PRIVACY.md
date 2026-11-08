# Privacy and permissions

What Mura asks for, why, and what it does with it. Short version: it collects nothing,
because there is no server for anything to be collected on.

## What Mura collects

Nothing. Not "very little" — nothing. There is no server, no account, no analytics SDK, no
crash reporter, no advertising identifier and no Firebase. The developer receives no data of
any kind, and could not receive it even if we wanted to.

| Data | Status |
|---|---|
| Phone number | Never requested |
| Email address | Never requested |
| Display name | Stored on your device; shown only to people you add |
| Address book / contacts | Never read |
| Location | Only for a location message you deliberately send; never in the background |
| Message content | End-to-end encrypted; unreadable to anyone but the recipient |
| Who you talk to | Hidden by sealed sender, even from relays |
| Message sizes | Hidden by uniform padding |
| IP address | Hidden by Tor; never exposed to the other phone |
| Device identifiers | None collected; the Bluetooth identifier rotates every 15 minutes |
| Analytics / telemetry | None |
| Crash reports | None |
| Cloud backups | None; backups are encrypted files you place yourself |

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | The mesh itself. Declared with `neverForLocation` on Android 12+. |
| `ACCESS_FINE_LOCATION` / `COARSE` | Two uses only: Android 9–11 gate Bluetooth LE scanning behind location permission, and the optional "share my location" message you send deliberately. **No background location is requested.** |
| `RECORD_AUDIO` | Voice notes, voice calls, and call recording you start. |
| `CAMERA` | Scanning a contact's QR code, and taking a photo to send. |
| `POST_NOTIFICATIONS` | Message, call and mesh-service notifications. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keeps the Bluetooth mesh and Tor transport alive. There is no push server to do this instead. |
| `FOREGROUND_SERVICE_MICROPHONE` | Added only while a call is active, removed when it ends. |
| `FOREGROUND_SERVICE_LOCATION` | Added only on Android 11 and below, only where the OS requires it for BLE scanning. Not used on Android 12+. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Writing your encrypted backup file to the folder you chose. No network involved. |
| `USE_FULL_SCREEN_INTENT` | Showing an incoming call on the lock screen. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional, and only ever after you tap. Without it Android pauses the mesh and messages stop arriving. |
| `RECEIVE_BOOT_COMPLETED` | Restarting the mesh after a reboot. |
| `DETECT_SCREEN_CAPTURE` | Telling the other person when a screenshot is taken in a chat. |
| `INTERNET` | Used **only** through Tor. There is no clearnet path. |

Mura uses Android's **photo picker** and document picker rather than broad media permissions,
so it never gets access to your whole gallery.

## Things Mura does not do

- No contact discovery. It never uploads or hashes your address book.
- No clearnet connections. Nothing is sent before Tor connects — not even a DNS lookup.
- No link previews unless you turn them on, and then only through Tor.
- No read receipts if you turn them off, in either direction.
- No cloud. Translation and voice transcription use Android's own on-device engines.

## Data you control

| | |
|---|---|
| Disappearing messages | Per chat or as a default for new chats |
| View-once messages and voice notes | Gone after one view |
| Delete chat / clear messages | Immediate |
| Panic erase | Wipes database, files, settings and Keystore keys |
| Backups | Created only when you ask, encrypted with your password, stored where you choose |
| Blocking | Blocks messages and calls, and rotates your status key |
| Stranger filters | Block messages, calls, files and group invites from non-contacts |

## Children

Mura is not designed for or directed at children, and is rated 18+. It has no content
moderation, because it is end-to-end encrypted and serverless — there is no operator who can
see content in order to moderate it.

## Changes

Any change to this document will appear in the repository history, where you can see exactly
what changed and when. That is a property of being open source rather than a promise.

**Full policy:** https://www.dedseec.com/privacy-policy-and-disclaimer/
