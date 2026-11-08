# Architecture

How Mura moves a message from one phone to another with no server involved.
For the exhaustive feature-level detail, see [TECHNICAL-DETAILS.md](TECHNICAL-DETAILS.md).

---

## Design principles

1. **No server, ever.** Not ours, not a volunteer's, not an optional one. Anything that must
   stay online to make Mura work would become the thing to subpoena, seize or block.
2. **The transport is interchangeable.** Bluetooth and Tor are two implementations of one
   interface. The routing layer does not know or care which it is using.
3. **A relay learns nothing.** Not the sender, not the recipient, not the content, not the
   real length. This is enforced by construction, not by policy.
4. **No discovery of strangers.** There is no directory, so nobody can enumerate Mura users
   or find you without you handing them your address.
5. **Nothing leaves before it is sealed.** Encryption happens before a byte reaches a radio
   or a socket.

---

## The layers

```
┌─────────────────────────────────────────────────────────────┐
│  UI — Jetpack Compose                                       │
│  Chats · Status · Calls · Mesh · Me                         │
├─────────────────────────────────────────────────────────────┤
│  MeshEngine                                                 │
│  the app's brain: sessions, groups, files, calls, settings   │
├─────────────────────────────────────────────────────────────┤
│  Crypto                  │  Store                           │
│  X25519 / Ed25519        │  SQLCipher-style encrypted DB     │
│  Double Ratchet          │  relay store, quota, expiry       │
│  sealed sender           │  Keystore-wrapped master key      │
│  group sender keys       │                                   │
├─────────────────────────────────────────────────────────────┤
│  MeshRouter — transport-agnostic epidemic store-and-forward  │
│  offer / want / tombstones · hop counting · dedup by id      │
├──────────────────────────┬──────────────────────────────────┤
│  BleTransport            │  OnlineTransport                  │
│  Bluetooth LE GATT       │  Tor onion service + SOCKS        │
│  advertise · scan · link │  StreamLink over TCP              │
└──────────────────────────┴──────────────────────────────────┘
```

The key structural idea is that `MeshRouter` holds a map of `Link` objects and knows nothing
about radios or sockets. A Bluetooth connection and a Tor circuit both present as a `Link`
with a `remote` flag. That is why a message can begin its journey over Bluetooth and finish it
over Tor without any special handling.

---

## Identity

On first run the app generates:

- an **X25519** key pair for key agreement
- an **Ed25519** key pair for signing

Neither private key ever leaves the device. There is no registration step, because there is
nothing to register with. Your public identity is derived from these keys:

- **Key fingerprint** — 16 hex characters shown in groups of four, for verbal verification
- **Node id** — an 8-byte hash used as a routing address; this is all a relay sees
- **Mura ID** — your Tor onion address, for reaching you over the internet

A QR code contains your public keys, your onion address and your display name. Scanning it in
person is the strongest verification available, because no network is involved at all.

---

## Sealing a message

Every outbound payload goes through the same pipeline:

```
plaintext
   │
   ├─ 1:1    → Double Ratchet  (new key per message, forward secrecy)
   ├─ group  → hash-ratcheted sender key
   └─ call   → one-time key, discarded at hangup
   │
   ▼
sealed-sender envelope      ← hides the author from everyone but the recipient
   │
   ▼
padded to a uniform bucket  ← a one-word reply and a paragraph look identical
   │
   ▼
Bundle { id, dest (8 bytes), type, hops, expiresAt, ciphertext }
```

The `Bundle` is the only thing a relay ever touches. It contains an 8-byte destination hash,
a type, a hop count, an expiry and opaque ciphertext. There is no sender field.

---

## Routing: epidemic store-and-forward

Mura uses an offer/want exchange, the standard approach for delay-tolerant networks:

1. Two phones connect and exchange **HELLO**, each claiming a public key.
2. Each answers the other's challenge with a **Proof** MAC. A link is not `ready` until the
   proof verifies, so a peer cannot impersonate a key it does not hold.
3. Each sends **tombstones** — ids of bundles already delivered, so neither re-offers them.
4. Each **offers** the bundle ids it holds.
5. Each **wants** the subset it does not have and is willing to accept.
6. Bundles transfer. `hops` decrements. Duplicates are dropped by id.

Whether a phone accepts a bundle it is not the recipient of depends on:

```kotlin
relayOk = !forMe && m.hops > 0 && !store.has(m.id) &&
          (subscribed || relays(s.link) || mailbox)

relays(link) = relayEnabled && (!link.remote || internetRelay)
```

So with **Relay for others** on (default), a phone carries strangers' bundles over Bluetooth;
with **Carry anyone's messages online** also on, it carries them over Tor as well.

### Relay storage

A carrying phone lends a bounded amount of space — 200 MB by default, adjustable. Items expire
after 7 days. When the quota is full, the oldest and least useful are evicted first. This
bound is load-bearing: it is what keeps the mesh from being a denial-of-service vector.

---

## The four delivery paths

| Path | Both online? | Mechanism |
|---|---|---|
| Direct Bluetooth | No | Delivered as soon as you are in range |
| Bluetooth carry | No | Nearby phones hold and hop it onward |
| Friend mailbox | No | After 90s undelivered, a mutual contact holds it blind |
| Direct Tor | Yes | Onion-to-onion, both reachable |

**Friend mailboxes** deserve explanation because they solve the hardest problem. When your own
message has gone undelivered for 90 seconds, `offerToMailbox()` marks it as something a
contact might carry. Any contact of yours who *also* has the recipient as a contact will
accept it (`isMailboxFor()`), hold it without being able to read it, and deliver it when the
recipient next comes online. You can be switched off by then.

This is why an old phone left plugged in at home running Mura is so effective: it is a mailbox
that is always online for everyone in your circle.

---

## The internet side

Mura bundles Tor and runs it in-process. Your phone publishes an **onion service**; your
onion address is shared only inside the QR code you show in person or in your end-to-end
encrypted profile update to existing contacts.

Consequences worth stating:

- Neither phone learns the other's IP address.
- There is no server in the path — it is onion-to-onion.
- Nothing is sent before Tor has bootstrapped, including DNS. There is no clearnet fallback.
- Snowflake and obfs4 bridges are built in for censored networks.
- Tor circuits rotate every 10 minutes, as in Tor Browser.

Because onion addresses are only shared deliberately, the set of people who can reach you is
exactly the set you gave your address to. That is a deliberate trade: it means no global
delivery to strangers, and it also means no stranger can find, probe or flood you.

---

## What is stored on the device

| | |
|---|---|
| Database | encrypted; master key wrapped by the Android hardware Keystore |
| Media and files | encrypted individually on disk |
| Relay store | other people's sealed bundles, unreadable, quota-bounded, 7-day expiry |
| Keys | in the Keystore where the hardware allows; ratchet state erased after use |
| Backups | only when you ask; PBKDF2-SHA256 at 310,000 iterations, AES-256-GCM, fresh key per file, written to a folder you choose |

**Panic erase** wipes the database, the files, the settings and the Keystore keys. Because the
on-disk data is encrypted under a Keystore-held key, destroying that key makes what remains in
flash unreadable even if the blocks themselves survive.

---

## Calls

Voice calls run over whichever link is up. A call negotiates a one-time key pair that is
discarded when the call ends, so a recorded call cannot be decrypted later even if the
identity key is compromised afterwards.

Audio bitrate adapts to the link: HD at 16 kHz on a good Bluetooth connection, fewer and
larger packets on a weak one, and tolerance for the extra latency of a Tor circuit.

Video calls are not implemented and are not planned. At 10–60 KB/s over Bluetooth there is
not enough bandwidth, and a six-hop onion circuit adds too much latency for video to be
usable. We would rather not ship a feature that works badly.

---

## Foreground service

Mura has no push service, because a push service means routing notification metadata through
a third party. Instead `MeshService` runs as a foreground service of type `connectedDevice`,
keeping the Bluetooth mesh and the Tor transport alive. It adds the `microphone` type only
during a call, and the `location` type only on Android 11 and below where the OS gates
Bluetooth LE scanning behind location permission.

This is also why Mura asks to be exempted from battery optimisation: there is no server that
can wake it up.
