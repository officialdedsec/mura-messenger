# Mura compared to other private messengers

*Verified November 2026. Other projects move fast. If something here is wrong or out of date,
please [open an issue](../../../issues) — an inaccurate comparison damages our credibility more
than it damages theirs.*

---

## The one-sentence version

Mura is for the case every other private messenger fails: **no internet at all.** If you have
a working internet connection and want the most-audited private messenger, use Signal. If you
need messaging that survives a network shutdown, keep reading.

---

## The gap Mura was built for

Signal, WhatsApp, Telegram, Session and SimpleX differ enormously in how much metadata they
expose and how much you must trust their operators. But they share one property: **they all
require the internet.**

That matters more often than it sounds:

- **Network shutdowns.** Governments cut mobile data during protests and elections. It is a
  routine tactic, not an exotic one.
- **Disasters.** Earthquakes, floods and storms take cell sites down for days.
- **Throttling at scale.** At a large gathering the local cell site saturates and nothing gets
  through, even with full signal bars.
- **No coverage.** Mountains, sea, underground, rural areas, large buildings.
- **Blocked domains.** A messenger whose servers are blocked and which you did not install in
  advance is not available to you at the moment you need it.

In all of those, Signal shows a spinner. Mura hands your message to the phone next to you.

---

## Feature comparison

| | **Mura** | Signal | WhatsApp | Telegram | Session | SimpleX | Briar |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Works with no internet (Bluetooth) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Messages relayed by other users' phones | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Tor built into the app | ✅ | ❌ | ❌ | ❌ | own network | via Orbot | ✅ |
| Voice calls | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| Video calls | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| No phone number | ✅ | beta¹ | ❌ | ❌ | ✅ | ✅ | ✅ |
| No operator servers at all | ✅ | ❌ | ❌ | ❌ | ❌ | self-host | ✅ |
| E2E encrypted by default | ✅ | ✅ | ✅ | ❌² | ✅ | ✅ | ✅ |
| Forward secrecy | ✅ | ✅ | ✅ | partial | ❌³ | ✅ | ✅ |
| Sealed sender / metadata hiding | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Traffic padding by default | ✅ | partial | ❌ | ❌ | ✅ | ✅ | ✅ |
| Works without Google Play Services | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Group size | 200 | 1000 | 1024 | 200k | 100 | large | small |
| Independent security audit | ❌ | ✅ | ✅ | partial | ✅ | ✅ | ✅ |
| Open source | ✅ | ✅ | ❌ | client only | ✅ | ✅ | ✅ |
| Licence | GPL-3.0 | GPL-3.0 | — | GPL-3.0 | GPL-3.0 | AGPL-3.0 | GPL-3.0 |

¹ Signal began testing phone-number-less registration in an Android beta in September 2026,
with a one-time US$2.99 fee, an Account ID and an Account Key. It is beta, Android-only, and
you cannot remove a number from an existing account. Stable Signal still registers with a
phone number.
[Source](https://www.privacyguides.org/news/2026/09/16/signal-enables-phone-number-less-registration-in-beta/)

² Telegram cloud chats are **not** end-to-end encrypted. Only "Secret Chats" are, they are
opt-in, one-to-one only, and they do not sync across devices. Most Telegram users have never
used one.

³ Session removed the Signal protocol's ratchet in favour of its own design and does not
provide forward secrecy in the same sense. This is a documented design decision on their part,
not an oversight.

---

## App by app

### Signal

**Use Signal if you have internet.** It is the best-reviewed private messenger in existence,
with repeated independent audits, a decade of hardening, and cryptography that the rest of the
industry copied. We are not claiming to be better than Signal at being Signal.

What Mura does differently: Signal needs its servers reachable and a working internet
connection. It is a centralised service with excellent privacy properties, not a decentralised
one — Signal Messenger LLC operates infrastructure, holds account records tied to phone
numbers, and is a legal entity that can be served with orders. Their response has been to hold
as little as possible, which is admirable and verifiable. But "holds almost nothing" is a
different guarantee from "holds nothing because there is nothing to hold".

### WhatsApp

Uses the Signal protocol for message content, which is genuinely strong. Everything around
the content is the problem: it requires a phone number, it is owned by Meta, and it collects
substantial metadata — who you message, when, how often, your device, and historically your
address book. It is closed source, so none of this is verifiable. It also effectively needs
Google Play Services.

### Telegram

The most common misconception in messaging: **Telegram chats are not end-to-end encrypted by
default.** Normal cloud chats are encrypted to Telegram's servers, where Telegram can read
them. End-to-end encryption exists only in opt-in Secret Chats, which are one-to-one and do
not sync. Telegram also rolled its own cryptography (MTProto) rather than using reviewed
designs. Good app, excellent features, not a private messenger in the sense the others are.

### Session

Genuinely interesting: no phone number, onion routing over its own decentralised service-node
network. A real improvement on Signal's identity model. Two differences from Mura: it needs
the internet, and it dropped ratcheting forward secrecy for its own protocol.

### SimpleX Chat

The most architecturally thoughtful of the online-only options. No user identifiers at all —
not even a pseudonymous account ID — using unidirectional queues on relay servers you can
self-host. If your concern is metadata on the modern internet, SimpleX deserves a serious
look. It does not work without the internet.

### Briar

**Briar is Mura's nearest relative and got there first.** Bluetooth, Wi-Fi and Tor, no central
servers, no phone number, GPL-3.0. The design Mura follows is the design Briar proved. Briar
also has something we do not: **independent security audits.**

What Mura adds:

| | Mura | Briar |
|---|---|---|
| Encrypted voice calls | ✅ over Bluetooth and Tor | ❌ none |
| Groups | up to 200 | small |
| Status updates, polls, checklists | ✅ | forums/blogs instead |
| File transfer with resume | ✅ | limited |
| On-device translation & voice transcription | ✅ | ❌ |
| Optional disguise mode | ✅ | ❌ |
| Conventional messenger interface | ✅ | more utilitarian |
| Offline mailbox | mutual contacts, automatic | Briar Mailbox (separate app) |
| **Independent audit** | ❌ **not yet** | ✅ |

**Honest recommendation:** if you want the most-audited offline-capable messenger available
today, that is Briar, and we will say so without hesitation. If you need offline messaging
*plus* voice calls *plus* a feature set people will actually adopt, that is Mura — with the
audit caveat fully in view.

### Bridgefy, Berty and other mesh apps

Bridgefy became well known during the 2019–2020 protests and was then found by researchers to
have serious cryptographic weaknesses; it has since changed its approach. Berty is an
interesting Bluetooth + IPFS design. Neither combines a mesh with built-in Tor and a
forward-secret ratchet in the way Mura and Briar do.

---

## Choosing honestly

| Your situation | Use |
|---|---|
| Normal internet, want the best-reviewed option | **Signal** |
| Want no identifiers at all, have internet | **SimpleX** |
| Want no phone number, have internet | **Session** or Signal beta |
| Need offline, want maximum audit coverage | **Briar** |
| Need offline **and** voice calls **and** a full feature set | **Mura** |
| Need to talk to everyone you know | WhatsApp, realistically |

There is no shame in using more than one. Most people who care about this run Signal for
everyday use and keep something like Mura or Briar installed for the day the network stops.
Installing it in advance is the whole point — you cannot download a messenger during a
shutdown.
