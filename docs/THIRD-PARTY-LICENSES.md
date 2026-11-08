# Third-party components

Mura is GPL-3.0-or-later. These are the components it bundles or links, with their licences.
All are compatible with GPL-3.0.

| Component | Purpose | Licence |
|---|---|---|
| Tor (`info.guardianproject:tor-android`) | Onion routing, bundled and run in-process | BSD 3-Clause |
| IPtProxy | Snowflake and obfs4 pluggable transports | BSD / MIT (see upstream) |
| AndroidX (core, lifecycle, activity, work, biometric, security) | Platform libraries | Apache 2.0 |
| Jetpack Compose | User interface | Apache 2.0 |
| Kotlin stdlib and coroutines | Language and concurrency | Apache 2.0 |
| Google Tink | Cryptographic primitives | Apache 2.0 |
| ZXing | QR code generation and scanning | Apache 2.0 |
| IBM Plex Sans / IBM Plex Mono | Typefaces | SIL Open Font License 1.1 |
| Unbounded | Display typeface | SIL Open Font License 1.1 |

Run `./gradlew app:dependencies` for the exact resolved versions in the tree you are building.

## Trademarks

**Tor** is a registered trademark of The Tor Project, Inc. Mura bundles and uses Tor but is
not affiliated with, endorsed by, sponsored by, or a product of The Tor Project.

Signal, WhatsApp, Telegram, Session, SimpleX and Briar are referenced in documentation for
comparison purposes only. All trademarks belong to their respective owners, and no
affiliation or endorsement is implied in any direction.

## A note on the LICENSE file

`LICENSE` contains the canonical text of the GNU General Public License version 3, retrieved
from the SPDX license list. The wording is verbatim; only the line wrapping differs from the
version published at gnu.org, which has no legal effect. The authoritative text is always at
https://www.gnu.org/licenses/gpl-3.0.txt.
