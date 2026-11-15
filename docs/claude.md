# Claude Development Guidelines (PixelMusic)

These are the conventions Claude should follow when generating code, documentation, or design suggestions for this project.

## 1. UI / Theming
- **Always** use **Jetpack Compose** with **Material 3 Expressive** components.
- Leverage the provided dynamic colour scheme (`MaterialTheme.colorScheme`) and adaptive edge‑to‑edge handling.
- Follow the **adaptive** skill guidelines for different device form‑factors (phones, tablets, foldables, TVs, Auto, XR).
- Use the **Styles API** (`androidx.compose.ui.graphics.Style`) for reusable component styling.
- Respect the `FeatureToggle` for experimental UI features.

## 2. Architecture
- Stick to **MVVM + Clean‑Architecture** (UI → ViewModel → Use‑Case → Repository).
- All DI should be handled via **Hilt**; annotate with `@Inject`/`@Singleton` as appropriate.
- Use **Kotlin Coroutines** and **Flow/StateFlow** for async work and state propagation.
- Keep business logic in the `domain` layer; UI‑only code stays in `feature/*` modules.

## 3. Media & Playback
- Use **Jetpack Media3** for all playback components.
- The primary playback engine is `DualPlayerEngine` – never replace it with a single ExoPlayer instance.
- Respect the **lossless** flag and the `LosslessAddon` configuration.
- Integrate **Cast** via the `Media3 Cast` library; UI for Cast lives in `feature/playback/cast`.

## 4. Networking & Locale
- All network calls go through `InnerTubeClient` (Retrofit + OkHttp).
- Inject the current locale (`hl` query param) via `InnerTubeHeadersProvider`.
- If a proxy is enabled, route traffic through the optional HTTP/SOCKS5 proxy layer.

## 5. Persistence
- Local music library: **Room** with DAOs under `data/local`.
- Settings & locale: **DataStore** (Proto). Use `LocaleRepository` to change language at runtime.
- Cache any heavy InnerTube responses as protobuf files when appropriate.

## 6. Testing
- Unit tests for use‑cases and repository logic (JUnit + Truth).
- UI tests with **ComposeTestRule**; cover navigation, theming, and locale changes.
- Run **R8** and **baseline profile** checks before CI builds.

## 7. Build & Release
- Project is built with **AGP 9** (managed by the `agp-9-upgrade` skill).
- Flavors: `prod`, `beta`, `nightly` – each has its own `applicationId`.
- Follow the Git remote policy: only push to `origin` (`https://github.com/ianshulyadav/ThePixelMusic.git`).
- Use the **Gradle version catalog** (`libs.versions.toml`) for dependency versions.

## 8. Documentation
- Keep README concise; link to `architecture.md`, `project_overview.md`, and `AI_CONTEXT.md`.
- For any new feature, update the corresponding **FeatureToggle** entry and add a brief description in `features.md`.

---

These guidelines give Claude a clear, consistent direction for any contribution to the PixelMusic codebase.
