# Frequently asked questions

## Using Mura

### Do I need a phone number, email or account?
No. None of them. The app generates a key pair on first run and that is your identity. There
is nothing to sign up for because there is no server to sign up with.

### How do I add someone?
Best way: meet them and scan each other's QR code. That is the strongest verification Mura
offers, because no network is involved at all. If you are not together, share your **Mura ID**
(your onion address) by any channel you like — nobody can fake it, and there is no directory
for anyone to look you up in.

### Does it really work with no internet?
Yes, over Bluetooth LE. Both phones need Bluetooth on. If your contact is in range it is
direct; if not, other Mura phones nearby will carry the sealed message and hand it on.

### How far does Bluetooth reach?
Typically 10–50 metres depending on the phones and what is between them. Range is why relaying
matters: three phones in a line cover three times the distance of one.

### How fast is it over Bluetooth?
Roughly 10–60 KB/s per hop. Text is instant. A photo takes seconds. A compressed video takes
minutes per hop. This is a Bluetooth LE limit, not something we can optimise away.

### Can I message someone on the other side of the world?
Yes, if you have each other's Mura ID — it goes over Tor. What you cannot do is reach a Mura
user you have never exchanged addresses with. There is deliberately no global directory.

### Do we both have to be online at the same time?
Only for *direct* Tor delivery. Three of the four delivery paths need no simultaneous
presence — Bluetooth carry and friend mailboxes both hold your message and deliver it later.
See [ARCHITECTURE.md](ARCHITECTURE.md#the-four-delivery-paths).

### What is the single best way to improve delivery?
**Leave an old phone at home, plugged in, running Mura.** It becomes an always-on blind
mailbox for everyone in your circle. One per friend group closes most delivery gaps, with no
servers and nothing to trust.

### Why does Mura need to run in the background?
Because there is no push server. A push notification service would mean routing metadata about
your messages through Google or Apple, which defeats the point. Mura holds its own connection
instead, which means it has to stay alive. Allow it to run in the background.

### Does it drain my battery?
Bluetooth LE is designed for low power, and Mura idles most of the time. Tor costs more. You
can set Tor to Wi-Fi-only, or off, in Settings → Internet. Expect noticeably more drain than a
messenger that sleeps until a push arrives — that is the cost of not having a server.

### Are there video calls?
No, and none are planned. There is not enough bandwidth over Bluetooth (10–60 KB/s) and too
much latency over Tor (a six-hop circuit). We would rather omit a feature than ship one that
works badly.

### Does it work on GrapheneOS / CalyxOS / LineageOS?
Yes, natively. Mura uses no Google Play Services, no Firebase and no Google libraries beyond
AndroidX. De-Googled Android is arguably the best place to run it.

### Is there an iPhone version?
No. iOS restricts background Bluetooth and does not permit an app to run Tor and a persistent
mesh service the way Android does. A useful iOS build is not currently possible.

### Is there a desktop version?
Not yet. A headless build that could act as a mailbox node is the most requested idea and the
one most likely to happen.

---

## Privacy and security

### Can you read my messages?
No. We have no servers, so there is nothing for your messages to pass through. Messages are
end-to-end encrypted and we could not read them even if they did.

### Can you be forced to hand over my data?
We hold nothing. No account records, no phone numbers, no message logs, no IP addresses, no
contact graph. An order compelling us to produce data would produce nothing, because nothing
exists. That is architecture, not policy — and you can verify it in the source.

### What can a phone that relays my message see?
An 8-byte destination hash, a byte count and an expiry. Not the sender, not the recipient, not
the content. Because all payloads are padded to uniform sizes, not even the real length.

### Has Mura been security audited?
**No.** The cryptography follows well-studied designs and is covered by unit tests with
known-answer vectors, but all review so far has been in-house. This is the most important
caveat about Mura and we state it everywhere. If your safety depends on this app, read the
code, build it yourself, and please help us get it audited. See [../SECURITY.md](../SECURITY.md).

### Is Mura better than Signal?
No, and we do not claim it is. Signal is independently audited, far more mature, and better at
being Signal. Mura does one thing Signal cannot: it works with no internet. Many people should
run both. See [COMPARISON.md](COMPARISON.md).

### What about Briar?
Briar got there first, proved the design, and has independent audits we do not have. If you
want the most-audited offline messenger today, use Briar. Mura adds voice calls, larger
groups and a fuller feature set. We think both should exist.

### What is the calculator disguise?
An optional, off-by-default setting that replaces Mura's home-screen icon with a working
calculator. Typing your secret code opens Mura. It exists so that someone who is searched or
coerced is not forced to reveal that they use a private messenger. It is **not** a forensic
defence — it hides the app from someone glancing at your home screen, and will not survive an
examination of the device.

### What does panic erase actually delete?
The database, all stored files, all settings and the Android Keystore keys. Because on-disk
data is encrypted under a Keystore-held key, destroying that key makes any surviving flash
blocks unreadable. Hold for 2.5 seconds. Know how it works before you need it.

### Can someone find me because I use Mura?
Not over the internet — there is no directory, and your onion address only exists where you
put it. Over Bluetooth, a transmitting radio can be direction-found by someone with the right
equipment and physical proximity. Mura rotates its Bluetooth identifier every 15 minutes so
sessions cannot be linked over time, but it cannot make a radio invisible while it is on.

### Does relaying for others put me at risk?
You cannot read what you carry, and you cannot be shown to have chosen to carry any particular
item. Relaying also gives your own traffic cover: a phone that only ever transmits its own
messages is easier to analyse than one constantly carrying other people's. You can turn it off
in Settings → Mesh.

### Can I be tracked by my message sizes?
Padding is on by default and makes payloads uniform, so a one-word reply is indistinguishable
from a paragraph. Padding raises the cost of traffic analysis substantially; it does not make
it impossible against a global passive adversary.

---

## Open source and contributing

### Why is Mura open source?
Because a privacy claim you cannot read is a privacy claim you cannot verify. Closed-source
encryption asks for faith. See the [README](../README.md#why-mura-is-open-source).

### What licence?
GNU GPL v3.0 or later. You may use, study, modify and redistribute it, but a modified version
must also be free software with published source. Nobody can take Mura, add tracking, close
the source and ship it as the private version.

### Can I fork it?
Yes. Please change the name and the application id so users are not confused about who built
what they are running, and keep it GPL-3.0.

### How do I build it?
`./gradlew assembleDebug`. See [BUILDING.md](BUILDING.md).

### How can I help?
Highest value right now: **independent review of the cryptography**, particularly the ratchet
and sealed-sender implementations. After that: translations, accessibility, battery profiling
and protocol fuzzing. See [../CONTRIBUTING.md](../CONTRIBUTING.md).

### Is there a bug bounty?
No — the project is unfunded. We will credit you properly in the advisory and the changelog,
and we will say plainly that you found it.

### How is this funded?
It is not. No ads, no subscriptions, no premium tier, no investors, no data to sell. It is
built by a small research group in their own time.
