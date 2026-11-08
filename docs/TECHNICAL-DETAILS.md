# Mura 2.7.9 — private mesh messenger, offline and online, no servers (Android 9–16)

End-to-end encrypted chats, groups (up to 200), status updates, photos, videos, PDFs and
documents, voice notes, live location, voice calls with recording — carried phone-to-phone over
**Bluetooth LE**, and now also **over the internet through Tor onion services**, with no server of
any kind. If someone is out of range and offline, nearby Mura phones carry the sealed message until
it arrives. No phone number, no email, no accounts, no servers.

**Tor-only:** Mura never uses the normal internet. Internet links to contacts, link previews and the
link previews all go through the built-in Tor, and nothing is sent before Tor
has connected (not even a DNS lookup). The only direct connection is Tor reaching the Tor network.
Publishing on Google Play: see **PLAY_STORE.md**.

## About

Mura is made by **DedSec Cyber Security Research Institute** — only for privacy lovers.

## Build

1. Install a current **Android Studio**. *File › Open* this folder and let Gradle sync
   (Gradle 8.14.3, AGP 8.13, Kotlin 2.2, Compose, Media3, WorkManager, Tink, ZXing).
2. Accept the **Android SDK Platform 36** install if asked.
3. Run on **at least two phones** (three to see relaying). Create an identity on each, allow the
   permissions, then *Add › Scan their code* in person.
4. Tests: `./gradlew test` (crypto vectors, groups, backup format, store-and-forward, resumable files,
   Double Ratchet, sender keys, replies and timers, and internet links through a local fake Tor proxy).
5. Gradle needs access to the Guardian Project Maven repo for Tor (already listed in `settings.gradle.kts`).

> **2.1 is not wire-compatible with 2.0** (new protocol for forward secrecy). Install 2.1 on every
> test phone; 2.0 and 2.1 phones simply don't link.

## 2.7.9 — Motion

Small visual touches across the app. They don't change what any screen does, and they are only drawn on screen: nothing is sent, stored or logged.

- **Seal & send**: a light shimmer crosses the message you just sent, then it settles with a soft bounce.
- **Hop trail**: while a message is waiting or being carried, dots hop beside its status. The ticks glow when it's delivered or read.
- **Cipher typing**: "typing" is followed by morphing cipher symbols, in the chat header and in the chat list.
- **Onion layers**: in Settings › Internet and on the Mesh screen, three rings close as Tor connects and turn green when it's online.
- **Sonar discovery**: the Mesh radar pings when it finds a phone, and blips light up as the sweep passes over them.
- **Voice orb**: a breathing orb behind the record button grows with your voice. A glow follows the playhead of a voice note.
- **Aurora call ring**: a flowing colour ring around the person you're calling grows with the voice level. The level is measured on the phone and only used to draw the ring.
- **Reaction burst**: a new reaction pops in with amber sparks.
- **Timer ring**: the border of a disappearing message drains like a clock until the message burns away.
- **Vault unlock**: after the app lock or the calculator code, the lock rings rotate open as the app appears.
- **Mesh splash**: at start, the nodes fly in and assemble into the Mura mark.

Built to stay smooth on every phone:
- Animation frames are drawn only and never recompose the screen. Paths and brushes are made once per size.
- One-shot effects last under 0.8 s. Looping ones run only while what they show is on screen.
- There is no blur and no bitmaps.
- Android's *Remove animations* setting turns them all off.
- Low-RAM phones skip the extra glow layers.

## 2.7.8 — Cipher seal for view-once messages

Everything from 2.7.7 is unchanged except:

- **Cipher seal.** When a view-once message is opened it leaves the chat with a new effect:
  - the bubble scrambles into encrypted symbols behind a glowing edge;
  - a lock stamps it shut (haptic tick);
  - it folds into a point of light that implodes with a pulse ring (haptic pulse).
  - Drawn live at 60 fps, no images.
- **Where it plays:**
  - for the receiver, after closing a view-once text;
  - when returning from viewing a photo or video (within 30 s);
  - for the **sender**, when the other person opens it.
  - Deleted and disappearing messages keep Ember burn.
- **Privacy:** the effect seals the "tap to open" card, never the content. The list of just-opened messages lives only in memory for 30 s.
- **Hardening:** decrypted thumbnails on the Media, links & docs screen are erased from memory when you leave it.

## 2.7.7 — backups without Google

Everything from 2.7.6 is unchanged except backups:

- **Google Drive removed**, along with every Google library (`play-services-auth`, `credentials`). Mura no longer touches Google Play services at all, and works on phones without it.
- **Backups are encrypted files in a folder you choose**: phone storage, SD card, USB drive or any cloud app's folder.
  - "Back up now" and automatic backups (daily … yearly) are written there; Mura keeps the newest 3.
  - "Save a copy…" saves a single backup anywhere.
- **Only Mura, with your password, can open them:**
  - PBKDF2-SHA256 (310,000 rounds, random salt) plus a fresh random key per file;
  - AES-256-GCM authenticated records, so a wrong password, an edited or a cut file is rejected;
  - nothing readable inside;
  - forward-secrecy keys are never included.
- **Restore:** on the welcome screen tap "Restore from backup file", pick the `.mura` file and enter the password.
- **No network:** backups don't use the internet at all.

## 2.7.6 — Media, links & docs

Everything from 2.7.5 is unchanged; only this was added:

- **Tap a contact's name at the top of a chat** to open their shared media, links and docs. In groups: Group info › Media, links & docs.
  - Header: photo, name, verified and encrypted badges, plus Message / Call / Safety code. Verify safety code is still in the chat's ⋮ menu.
  - Four counter tiles act as tabs: **Media · Docs · Links · Voice**.
  - **Media:** a rounded grid grouped by month; videos show their length; items not yet downloaded show a download badge. Tap to open in Mura's secure viewer.
  - **Docs:** cards with colour-coded type badges (PDF, DOC, XLS, PPT, ZIP…), size, date, and sent/received. PDFs open inside Mura; anything else asks before leaving Mura, as in chats.
  - **Links:** gathered from messages, with preview images and titles when available. Opening one asks first, because the browser doesn't use Tor.
  - **Voice:** notes play right there.
- **Privacy:** everything is decrypted only in memory for display, and nothing is copied out. View-once and deleted messages never appear.

## 2.7.5 — remove contacts, status screenshot permission

Everything from 2.7.4 is unchanged; only these were added:

- **Remove contact** (Contacts › ⋮ or long-press). Erases them and everything about them from this phone:
  - Erased: the chat, all messages, photos and files, call history and call recordings, their statuses, the encryption sessions, and your messages they haven't received yet.
  - Your status key is replaced, so they can't open your future statuses.
  - Shared groups are left alone.
  - If they write again, they appear as a new request.
- **Status screenshots** (Settings › Privacy): your choice travels, encrypted, inside each status you post.
  - Off (default): contacts' phones block screenshots and screen recording of your status.
  - On: they're allowed, even if their own Mura blocks screenshots elsewhere.
  - Statuses from older versions stay protected.

## 2.7.4 — stays alive in the background, and "Share to Mura"

Everything from 2.7.3 is unchanged; only these were added:

- **Comes back by itself after being killed.** Android's job scheduler now checks every 15 minutes, and again as soon as Wi-Fi or mobile data returns, even if Mura's process was killed. It restarts Mura's background service and syncs. If Android won't allow a background restart (battery optimisation still on), the job keeps Mura awake for 2 minutes to deliver and collect messages.
- **Warning on the Chats screen** whenever Android may pause Mura, with one-tap **Allow background** and **Auto-start** buttons.
- **Phone maker auto-start** (Settings › Always reachable) now opens the right screen directly on Xiaomi/Redmi/POCO, Oppo/Realme (ColorOS), OnePlus, Vivo/iQOO, Huawei/Honor, Samsung and Asus.
- **Force stop:** Android blocks every wake-up for a force-stopped app until it's opened again. No app can get around this, WhatsApp included.
- **"Share to Mura"** appears in Android's share sheet (file manager, gallery, browser…) for files, photos, videos and text, up to 10 items to up to 5 chats.
  - It opens behind the app lock and disguise.
  - Nothing is sent until you pick chats and tap Send.
  - Files are compressed and sealed exactly like in-chat attachments, with "Remove hidden details" applied.
  - Only content another app actually hands over is accepted: never raw file paths, never Mura's own storage.
  - Locked chats and requests aren't listed.
  - Hidden from the share sheet while the calculator disguise is on.

## 2.7.3 — background check

Everything from 2.7.2 is unchanged except one fix:

- **Nearby discovery could stop for up to 20 minutes.** Opening and closing Mura several times quickly restarted the Bluetooth scan more than 5 times in 30 s. Android silently blocks that, with no error, just no results. Scan restarts are now spaced at least 7 s apart.

## 2.7.2 — fixes from a full review

No features were changed or removed. Fixed:

- **Calculator disguise wouldn't open.** The code setup accepted any keyboard's digits, so with Hindi/Arabic numerals (१२३४, ١٢٣٤) the saved code could never match what the calculator types.
  - The code is now always stored as 0–9.
  - Codes already saved that way are recognised and fixed automatically the first time you type them.
  - The check runs in the background, so the calculator never freezes.
  - After 5 wrong codes, checks pause for 30 s while the calculator keeps working normally.
- **Backups (serious).** Every backup was encrypted with the same key and the same nonce sequence. Someone holding two of your backups, such as anyone with access to the Drive folder, could learn about their contents and forge data. Each backup now gets its own random key. Older backups still restore.
- **Crash attack.** A phone nearby or over Tor could make Mura resend the same large data again and again until memory ran out. Now only what was offered to that phone is sent, once per round.
- **Crash safety.** Any unexpected error caused by another phone's data now closes only that connection; it can never crash the app.
- **Group key flooding.** Strangers could fill the storage for groups you haven't joined yet and block keys for your real groups. Unknown groups now have their own small quota that clears after 3 days.
- **Low-order keys** are refused explicitly when opening sealed messages.
- **"Open with another app"** failed because leaving Mura for the chooser wiped the file link. Links from the last minute now survive; older ones are still wiped when you leave.
- **Backup temp files** are now always deleted, even if a backup fails.

## 2.7.1 — live online, typing and recording status

Everything from 2.7.0 is unchanged; only this was added:

- **Live status in the chat list and the chat header.** The chat list shows "typing…" or "recording audio…" in place of the last message, plus a green dot when a contact is online. The chat header shows "online", "typing…" or "recording audio…".
- **On/off switch:** Settings › Privacy › **Online, typing & recording status**. It's on by default and works both ways: turn it off and you neither share nor see it.
- **How it stays private:**
  - It's sent only to contacts you've added.
  - It travels only over a live, verified link (Bluetooth or Tor), sealed with the same encryption as call signals.
  - It's never stored, queued or carried by other phones.
  - "Online" means Mura is open on the screen; it switches off as soon as you leave the app.
  - Locked chats never show it in the chat list.
  - 2.7.0 phones simply ignore it.
- **Faster with status on:** opening a chat connects to that contact through Tor right away, and the link stays open while you're both in the app, so messages and typing are near-instant.

## 2.7.0 — contacts, one-time voice notes, private call recordings

Everything from 2.6.3 is unchanged; only these were added:

- **Contacts list.** A contacts button on the Chats screen lists everyone you've added, A–Z, with search, tap to chat and one-tap calling. People who added you but whom you haven't added back appear under "Requests". Stored only on this phone, encrypted.
- **One-time voice notes.** Turn on ① (view once) next to the message box, then hold the mic. The other person can play it once; it's erased when they close it. Screenshots and screen recording are blocked while it plays, and it can't be forwarded or saved.
- **Delete photos, videos, voice notes and documents.** Long-press now works on media, so you get Delete for me / Delete for everyone. The photo and video viewer also has a delete button. Delete for everyone erases the encrypted file on both phones.
- **Call recordings stay in call history only.** They are never put in or sent to any chat. While recording, the audio exists only in memory and is never written as a readable file, not even temporarily. When you stop, it's sealed with AES-256-GCM under its own random key, which is itself stored encrypted. Recordings live in Mura's private storage, so no gallery, file manager or other app can see them. Play, pause or delete them in **Calls**. The other person is still told when you record.
- **About us** links to **dedseec.com**. It opens in your phone's browser; Mura itself never connects to it.

## 2.6.3 — see exactly why a message is waiting

- **Tor tells you the real reason.** Tor now reports precise onion errors: their address isn't published (their phone is offline on Tor), their phone didn't answer, the path broke, or the connection timed out. Tap the "Tor: …" line in a chat to see this phone's status, whether your address is reachable, the last problem, and a **Try now** button.
- **Settings › Internet** shows whether *your own* address is reachable through Tor, plus a **Test my address** button. "Online · ready" alone only meant connected to Tor.
- **Version mismatch is explained.** If the other phone runs a different Mura version, the chat says so instead of retrying silently.
- Onion connections wait up to 150 s, longer than Tor's own timeout, so slow Snowflake connections aren't cut off early.
- Fixed headings and contact names drawn in black on dark themes.
- The phone-made onion address was verified against real Tor (0.4.9): identical.

## 2.6.2 security review

A full pass over every link, intent and stored secret. Fixed:

- **Link impersonation (serious).** A phone could claim anyone's public key in its HELLO and be treated as that person: it could pull sealed messages queued for them (it still couldn't read them) and make your phone tombstone and drop them, so they'd never arrive. Now every link, Bluetooth or Tor, ends with a challenge–response: each side proves it holds the private key (X25519 → HKDF → HMAC over both keys and fresh 16-byte nonces, compared in constant time). Nothing except HELLO/PROOF is accepted before that, and all-zero (low-order) keys are refused. A unit test checks that an impostor gets nothing and the real owner still does.
- **Other apps could answer your calls or open chats.** Notification intents now carry a per-install secret. Intents from anywhere else are ignored, and "Answer" only answers the call that is actually ringing from that person. The main activity is no longer exported (the launcher aliases still are).
- **Add-by-ID trusted an unverified HELLO.** It now waits for the proof check, and a Tor link counts as "connected" only after verification.
- **Connection flooding over Tor.** At most 32 open Tor connections, and any that haven't proved their key within 30 s are dropped.
- **Contact-request spam.** At most 100 pending requests from strangers; new ones beyond that are dropped.
- **Disguise code.** Stored as PBKDF2-SHA256 (60,000 rounds, salted) instead of one SHA-256, and compared in constant time. Older codes upgrade automatically the first time they're entered.
- **Camera temp file.** The camera's unencrypted photo is deleted even if you cancel or encoding fails.

## 2.6.1 fixes and Mura ID

- **Messages over Tor weren't sent**: Tor reports its local SOCKS port as -1 until its control thread is ready, and Mura read it too early — every connection attempt then failed silently. The port is now read again when Tor is fully connected and before every attempt.
- **Crash when switching Snowflake / bridges / internet on-off** (the app closed with no error): native Tor can't be stopped and started again inside one app process. Mura now never restarts Tor — bridges are applied live through Tor's control port (SETCONF), and "off" / no network just pauses it (DisableNetwork).
- **See why a message isn't going out**: each chat shows the live Tor status for that contact ("Connecting through Tor…", "Their phone isn't reachable on Tor right now · retrying in 20 s", "Connected"), plus ⋮ › Reconnect over Tor now. Settings › Internet shows whether *your own* address is reachable (self-test).
- **Faster first contact**: retries every 20 s–1 min for the first 15 minutes online (addresses take a minute or two to publish), then up to 10 min.
- **Mura ID — add friends from anywhere**: your Mura ID is your Tor onion address (shown in groups of 4). Copy or share it any way you like; your friend types or pastes it in Add contact › "Add by Mura ID". Tor connects to whoever holds that address (it can't be faked), the phones introduce themselves, and you get a contact request on the other side. No server, no search directory, no phone number. Typos are caught by the address checksum. "Reset" gives you a new ID (the old one stops working; contacts get the new one automatically). Pasting a full invite code (`mura:3:…`) works too.

## What's new in 2.6

| Feature | Details |
|---|---|
| **Anyone carries, online too** | Over the internet, sealed messages now hop phone-to-phone across everyone's Tor connections (contacts of contacts and beyond), like Bluetooth relaying — not only through mutual friends. Carriers can't read them or see the sender. Settings › Internet › Carry anyone's messages online. Over Bluetooth any nearby Mura phone already carries (Mesh › Relay for others). Note: without a server nobody can *find* strangers online, so internet carrying travels along people who are connected to each other |
| **Archive and delete chats** | Long-press a chat: Archive / Unarchive (an "Archived" row at the top), or Delete chat (erases its messages and files from this phone and removes it from the list until they write again) |
| **Adaptive call quality** | HD voice (16 kHz) on good links, Standard (8 kHz) or Saver (100 ms packets) on weak Bluetooth or busy Tor; each side measures loss and lateness every 3 s and asks the other to step down fast / up slowly. The call screen shows the current quality and link |
| **Call history** | New Calls tab: every call with direction, missed calls in red, duration, date and time, and a call-back button |
| **Dates in chats** | "Today", "Yesterday", weekday or full date between days; every bubble shows its time |
| **Nothing leaves the app unwarned** | All media stays sealed (encrypted) in Mura's private storage and is decrypted in memory only to show it — never in the gallery, Downloads or any shared folder. Saving a readable copy, opening a file in another app, opening a link or a map (outside Tor) now always asks first, explaining what can be exposed. Leftover camera temp files are wiped at start |
| **Disclaimer** | About shows a serverless / no-liability disclaimer instead of a link |

## 2.5.1 fixes

- **Your onion address now exists before Tor ever connects** (made on the phone, Tor v3 format, SHA3 checksum) — so it's always in your QR code. Before, a QR shown while Tor was still connecting had no internet address, and two phones that scanned each other that way could never reach each other online. **Re-scan each other's codes once after updating.**
- **One-sided adds work over the internet:** if only one phone scanned the other, the scanned phone now gets a contact request ("Lena added you by scanning your code") and can add them back.
- **Mura waits for Tor to be fully connected (100%)** before dialing contacts or allowing web requests, and Settings › Internet shows live progress ("Connecting to Tor via Snowflake… 45% · Loading relay information"), with a hint if it's stuck.

## What's new in 2.5 — delivery without being online together, disguise, padding, bridges

| Feature | How it works |
|---|---|
| **Friend mailboxes** | If a message can't reach someone directly for 90 s, your phone offers it (sealed) to your other contacts over Tor. Only a friend who **also has that person as a contact** accepts it, holds it, and hands it over when that person comes online — so you and your contact never need to be online at the same time, and there's still no server. The friend can't read it (end-to-end encrypted, sealed sender), but can tell you wrote to that person. Turn off in Settings › Internet. Tip: an old phone at home running Mura makes a great always-on mailbox for your circle |
| **Calculator disguise** | Settings › Privacy › Disguise app icon: Mura's icon becomes a working Calculator; type your secret code and press = to open Mura. Recents show "Calculator", notifications use a neutral icon and "New message" text. (Android 10+ doesn't let any app remove its icon completely, so a disguise is the strongest option.) |
| **Traffic padding** | Every message is padded to a fixed size step (256 B … 128 KB) before encryption — a "yes" and a paragraph look identical, on Bluetooth and Tor. Tor links send every record padded to 512-byte steps plus random cover traffic every 10–40 s. Backward-compatible for messages; Tor links need 2.5 on both sides |
| **Hidden details removed** | Videos and photos/videos sent as files: GPS location, dates, phone make/model, XMP/IPTC removed losslessly (JPEG keeps its rotation; MP4 boxes are blanked in place). Settings › Privacy: Ask me / Always / Never. Photos sent as photos and voice notes are always clean |
| **Incognito keyboard** | Every text field asks the keyboard not to learn what you type (IME_FLAG_NO_PERSONALIZED_LEARNING) |
| **Tor bridges** | Settings › Internet › Bridges: **Snowflake** built in (looks like a video call), or paste obfs4 / webtunnel / snowflake lines from bridges.torproject.org or @GetBridgesBot. Transports run inside the app (IPtProxy: Lyrebird + Snowflake) |

## What's new in 2.4 — Tor-only, smoother, Play-ready

| Change | Details |
|---|---|
| **Tor-only networking** | New `TorNet`: a small HTTPS client that only works through Tor's SOCKS port, with the hostname resolved inside Tor. Link previews and Drive backup use it and wait for Tor; Android's HttpURLConnection is not used (it resolves DNS locally even through a proxy). ML Kit removed (it talked to Google directly). `usesCleartextTraffic=false`, system CAs only |
| **Circuit isolation and rotation** | Each contact gets its own Tor circuit (SOCKS username isolation); link previews and backup get separate ones. New circuits every 10 min / hour / on demand (Tor's NEWNYM, like Tor Browser's "New circuit") |
| **Faster delivery after being offline** | When your phone connects to Tor it knocks on every contact once (random 0–20 s delays) to collect waiting messages; senders retry at most every 10 minutes |
| **Smooth navigation** | Chats · Status · Mesh · Me in one swipeable pager with a sliding tab pill; screens slide in/out WhatsApp-style; back from a tab returns to Chats |
| **Play Store ready** | Release signing via `keystore.properties`, R8 rules for Tor, ABI-split bundle, no background-location permission, network security config, PLAY_STORE.md with Data safety, permission declarations and listing text |

## What's new in 2.3 — online without a server, translation, themes, status replies

| Feature | How it works |
|---|---|
| **Internet links (Tor)** | Every phone runs Tor inside the app and becomes its own **onion service**. Your onion address is shared only with contacts — inside the QR code you scan in person (`mura:3:…`) and inside your end-to-end encrypted profile. When you have something for a contact who's online, your phone connects to their onion address through Tor and runs the **same mesh protocol** as Bluetooth, carrying the same sealed, ratcheted bundles |
| **Bluetooth unchanged** | Bluetooth works exactly as before, side by side. Whichever link is up delivers first; duplicates are dropped. Offline? Messages still hop over Bluetooth |
| **Calls over the internet** | Tap call on a contact who isn't nearby: Mura opens a Tor link (both must be online) and starts the usual end-to-end encrypted call with fresh keys. Expect ~0.5–1 s of delay over Tor |
| **Who can connect** | Only contacts. A connection's first frame must be a hello from a contact's key, or it's dropped. Over the internet your phone never relays strangers' mail and never announces relaying |
| **Network modes** | Off · Wi-Fi only · Wi-Fi + mobile data. Status shows in Settings, on the Mesh screen, in the chat list and in each chat ("Online · via Tor") |
| **On-device translation** | Long-press › Translate, or ⋮ › Auto-translate per chat. "Translated from Spanish · Show original". Translate your draft into their language before sending (with undo). Uses only **Android's built-in on-device translator** (Android 12+, on phones that ship one). Translations are cached encrypted, never sent |
| **Themes** | System · Dark · Light · Black (true-black OLED) × five accents (Amber, Mint, Sky, Rose, Violet). Status-bar icons follow the theme |
| **Wallpapers** | Plain, Dots, Contours, Aurora (all drawn in the theme's colours) or your own photo with a dim slider; one for all chats and an optional one per chat (⋮ › Wallpaper). Photos are shrunk, stripped of metadata and stored encrypted |
| **Status replies** | Reply privately to a contact's status, or tap a quick reaction (❤️ 😂 😮 😢 👏 🔥). The reply lands in your 1:1 chat with a quote of the status; tap the quote to reopen it while it's still up. The story pauses while you type |

### Internet privacy, honestly

- **No server**: there is nothing to run, rent or trust. Tor relays (run by volunteers) only see
  encrypted Tor cells; onion-to-onion traffic never leaves the Tor network and neither phone's IP
  address is revealed to the other or to the relays.
- **Two layers of encryption**: Mura's end-to-end encryption (Double Ratchet, sealed sender) inside
  Tor's own end-to-end encryption between the two onion services.
- **What contacts learn**: that you're online right now (they can connect to you). Strangers can't —
  they don't know your onion address, and if they did, they're dropped at hello.
- **What your network sees**: that you use Tor, not who you talk to. In countries that block Tor you'd
  need bridges (not built in yet).
- **Both phones must be online at the same time** for internet delivery. Until then the message waits
  on your phone (and still travels by Bluetooth). Your phone re-checks every 20 s and retries an
  unreachable contact with back-off (30 s → 30 min).
- **Battery and data**: Tor keeps a few connections open; expect a few % of battery a day and a few MB
  of data. Choose Wi-Fi only or Off in Settings › Internet to save both. Some phone brands kill
  background apps — see Settings › Always reachable.
- **Libraries**: Tor from the Guardian Project's `tor-android` (0.4.9.x) and `jtorctl`, from their
  Maven repo (`https://raw.githubusercontent.com/guardianproject/gpmaven/master`). They add native
  Tor libraries for each CPU type (several MB per ABI); use App Bundles or ABI splits to keep
  downloads small.

## What's new in 2.2 — everyday chat

| Feature | How it works on a mesh |
|---|---|
| **Reactions** | Long-press a message: six quick emoji or any emoji. A reaction is ~40 bytes, so it travels instantly. Tap the chips to see who reacted |
| **Edit** | Your own text messages, for 1 hour (scheduled ones until they go out). Shows "edited". An edit that overtakes its message is applied when the message arrives |
| **Polls** | Up to 12 options, one or many answers; votes are visible to the chat (like WhatsApp). Latest vote per person wins, whatever order they arrive in |
| **Forward** | To up to 5 chats. Files are re-sealed with a fresh key per chat (the original key never leaves your phone). View-once can't be forwarded. Marked "Forwarded" |
| **Star** | Private bookmarks, only on this phone — *Settings › Starred messages* |
| **Pin** | Up to 3 pinned messages per chat, shown under the header; in groups only admins can pin |
| **@mentions** | Type "@" in a group to pick a member. A mention notifies them even if they muted the group |
| **Search** | All chats from the chat-list search, or inside a chat (menu › Search). Everything is encrypted in the database, so search decrypts in memory on the phone; locked chats are never searched |
| **Send later** | Long-press Send, pick a date and time. The message waits encrypted on your phone and leaves on time (Mura keeps running in the background) |
| **Shared checklists** | Everyone can add, tick, rename and remove items, even offline. Changes are small operations that merge the same way on every phone (a CRDT), so lists always agree once phones meet |
| **Voice transcripts** | "Transcribe" under a voice message — uses Android's **on-device** speech recognizer only (Android 13+), audio is decoded in memory, the text is stored encrypted and never sent |
| **Doodles** | Draw with 7 colours and 3 pens; sent as vector strokes (typically 0.5–5 KB), not as a picture |

2.2 talks to 2.1 phones for normal messages; reactions, edits, polls, lists, pins and doodles need 2.2 on both phones.

## What's new in 2.1

- **Forward secrecy everywhere** — Double Ratchet for chats, hash-ratcheted sender keys for groups,
  ephemeral key exchange for calls, daily status keys. Details below.
- **Replies like WhatsApp** — swipe a message right (or long-press › Reply) to quote it; tap a quote
  to jump to the original. Replies keep working even if the original was deleted.
- **Reply from the notification** — conversation-style notifications with *Reply* (type right in the
  notification) and *Mark as read*. Not shown for locked chats.
- **Ember burn** — deleted, disappearing and opened view-once messages burn away: a glowing line
  sweeps across the bubble, sparks rise, ash drifts down, then the gap closes.
- **Mura's own sounds** — "Sonar ripple" message tone and ringtone (`res/raw`, synthesised for Mura).
- **New privacy options** — disappearing messages (1 h / 24 h / 7 d / 30 d, per chat or default for new
  chats), locked chats (fingerprint/face, optional hidden "Locked chats" folder, no notification
  content), lock-after timeout, sensitive + auto-cleared clipboard, rotating Bluetooth id, hold-to-erase
  panic wipe, backups that never contain ratchet keys.

### Backups (no Google needed)

Settings › Chat backup: set a backup password, choose a folder, then "Back up now" or pick a schedule.
Restore on a new phone from the welcome screen: "Restore from backup file". Works on phones without Google Play services.

## Privacy design

| What | How |
|---|---|
| Messages, files, calls, statuses | End-to-end encrypted. Sealed-sender envelope (X25519 + HKDF + AES-256-GCM); relays see only an 8-byte destination address and size |
| **Forward secrecy — chats** | X3DH-style start with a weekly-rotated, Ed25519-signed prekey (shared in the encrypted profile), then the **Double Ratchet**: a new key per message, a new DH ratchet every time the conversation turns, used keys erased. Handles messages that arrive days late, twice, or out of order (skipped keys kept ≤9 days, ≤2000 per session). A replay or tampered message never changes the session |
| **Forward secrecy — groups** | Each member's **sender-key chain** (HMAC hash ratchet) inside the group-key layer; the chain state is handed to members over the 1:1 ratchet, replaced when membership changes and weekly. New members can't read earlier messages |
| **Forward secrecy — calls** | Ephemeral X25519 keys in the offer and the answer; the call key is derived and both private halves are erased before audio starts |
| Statuses | Status key rotated at least daily and after blocking someone |
| Groups (≤200) | One shared group key (encrypt once, not 200 times) + every message **signed** (Ed25519) so members can't impersonate each other. Removing someone switches to a fresh key they never receive |
| Bluetooth | No pairing or bonding, ever. Mura connects only to phones advertising the Mura service, and advertises a **random id that changes every 15 minutes** so it can't be used to track you |
| Disappearing messages | Per chat (Off / 1 h / 24 h / 7 d / 30 d), synced to the other side; counted from arrival; undelivered copies are pulled from the mesh too |
| Locked chats | Fingerprint/face/screen lock to open; optional hidden folder; notifications say only "New message" and offer no reply |
| Clipboard | Copied messages are marked sensitive (hidden from previews and keyboard clipboard history on Android 13+) and cleared after a minute |
| Panic erase | Hold 2.5 s: wipes database, files, settings and **deletes the Keystore keys**, so leftovers in flash are unreadable |
| Data on the phone | Every sensitive database field is encrypted with a data key that's wrapped by a **non-exportable Android Keystore key** (StrongBox/TEE hardware where available). Copying the app's storage, even with root, yields nothing readable |
| Files on the phone | Stored only as sealed 32 KB pieces with a per-file key. Received files are **never** written in readable form; photos, videos, voice notes and PDFs are decrypted in memory while you view them. "Open in another app" uses a decrypt-on-read link that expires in 10 minutes |
| Screen | Screenshots/recording blocked by default (toggle), app lock with fingerprint/face/PIN, notification previews: full / name only / nothing |
| Backups | Encrypted on the phone with your password (PBKDF2-SHA256, 310 000 rounds) and a fresh key per backup file, saved to a folder you choose. Wrong password or tampering is detected. Ratchet sessions and group chains are **removed from the backup copy** — sessions restart automatically after a restore |
| Strangers | Optional blocking of messages, calls, files and group invites from people you haven't added |
| Internet | Only through Tor onion services — no server, no IP addresses exchanged. Onion address shared only with contacts (QR in person + encrypted profile). Only contacts may connect; no stranger relaying over the internet. The onion key is stored encrypted in the Vault |
| Translation | On the phone only, with Android's built-in translator; no third-party translator. Translations cached encrypted |

## Compression (before encryption)

| Type | What Mura does | Typical result |
|---|---|---|
| Text | Deflate | 2–20× smaller for longer messages |
| Photos | Resize to 1600 px (HD: 2560) + WebP | 5–15× smaller than a camera JPEG |
| Videos | Re-encode H.264 480p / 720p / 1080p, low bitrate | often 10–30× smaller |
| Voice notes | Opus 16 kbit/s (Android 10+), AAC 24 kbit/s (Android 9) | ~2–3 KB per second |
| Call recordings | AAC 16 kHz mono 24 kbit/s | ~180 KB per minute |
| PDFs, documents, other files | Lossless deflate when it helps | Exact original bytes on arrival |

Photos and videos are **lossy**: the smaller version is what arrives, so original camera quality
can't be restored on the other phone. Documents are always bit-exact.

## Honest limits

- **Bluetooth speed**: roughly 10–60 KB/s per hop. Compressed photos arrive in seconds; a compressed
  1-minute video (~3–8 MB) takes a few minutes per hop.
- **Calls** only work while both phones are linked directly (about one room to one building).
- **Screenshot alerts** need Android 14+ on the phone taking the screenshot. Nothing stops a photo of
  the screen taken with another camera.
- **Forward secrecy, first message**: until a new contact's signed prekey arrives, the very first
  messages use their identity key (as when Signal has no prekey); the ratchet makes everything after
  the first reply forward-secret.
- **Long silences**: old prekeys are erased about 3 weeks after rotation. If a contact starts a brand-new
  session with a prekey that old, that first message can't be opened; Mura automatically re-keys with
  them, but that one message has to be sent again.
- **Big groups**: the first message after someone joins (or after a key change) also sends your chain
  key privately to each member — about 250 bytes per member.
- **Metadata**: who sends to whom is hidden from relays by the sealed sender, but the outer envelope
  is keyed to long-term keys, so it is not forward-secret for the *sender's identity* (content is).
- **Disappearing messages / burn** remove the message from the phones that follow the protocol; a
  modified app, a camera, or a screenshot on a phone without alerts can still keep it.
- **Voice transcripts** need Android 13+ with an on-device speech pack for the language; older phones don't show the button.
- **Send later** relies on the phone being on at that time; if it's off, the message goes out as soon as it's back.
- **Internet delivery** needs both phones online at once — or a friend mailbox (a mutual contact who is online at both times). Tor connections take
  5–30 s to set up and calls over Tor have noticeable delay.
- **Built-in translator availability** depends on the phone maker (Pixel 6+ has one); on other phones
  translation isn't available (by design: no third-party translator). Language detection needs Android 10+.
- **Bridges**: built-in Snowflake settings come from Tor Browser and may change over time; if Snowflake stops
  connecting, paste fresh bridge lines. IPtProxy adds roughly 15–25 MB per CPU type (an App Bundle downloads one).
- **Friend mailboxes** reveal to the mutual friend *that* you wrote to that person (never the content).
- **Backup files** are only as safe as your backup password: use a long one. Anyone who has the file and guesses the password can open it.
- **Update every phone to 2.6.2**: links now require proof of key (protocol 4), so 2.6.2 phones won't link with older versions.
- **Independent audit**: this review was done in-house with unit tests; no software can be promised bug-free. Before relying on Mura where lives or freedom are at stake, have it audited by an independent security firm.
- **Compose UI** in this package was reviewed but not compiled here (the build servers are blocked in
  this environment); the crypto core is unit-tested and the Android non-UI layer is compile-checked.
- **Group delivery ticks**: groups show "sent/carried", not per-member read receipts (by design: less metadata).
- **Keys in memory**: while Mura is running, its data key is in the app's memory, like every messenger's.
- **Phone makers** (Xiaomi, Oppo, Vivo, Huawei, some Samsung) may still stop background apps —
  allow auto-start / unrestricted battery (link in Settings).
- **Call recording** laws differ by country; Mura always tells the other person when you record.

Fonts: Unbounded and IBM Plex — SIL Open Font License (`app/src/main/assets/licenses`).
