# SEO, keywords and metadata

Everything to paste into GitHub, the Pages site and anywhere else Mura is listed.
Character counts matter: Google truncates meta descriptions around 155–160 characters and
GitHub's About field caps at 350.

---

## 1. GitHub repository "About" description

GitHub's repo description is the single highest-value SEO field you have. It becomes the
page's `<meta name="description">`, the Open Graph description, and the text in every
GitHub search result and topic listing.

**Use this (136 characters):**

```
Open-source encrypted messenger for Android. Chat, files and calls over Bluetooth mesh and Tor. No servers, no phone number, no account.
```

Shorter alternative (122), if you want the keyword density tighter:

```
Serverless open-source messenger. End-to-end encrypted chat and calls over Bluetooth mesh and Tor. No phone number needed.
```

**Website field:** `https://officialdedsec.github.io/mura-messenger/`

---

## 2. Meta description for the website

**Primary (150 characters):**

```
Mura is a free, open-source encrypted messenger for Android. Send messages and make calls over Bluetooth mesh or Tor — no servers, no SIM, no account.
```

**Variants to A/B or use on sub-pages:**

| Page | Description | Chars |
|---|---|---|
| Home | Mura is a free, open-source encrypted messenger for Android. Send messages and make calls over Bluetooth mesh or Tor — no servers, no SIM, no account. | 150 |
| How it works | How Mura delivers messages with no internet: Bluetooth LE mesh relaying, Tor onion services, sealed sender and forward secrecy. Open source, auditable. | 151 |
| Comparison | Mura vs Signal, WhatsApp, Telegram, Session, SimpleX and Briar — which private messengers work with no internet, no phone number and no servers. | 144 |
| Privacy | What Mura knows about you: nothing. No phone number, no analytics, no cloud, no Google Play Services. Open-source proof, not a privacy promise. | 143 |
| Download | Download Mura, the open-source offline encrypted messenger for Android 9+. Signed APK with SHA-256 checksums, or build it yourself from source. | 143 |

---

## 3. GitHub topics

GitHub allows 20 topics. These are the ones people actually search. Add them in
**Settings → General → Topics**, or on the repo home page via the gear beside "About".

```
android
encryption
privacy
messenger
end-to-end-encryption
bluetooth-low-energy
mesh-network
tor
onion-services
peer-to-peer
offline-first
serverless
kotlin
jetpack-compose
anonymity
secure-messaging
decentralized
censorship-resistance
cryptography
opsec
```

---

## 4. Target keywords

Grouped by search intent. The README already uses all of these in natural sentences —
don't stuff them further, it hurts both ranking and readability.

### Primary (high intent, what you want to rank for)

- open source encrypted messenger
- offline messaging app
- bluetooth mesh messenger
- messenger without phone number
- serverless messenger
- tor messenger android
- encrypted chat without internet
- peer to peer messenger android

### Secondary (strong, lower competition)

- off-grid messaging app
- mesh network chat app
- messaging app no sign up
- decentralized messenger android
- anonymous messaging app android
- censorship resistant messaging
- messenger for no network areas
- bluetooth chat app no internet
- onion service messenger
- no google play services messenger

### Long-tail (these convert best)

- how to message someone with no internet
- best private messenger no phone number required
- messaging app that works during internet shutdown
- signal alternative without phone number
- briar alternative with voice calls
- encrypted messenger for protests
- offline chat app for hiking and sailing
- messenger that works on grapheneos
- open source alternative to whatsapp
- disaster communication app android
- messaging app for network blackout

### Comparison keywords (people searching these are ready to switch)

- mura vs signal
- mura vs briar
- signal vs simplex vs session vs briar
- private messenger comparison 2026
- which messenger works without internet

---

## 5. Social / Open Graph preview

Already wired into `docs/index.html`. If you post about Mura elsewhere, use the same:

| Field | Value |
|---|---|
| `og:title` | Mura — encrypted messenger that works without internet or servers |
| `og:description` | End-to-end encrypted chat and calls over Bluetooth mesh and Tor. No servers, no phone number, no account. Free and open source. |
| `og:image` | `docs/images/feature-graphic.png` (1024×500) |
| `og:type` | `website` |
| `twitter:card` | `summary_large_image` |

---

## 6. Practical ranking notes

Things that will move the needle more than keyword choice:

1. **A real release with a signed APK.** Repos with releases rank higher and get far more
   installs. An empty Releases tab reads as abandoned.
2. **Get listed on F-Droid.** It is a high-authority backlink and the main discovery route
   for exactly the audience Mura is for.
3. **Submit to [privacyguides.org](https://www.privacyguides.org) and
   [AlternativeTo](https://alternativeto.net).** Both rank extremely well for
   "private messenger" searches and both drive real users.
4. **Get an independent audit and publish it.** This is the single biggest credibility and
   ranking factor for a security tool, and the thing most likely to get you written about.
5. **Write up the interesting engineering.** A post explaining Bluetooth LE epidemic routing
   with padding and sealed sender will earn links from people who would never find a repo
   through keywords.
6. **Keep the comparison table accurate and dated.** It is your best organic traffic magnet,
   and a wrong claim about a competitor is the fastest way to lose credibility.
7. **Add a `CITATION.cff`** if you want academic citations — researchers do work on
   delay-tolerant networking and will cite a project that makes it easy.
