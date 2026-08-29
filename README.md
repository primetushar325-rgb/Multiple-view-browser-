# MultiView — multi-pane Android browser

A free, open-source Android browser whose home screen is a **grid of independent
panes**. Every pane is a full WebView, so you can run Gmail in one pane, YouTube
in another and WhatsApp Web in a third — all at the same time, all logged in.

Kotlin + XML Views + Material 3. Single `:app` module. No Compose, no Hilt, no
Room, no navigation library, no paid APIs and no API keys of any kind: panes just
load websites the way any browser does.

---

## Features

**Panes**
- Up to 8 panes (4 automatically on low-RAM devices, with a notice).
- Add, close, focus. The focused pane gets a 2dp accent border; every menu and
  keyboard action applies to it.
- Each pane has a 48dp header: favicon-or-globe, hostname, reload, maximize,
  close, and a profile toggle.

**Layouts**
- Full screen · split left/right · split top/bottom · three columns · 2x2 grid ·
  one-top-two-bottom · 3x3 grid.
- **Switching layout never reloads a page.** Existing WebView instances are
  detached from the old grid and re-attached to the new one, so scroll position,
  history and in-flight loads survive. `LayoutResolver` is a pure function of
  (paneCount, layoutId) and is covered by unit tests.

**Site picker**
- Bottom sheet with presets — Gmail, YouTube, Facebook, X, Instagram, WhatsApp
  Web, Messenger, TikTok, Google Drive, Google Search — plus a custom URL/search
  field. The whole list lives in `SitePresets.kt`.

**Everything else**
- Ad & tracker blocker (652 hosts) that never touches a login-critical host.
- Find in page, share, copy link, desktop site, open externally, clear data.
- Fullscreen video, file upload (gallery, documents and camera), camera/mic and
  geolocation prompts, `intent://` / `market://` / `tel:` hand-off.
- Branded offline/error page with Retry.
- Dark mode (follows system, plus "force dark web content") and 80–130% text zoom.
- Pane state persists across restarts, rotation and process death.
- Can be set as the default browser: http/https `ACTION_VIEW` links open in the
  focused pane.

---

## Separate Gmail login per pane

This is the headline feature. Each pane is either:

- **SHARED** — one common cookie store. Every shared pane sees the *same* signed-in
  account. Google's own account switcher still works inside any pane, but it
  switches the account for *all* shared panes at once.
- **ISOLATED** — its own cookie store, so pane 1 can be account A, pane 2
  account B, pane 3 account C, all at the same time.

Toggle it with the person icon on a pane header. Isolated panes show a coloured
**P1 / P2 / P3…** badge so you can tell which pane is which account.

Profile ids are derived from the pane index (`mv-pane-0`, `mv-pane-1`, …), never
random, so **each pane's login survives an app restart**.

### Requirements and limits

Isolation uses `androidx.webkit.ProfileStore` / `Profile`, added in
**androidx.webkit 1.9.0** (this project pins 1.12.1). Every call is annotated
`@RequiresFeature(WebViewFeature.MULTI_PROFILE)` and throws
`UnsupportedOperationException` when the installed **Android System WebView** is
too old to provide it.

Android does not publish a fixed Chrome version for this feature — support is
decided at runtime, so the app asks first:

```kotlin
WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
```

If that returns false, the isolate toggle shows *"Not supported on this device —
please update Android System WebView"* and the app carries on working fully in
shared mode. All profile code is confined to `IsolatedProfileFactory.kt`, guarded
and wrapped in try/catch, so a failure anywhere falls back to shared mode rather
than crashing.

**Memory reality check.** Each isolated profile is effectively its own browser
instance. The app warns when you enable a 5th isolated pane. On a 4GB device,
keep it to 4 isolated panes or fewer.

---

## Building

The repo builds on GitHub Actions with no setup — push to `main` and the
`Android CI` workflow runs lint, unit tests and a debug build, then uploads the
APK as an artifact.

Locally:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Toolchain: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17, compileSdk/targetSdk
35, minSdk 24. Versions are pinned in `gradle/libs.versions.toml`.

`lint` is configured with `abortOnError = false` so warnings never break CI; the
build currently reports **0 errors**.

### Release signing

`assembleRelease` never fails because of missing keys. If `keystore.properties`
exists at the repo root it is used; otherwise the debug keystore signs the
release build.

```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

For CI, set the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` secrets. Pushing a `v*` tag runs the `release` job, which builds a
signed release APK and publishes it as a GitHub Release.

`keystore.properties` and `*.keystore` are gitignored.

---

## Tests

67 JVM unit tests, no emulator required:

| Suite | Covers |
|---|---|
| `HostMatcherTest` | host/subdomain matching, whitelist always wins, URL forms |
| `UrlUtilsTest` | URL-vs-search, https prepend, external schemes, host parsing |
| `LayoutResolverTest` | every (paneCount, layoutId), cap rejection, stable pane order |
| `PanesStateTest` | pane JSON round-trip, order, focus, corrupt-input fallback |
| `ProfileMapTest` | profile-mode round-trip, stable ids, unsupported fallback |
| `SitePresetsTest` | presets are valid https, no duplicate URLs/names/icons |

---

## Components

The app declares exactly one activity (`MainActivity`) and one
non-exported `FileProvider`. The only exported component in the merged manifest
is `androidx.profileinstaller.ProfileInstallReceiver`, injected transitively by
`androidx.core`; it is guarded by the signature-level
`android.permission.DUMP`, so third-party apps cannot invoke it.

## License

MIT. See `LICENSE`.
