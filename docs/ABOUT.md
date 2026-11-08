# About Mura and DedSec

## Who we are

**Mura is built by the DedSec Cyber Security Research Institute**, a small independent
security research group. Our day-to-day work is reverse engineering, malware analysis,
vulnerability research and defensive security tooling.

We are researchers, not a company. Mura has no funding, no investors, no revenue and no
business model. It is built in our own time because we wanted it to exist.

## Why we built it

The question was simple: **what would a messenger look like if you removed every component
that could be subpoenaed, seized, sold, bribed or switched off?**

Work through it honestly and the answer is uncomfortable. The phone number has to go, because
it ties your identity to a government registry and a telecom company. The account has to go,
because an account is a record somebody holds. The push notification service has to go,
because it leaks who is being messaged to a third party. The contact discovery service has to
go, because it means uploading your address book. And then the servers have to go, because a
server is a physical thing in a jurisdiction with an owner who can be compelled.

What remains is phones talking directly to each other.

That turns out to be a hard engineering problem rather than an impossible one, and solving it
produces a useful side effect: a messenger with no infrastructure does not stop working when
the infrastructure is attacked. It keeps working during a network shutdown, after an
earthquake, at a protest where the cell site is saturated, and in places where the servers
would have been blocked anyway.

## What we will not do

- **We will not add a server**, not even an optional one. It would immediately become the
  thing to subpoena, seize or block, and the whole design would be pointless.
- **We will not add analytics.** Not anonymised, not aggregated, not "to improve the product".
- **We will not accept funding with strings.** A privacy tool that answers to an investor is
  on a timer.
- **We will not weaken encryption for anyone**, including any government. We also cannot, in
  any useful sense — we hold no keys.
- **We will not overstate what Mura does.** Every limitation we know about is written down in
  this repository, including the ones that are bad for us.

## What we hold

Nothing. No account records, no phone numbers, no message content, no metadata, no IP
addresses, no contact graph, no analytics.

If we received a legal order compelling us to produce user data, we would produce nothing,
because nothing exists. This is not a policy that could be quietly changed in an update — it
is a consequence of the architecture, and you can verify it by reading the source.

## The honest caveat

**Mura has not had an independent security audit.** The cryptography follows well-studied
designs and is covered by unit tests with known-answer vectors, but all review so far has
been in-house.

We say this on the front page, in the security policy and here, because a privacy project
that buries its weaknesses is not trustworthy regardless of its code quality. If your safety
depends on this software, read the code, build it yourself, and treat the absence of an audit
as the real limitation it is.

If you can perform or fund an audit, please get in touch. It is the single most valuable
contribution anyone could make to this project.

## Credit where it is due

Mura stands on work by others:

- **The Tor Project** — onion routing, and the bundled Tor itself.
- **Briar** — proved that Bluetooth-plus-Tor messaging was practical. We followed a path they
  cut, and they have audits we do not.
- **Signal** — the Double Ratchet and sealed sender, which the whole field now builds on.
- **The Guardian Project** — `tor-android`, which makes running Tor on Android tractable.

Mura uses Tor but is not affiliated with, endorsed by, or a product of The Tor Project, Inc.

## Contact

| | |
|---|---|
| Website | https://www.dedseec.com |
| Privacy policy | https://www.dedseec.com/privacy-policy-and-disclaimer/ |
| Security reports | [SECURITY.md](../SECURITY.md) — please do not use public issues |
| Everything else | GitHub issues, or *add your public contact address* |
