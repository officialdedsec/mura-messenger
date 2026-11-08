# Threat model

What Mura defends against, what it does not, and where the honest boundaries are.
A security tool that does not state its limits is making a marketing claim, not a security
claim.

## Who Mura is for

- Journalists and their sources
- Protesters and organisers, particularly where networks get shut down
- Aid and medical workers in disaster zones
- People under surveillance by an abusive partner or family member
- People in countries where messengers are blocked or monitored
- Hikers, sailors and anyone outside coverage
- Anyone who simply does not accept that private conversation requires a corporate account

## Adversaries Mura defends against

### A network operator or ISP
**Defended.** Over Bluetooth there is no network to observe. Over Tor they see Tor traffic,
not Mura traffic, and no destination. They cannot read content, identify your contacts, or
confirm that you are using Mura rather than any other Tor application.

### A government requesting data from the developer
**Defended by construction.** We hold nothing to hand over. No accounts, no numbers, no logs,
no metadata, no contact graph. An order would produce nothing because nothing exists.

### A server breach
**Not applicable.** There is no server to breach.

### Someone who finds your phone, locked
**Mostly defended.** The database and files are encrypted under a key held in the Android
hardware Keystore. App lock adds biometric or screen-lock entry, and locked chats hide
selected conversations behind a second gate. Panic erase destroys the Keystore key, making
surviving flash blocks unreadable.

### A phone relaying your message
**Defended.** It sees an 8-byte destination hash, a byte count and an expiry. Not the sender,
not the recipient, not the content, not the real length.

### Someone impersonating a contact
**Defended.** A link is not usable until the peer proves possession of the private key for
the public key it claims, via the HELLO/Proof challenge in `MeshRouter`. Verifying a
fingerprint by QR in person defeats impersonation entirely.

### Retroactive decryption after a key compromise
**Defended.** Forward secrecy via the Double Ratchet for chats, hash-ratcheted sender keys for
groups, and one-time keys per call. Recording ciphertext today and stealing a key tomorrow
does not reveal yesterday's messages.

### Message-size traffic analysis
**Substantially raised cost.** Uniform padding makes a one-word reply indistinguishable from
a paragraph. This is mitigation, not immunity.

### Censorship of Tor
**Defended.** Snowflake and obfs4 bridges are built in, and you can paste your own.

### Someone glancing at your home screen
**Partially defended**, if you enable it. The optional calculator disguise replaces the
launcher icon with a working calculator. It is off by default.

---

## Adversaries Mura does NOT defend against

State these to yourself before relying on the app.

### A compromised device
**Not defended, and not defendable.** A keylogger, a hostile Android build, a malicious
accessibility service or spyware with root sees everything you type and everything displayed.
No messenger can fix this. If the endpoint is compromised, encryption is irrelevant.

### Someone holding your unlocked phone
**Not defended.** App lock helps if the app is locked and the phone is not already inside it.
Beyond that, a person with your unlocked device reads your messages.

### Coercion
**Not defended.** If someone can compel you to unlock the app, they read it. The disguise may
help if they do not know Mura exists; it will not help if they do. There is no duress code,
and we would not want to imply one would save you.

### Forensic examination
**Not defended by the disguise.** A forensic tool examining the device will identify Mura
regardless of the launcher icon. The disguise defeats a glance, not an examination.

### A global passive adversary
**Partially, at best.** An adversary observing a large fraction of the internet can attempt
timing correlation against Tor. Mura inherits Tor's limitations here. Padding and circuit
rotation raise the cost; they do not eliminate the attack.

### Physical radio location
**Not defended.** A transmitting Bluetooth radio can be direction-found by someone nearby
with the right equipment. The identifier rotates every 15 minutes so sessions cannot be
linked over time, but a radio that is on is detectable. If radio emission is itself dangerous
in your situation, turn Bluetooth off.

### A malicious recipient
**Not defended, and cannot be.** Anyone you send a message to can screenshot it, photograph
the screen, or simply repeat it. Screenshot blocking and view-once raise friction and notify
you; they do not prevent a determined recipient. Only send things to people you trust.

### Your own metadata choices
**Not defended.** If you set your display name to your real name, that is what contacts see.
If you send a photo with faces in it, Mura strips the EXIF data, not the faces.

### An implementation bug we have not found
**This is the big one.** Mura has had no independent audit. The cryptography is conventional
and tested against known-answer vectors, but in-house review is not the same as adversarial
review by specialists. **Treat this as the most likely way Mura fails you.**

---

## Explicit design trade-offs

| Decision | Gain | Cost |
|---|---|---|
| No servers | Nothing to subpoena, seize or block | Delivery needs a path through people; no global stranger reach |
| No push service | No metadata to a third party | App must run in background; more battery |
| No directory | Nobody can enumerate or find you | You must exchange addresses deliberately |
| Bluetooth transport | Works with no network at all | 10–60 KB/s; radio is physically detectable |
| Tor transport | No IP exposure, no server | Latency; both ends must be reachable for direct delivery |
| Uniform padding | Defeats size analysis | More bandwidth per message |
| No content moderation | We cannot read your messages | We cannot police abuse; only you can block |
| GPL-3.0 | Nobody can close-source a tracking fork | Some commercial reuse is excluded |

## If your life depends on this

1. Read the source. Not as a formality — actually read `core/Crypto.kt` and `core/MeshRouter.kt`.
2. Build it yourself. Do not trust any binary, including ours.
3. Verify fingerprints in person, by QR, face to face.
4. Assume no audit has been done, because none has.
5. Use Bluetooth-only mode where radio exposure is safer than network exposure, and the
   reverse where it is not.
6. Turn on disappearing messages. The best protection for an old message is its absence.
7. Have a plan for panic erase before you need it.
8. Consider using Briar as well or instead — it has audits we do not.
