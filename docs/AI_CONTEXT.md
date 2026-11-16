# AI Context for Claude – PixelMusic Android App

> This single markdown gives Claude a rapid grasp of the project's structure, core components, and data flow.

## 1. Project Layout (top‑level)
```
PixelMusic/
├─ app/                     # Application class, DI (Hilt), navigation, theme
├─ core/                    # Utilities, extensions, logging, error handling
├─ data/                    # Room DB, DataStore, repositories
├─ domain/                  # Plain Kotlin models, use‑cases (business logic)
├─ feature/                 # Feature modules (playback, explore, share, settings, about…)
│   ├─ playback/            # Media3, DualPlayerEngine, queue, AutoQueue, lossless
│   ├─ explore/             # Home, QuickPicks, personalized catalogs, InnerTube fetch
│   ├─ share/               # ShareBottomSheet, story/card generation
│   ├─ settings/            # Settings UI, proxy config, locale picker
│   └─ …
├─ network/                 # InnerTube API client, proxy handling, auth helpers
├─ testutils/               # Fake repos, coroutine test dispatcher, helpers
└─ buildSrc/                # Gradle scripts, version catalog, AGP‑9 upgrade
```

## 2. Core Architecture
- **UI**: Jetpack Compose + Material 3 Expressive (dynamic theming, adaptive layouts).
- **Pattern**: MVVM + Repository + Use‑Case (clean‑architecture). ViewModels expose `StateFlow`/`LiveData` to Compose.
- **DI**: Hilt (application‑, activity‑, and feature‑scoped components).
- **Concurrency**: Kotlin Coroutines, Flow, `StateFlow` for reactive streams.
- **Media**: Jetpack Media3 + custom `DualPlayerEngine` (two ExoPlayer instances for seamless fail‑over).
- **Networking**: Retrofit + OkHttp, optional HTTP/SOCKS5 proxy layer.
- **Persistence**: Room (library), DataStore (settings, locale), Protobuf cache for InnerTube.
- **Localization**: Runtime locale stored in DataStore; injected as `hl`/`Accept‑Language` in every InnerTube request.
- **Feature Flags**: `FeatureToggle` runtime system; many new functionalities gated behind it.
- **Testing**: JUnit, Robolectric, Compose UI tests, integration tests.

## 3. Data Flow (simplified)
```mermaid
flowchart TD
    UI[Compose UI] --> VM[ViewModel]
    VM --> UC[Use‑Case]
    UC --> Repo[Repository]
    Repo -->|Cache| Local[Room / DataStore]
    Repo -->|Remote| Net[InnerTubeClient]
    Net --> Proxy[Proxy Layer (optional)]
    Proxy --> Server[YouTube InnerTube]
    Net -->|Response| Repo
    Repo --> UC --> VM --> UI
```
- UI dispatches *intents* → ViewModel → Use‑Case → Repository.
- Repository returns data from **local cache** or **remote** (InnerTube). Remote calls include the current `locale`.
- Results flow back as `Flow` → ViewModel → UI recomposition.

## 4. Key Entry Points (files you’ll likely start with)
| Component | Path | Role |
|-----------|------|------|
| `Application.kt` | `app/src/main/kotlin/.../Application.kt` | Sets up Hilt, loads saved locale, initialises navigation. |
| `MainActivity.kt` | `app/src/main/kotlin/.../MainActivity.kt` | Compose host, provides `AppTheme` and `NavHost`. |
| `PlaybackStateHolder.kt` | `feature/playback/src/main/kotlin/.../PlaybackStateHolder.kt` | Central playback state used by UI and service. |
| `DualPlayerEngine.kt` | `feature/playback/src/main/kotlin/.../DualPlayerEngine.kt` | Two ExoPlayer instances for seamless fail‑over. |
| `InnerTubeClient.kt` | `network/src/main/kotlin/.../InnerTubeClient.kt` | Retrofit service building InnerTube calls, injects `hl` based on locale. |
| `LocaleRepository.kt` | `data/src/main/kotlin/.../LocaleRepository.kt` | Persists and streams the selected locale. |
| `AutoQueueManager.kt` | `feature/playback/src/main/kotlin/.../AutoQueueManager.kt` | Auto‑fills the queue using QuickPicks, artist mixes, etc. |
| `FeatureToggle.kt` | `core/src/main/kotlin/.../FeatureToggle.kt` | Runtime feature‑flag system. |

## 5. Most Relevant Docs for Claude
1. **architecture.md** – high‑level stack & module list (already created).
2. **project_overview.md** – quick map of directories & entry points (already created).
3. **AI_CONTEXT.md** – this consolidated file (the most important single source).
4. **inner‑tube‑api.md** – details of the InnerTube endpoints and locale handling (optional, but helpful for network‑related questions).

## 6. What Claude Should Look At First
- Read **AI_CONTEXT.md** (this file) to get the overall picture.
- Dive into **architecture.md** for module responsibilities.
- Open **project_overview.md** if you need to locate a specific class quickly.
- For any network‑call question, refer to **inner‑tube‑api.md**.

---

*All files are placed in the repository under `docs/`.*