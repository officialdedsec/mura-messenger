**What this changes**

**Why**

**Checklist**
- [ ] `./gradlew test` passes
- [ ] `./gradlew assembleDebug` succeeds
- [ ] Tested on a real device
- [ ] No new dependency, or the new one is justified above
- [ ] No analytics, telemetry, crash reporting, or any network call outside Tor
- [ ] Docs updated if behaviour changed

**If this touches cryptography or the protocol**
- [ ] Exactly what changed, and why, is described above
- [ ] Tests with known-answer vectors are included
- [ ] Backward compatibility with 2.7.9 peers is considered and stated

<!-- Crypto and protocol changes get slow, sceptical review. That is not distrust of you,
     it is the only responsible way to handle it. -->
