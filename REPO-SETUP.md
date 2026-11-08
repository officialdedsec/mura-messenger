# How to publish this repository

Everything in this folder is ready to push. This file is the checklist, and it is the only
file you should delete afterwards.

---

## 1. Decide the licence — do this before you push

The repo ships with **GPL-3.0-or-later** in `LICENSE`. This is the one decision that is
genuinely hard to reverse: once other people contribute under a licence, changing it needs
every contributor's agreement.

**Why GPL-3.0 for Mura:**

- A modified version must also be free software with published source. Nobody can take Mura,
  add tracking, close the source and ship it to people who think they are getting the private
  version. For a privacy tool this is the whole point.
- It is what comparable projects use — Briar, Signal Android and Session are all GPL-3.0.
- It is compatible with everything Mura depends on (Tor is BSD-3-Clause; AndroidX, Tink and
  ZXing are Apache 2.0, which is GPL-3.0-compatible).
- Briar is GPL-3.0 and distributes on Google Play, so this is not a Play blocker.

**If you want something different:**

| Licence | Pick it if |
|---|---|
| **AGPL-3.0** | You later add any server component and want that covered too. Overkill for an app with no server, which is why SimpleX uses it and Briar does not. |
| **Apache-2.0** | You want maximum adoption and do not mind closed-source forks. **Not recommended** — someone could ship a tracking fork of a privacy app. |
| **MIT** | Same as Apache but without the patent grant. Not recommended, same reason. |

If you change it, replace `LICENSE`, and update the badge and licence section in `README.md`,
`docs/THIRD-PARTY-LICENSES.md` and `CONTRIBUTING.md`.

---

## 2. Replace the placeholders

Search the tree for these and fill them in:

| Placeholder | Where | Replace with |
|---|---|---|
| `officialdedsec` | README, BUILDING.md | your GitHub org or username |
| `dedsec-mura` | docs/index.html, .github/ISSUE_TEMPLATE/config.yml | your actual org/repo |
| `security@dedseec.com` | SECURITY.md | a real address you monitor |
| `*add your public contact address*` | README, ABOUT.md, CODE_OF_CONDUCT.md | a contact address |
| `docs/pgp-key.asc` | SECURITY.md | add the key file, or remove the line |

```bash
grep -rn "your-org\|dedsec-mura\|add your public contact" --include="*.md" --include="*.html" .
```

Decide whether to publish a personal email at all. A dedicated address or a GitHub-only
contact route is fine, and SECURITY.md already offers GitHub Security Advisories as the
preferred channel, which needs no email.

---

## 3. Push it

```bash
cd mura-repo
git init -b main
git add .
git commit -m "Mura 2.7.9 — initial public release

Open-source serverless encrypted messenger for Android. End-to-end encrypted
messages, files and voice calls over Bluetooth LE mesh and Tor onion services,
with no servers, no phone number and no account.
"
git remote add origin https://github.com/officialdedsec/mura-messenger.git
git push -u origin main
```

---

## 4. Configure the repository on GitHub

**Settings → General**

- **Description** — paste this (136 characters):
  ```
  Open-source encrypted messenger for Android. Chat, files and calls over Bluetooth mesh and Tor. No servers, no phone number, no account.
  ```
- **Website** — `https://officialdedsec.github.io/mura-messenger/`
- **Topics** — the 20 in `docs/SEO-AND-METADATA.md` §3
- Tick **Issues**, **Discussions**. Untick **Wikis** and **Projects** unless you will use them.

**Settings → Pages**
- Source: **Deploy from a branch** → `main` → `/docs`
- The landing page in `docs/index.html` goes live with full SEO and Open Graph metadata.
- After it is live, update the `og:url` and `canonical` values in `docs/index.html` to the
  real URL.

**Settings → Security**
- Enable **Private vulnerability reporting**. `SECURITY.md` points people at it.
- Enable **Dependabot alerts** and **secret scanning**.

**Settings → Branches**
- Protect `main`: require the `Build & test` check to pass before merge.

---

## 5. Cut the first release

An empty Releases tab makes a project look abandoned, and it is the single biggest thing
holding back both installs and search ranking.

```bash
./gradlew assembleRelease
cd app/build/outputs/apk/release
sha256sum *.apk > SHA256SUMS
apksigner verify --print-certs *.apk      # record this fingerprint publicly
```

Create the release tagged `v2.7.9`, attach the APK and `SHA256SUMS`, and paste the release
notes from `CHANGELOG.md`. **Publish your signing certificate's SHA-256 fingerprint in the
release notes** so people can verify that future builds come from the same key.

---

## 6. Get found

In rough order of impact:

1. **F-Droid** — submit via their [inclusion process](https://f-droid.org/docs/Inclusion_Policy/).
   High-authority backlink, and the main discovery route for exactly Mura's audience.
2. **privacyguides.org** and **AlternativeTo** — both rank extremely well for
   "private messenger" and send real users.
3. Post where this audience actually is: `r/privacy`, `r/androidapps`, `r/opensource`,
   Hacker News. Lead with the engineering (Bluetooth epidemic routing with padding and sealed
   sender), not the marketing. This audience is allergic to marketing and receptive to
   mechanism.
4. **Write up the interesting parts.** A post on how delay-tolerant routing works over BLE
   will earn links from people who would never find a repo via keywords.
5. **Get an audit and publish it.** This is both the biggest credibility factor and the thing
   most likely to get Mura written about by someone else.

---

## 7. Before you delete this file

- [ ] Licence decided
- [ ] All placeholders replaced
- [ ] README renders correctly on GitHub, with every screenshot visible
- [ ] GitHub Pages is live and the social preview looks right
      (check with [opengraph.xyz](https://www.opengraph.xyz))
- [ ] CI is green
- [ ] First release published with checksums and the signing fingerprint
- [ ] `keystore.properties` and `*.jks` are **not** in the repository
      (`git log --all --full-history -- '*.jks'` should be empty)
- [ ] Topics and description set
- [ ] Private vulnerability reporting enabled

```bash
rm REPO-SETUP.md
```
