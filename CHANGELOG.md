# Changelog

All notable changes to Mura. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versioning is the app's own.

## [2.7.9] — current

### Added
- **Motion** — small visual touches, drawn on screen only, nothing sent, stored or logged:
  - Seal & send: a shimmer crosses a message you just sent
  - Hop trail: dots hop beside a message while it is carried by the mesh
  - Onion layers: three rings close as Tor connects
  - Sonar: the mesh radar pings when it finds a phone
  - Voice orb and aurora call ring follow your voice level
  - Timer ring drains on disappearing messages

### Notes
- Android's "Remove animations" accessibility setting disables all of the above.

---

*Earlier releases are summarised below. For full detail of any version see
[docs/TECHNICAL-DETAILS.md](docs/TECHNICAL-DETAILS.md).*

## [2.7.7]
### Removed
- Google Drive backup. Backups are now encrypted files written to a folder you choose —
  phone storage, SD card, USB drive, or a cloud app's own folder if you want that. No Google
  account is involved and Mura no longer links Google Play Services at all.

## [2.6]
### Added
- **Carry anyone's messages online.** Over the internet, sealed messages now hop phone to
  phone across Tor connections rather than only through mutual contacts. Carriers cannot read
  them or see the sender.

## [2.5]
### Added
- **Friend mailboxes.** A message undelivered for 90 seconds is offered to mutual contacts,
  who hold it blind and deliver it when the recipient comes online — so the two of you never
  need to be online at the same time, and there is still no server.

## [2.4]
### Added
- Snowflake and obfs4 bridge support for censored networks.

## [2.3]
### Changed
- Hardened internet relaying: no relaying of strangers' mail by default, no relay announcement.

---

## Planned

Not promises, in rough priority order:

- **Independent security audit** — the single most important thing this project needs
- Reproducible builds, so published binaries can be verified against this source
- Translations
- A headless mailbox build for an always-on device
- Optional opt-in public relay nodes for global delivery, off by default, run by anyone

**Not planned:** video calls (insufficient bandwidth over Bluetooth, too much latency over
Tor), and any server operated by us.
