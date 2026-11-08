# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security flaw.** People depend on this app in
situations where disclosure before a fix could do real harm.

Report privately through one of these:

1. **GitHub Security Advisories** — [open a private advisory](../../security/advisories/new).
   This is preferred: it is encrypted, it keeps the discussion in one place, and it lets us
   credit you and issue a CVE.
2. **Email** — `security@dedseec.com` *(replace with your real address before publishing)*.
   If you want to encrypt, our PGP key is at `docs/pgp-key.asc` *(add this)*.

Please include:

- What the flaw is and where in the code it lives
- How to reproduce it, ideally with a minimal proof of concept
- What an attacker gains, and what they need in order to exploit it
- The Mura version, Android version and device, if relevant

### What to expect

| | |
|---|---|
| Acknowledgement | within 72 hours |
| Initial assessment | within 7 days |
| Fix or mitigation plan | within 30 days for high severity |
| Public disclosure | coordinated with you, after a fix ships |

We will credit you in the advisory and the changelog unless you prefer otherwise. We have no
bug bounty — we are unfunded — but we will say so plainly and credit you properly.

---

## Scope

**In scope**, and genuinely valuable to us:

- Cryptographic flaws — key exchange, the Double Ratchet, group sender keys, sealed sender,
  the call key handshake, backup key derivation
- Nonce reuse, IV reuse, weak randomness, key material left in memory or on disk
- Authentication bypass on a link (the HELLO/Proof exchange in `MeshRouter`)
- Anything that lets a relay learn the sender, the recipient, or the plaintext
- Anything that de-anonymises a user — IP leaks outside Tor, DNS leaks, Bluetooth identifier
  correlation across sessions
- Traffic analysis that defeats the padding scheme
- Bypassing app lock, locked chats, screenshot blocking or panic erase
- Remote crashes or memory corruption reachable from a hostile peer
- Protocol parsing flaws reachable before authentication
- Anything that causes a message to be delivered to the wrong recipient

**Out of scope:**

- Attacks requiring physical access to an unlocked device
- Attacks requiring a rooted or already-compromised device
- Social engineering of users
- Issues in Android itself, or in Tor, that we merely inherit — report those upstream, though
  do tell us if our usage makes them worse
- Bluetooth range limits, throughput, battery use, or the known delivery limitations
  documented in the README — these are design trade-offs, not bugs
- The deliberate absence of a global stranger directory

---

## Known limitations, stated plainly

These are honest and current as of version 2.7.9. We would rather you read them here than
discover them later.

1. **No independent security audit has been performed.** The cryptography follows
   well-studied designs and is covered by unit tests with known-answer vectors, but all
   review so far has been in-house. **This is the most important thing to know about Mura's
   security posture.** If you can fund or perform an audit, please get in touch.
2. **Mura cannot protect a compromised device.** Keylogger, hostile Android build, malicious
   accessibility service, screen over your shoulder, or someone holding your unlocked phone —
   none of that is defended against by any messenger.
3. **Traffic padding raises the cost of traffic analysis, it does not eliminate it.** A
   global passive adversary observing timing across many links can still learn things.
4. **Bluetooth is physically locatable.** A BLE radio that transmits can be direction-found.
   The rotating identifier prevents linking sessions over time; it does not make the radio
   invisible while it is on.
5. **Tor's own limitations apply.** Mura inherits whatever is true of Tor, including the
   possibility of a well-resourced adversary correlating circuits.
6. **Forward secrecy depends on the device erasing properly.** Keys are held in the Android
   Keystore and deleted after use, but flash storage and OS caching are outside our control.
7. **The optional disguise is not a forensic defence.** It hides Mura from a casual observer
   of your home screen. It will not survive a forensic examination of the device, and it is
   not intended to.

---

## Hardening recommendations

If your threat model is serious:

- Build from source and verify what you install.
- Turn on **app lock** and put sensitive conversations in **locked chats**.
- Turn on **disappearing messages** by default; the strongest protection for an old message
  is for it not to exist.
- Leave **traffic padding** and **block screenshots** on. They are on by default.
- Verify a contact's **key fingerprint** in person. A QR scan face to face is the strongest
  verification Mura offers.
- Use a **de-Googled Android** — GrapheneOS in particular. Mura needs no Google services.
- Keep **relay for others** on. It gives your own traffic cover; a phone that only ever
  transmits its own messages is easier to analyse than one that constantly carries others'.
- Know how **panic erase** works *before* you need it.

## Cryptography

| Purpose | Primitive |
|---|---|
| Key exchange | X25519 |
| Signatures | Ed25519 |
| Symmetric encryption | AES-256-GCM |
| Key derivation | HKDF-SHA256 |
| 1:1 ratchet | Double Ratchet (forward secrecy + post-compromise security) |
| Group keys | Hash-ratcheted sender keys |
| Backup KDF | PBKDF2-SHA256, 310,000 iterations, per-file key |
| At rest | Android hardware Keystore-wrapped key |

We implement no novel cryptography and do not intend to. Where we have had to make a choice,
we have taken the conservative one. If you find somewhere we did not, that is exactly the
kind of report we want.
