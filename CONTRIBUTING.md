# Contributing to Mura

Thank you for considering it. Mura is a privacy tool with no funding, so every contribution
matters disproportionately.

## Found a security flaw?

**Do not open a public issue.** Read [SECURITY.md](SECURITY.md) and report it privately.

## Where help is most valuable

Ordered by how much it would actually help:

1. **Independent review of the cryptography.** Especially `core/Crypto.kt`, the Double Ratchet
   implementation, the group sender-key ratchet and the sealed-sender envelope. Mura has had
   no external audit and this is its single biggest weakness.
2. **Reproducible builds.** Making the build byte-for-byte reproducible is what would let
   anyone verify that a published APK came from this source. See
   [docs/BUILDING.md](docs/BUILDING.md).
3. **Protocol fuzzing.** `core/Frame.kt` parsing is reachable from an unauthenticated peer.
   That is the highest-risk attack surface in the app.
4. **Translations.** Mura currently ships English only. The people who need it most often do
   not read English.
5. **Accessibility.** TalkBack labels, contrast, font scaling, reduced motion.
6. **Battery profiling.** Real measurements on real devices, especially during Tor operation.
7. **A headless mailbox build.** A desktop or always-on build that acts as a blind mailbox
   node is the most requested improvement to delivery reliability.

## Before you open a pull request

```bash
./gradlew test          # must pass
./gradlew assembleDebug # must build
```

- Keep the diff focused. One concern per pull request.
- Match the surrounding style. The codebase is deliberately plainly written with explanatory
  comments on anything non-obvious; please keep that.
- If you touch anything cryptographic, say in the PR description exactly what changed and
  why, and add a test with known-answer vectors. Crypto changes get slow, sceptical review —
  this is not distrust of you, it is the only responsible way to handle it.
- If you add a dependency, justify it. Every dependency is new attack surface and a new
  licence to check. We would usually rather write 50 lines than add a library.
- **Never add analytics, telemetry, crash reporting or any network call that is not through
  Tor.** A PR doing this will be closed without discussion.

## Writing style in docs

Say what the software does and does not do. Do not oversell. If a feature has a limitation,
write the limitation down next to it. The project's credibility rests on being the one that
tells people the awkward parts.

## Licence

By contributing you agree your work is licensed under **GPL-3.0-or-later**, the same as the
rest of the project. There is no CLA and no copyright assignment — you keep your copyright.

## Code of conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Short version: be decent, assume good faith,
argue about code rather than people.
