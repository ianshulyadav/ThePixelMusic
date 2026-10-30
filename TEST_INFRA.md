# PixelMusic Explore Page Material 3 Expressive E2E Test Infrastructure

## 1. Architecture Overview

This document outlines the opaque-box End-to-End (E2E) test architecture and verification framework for the pure Material 3 Expressive redesign of the PixelMusic Explore screen.

The redesign is governed by four core requirements established in `ORIGINAL_REQUEST.md`:
- **R1. Explore Page UI Redesign with Material 3 Expressive**: Pure Material 3 Expressive component hierarchy, `MaterialExpressiveTheme` encapsulation, expressive button shapes, wavy progress indicators (`LinearWavyProgressIndicator`, `CircularWavyProgressIndicator`), expressive card containers, and concentric artwork scaling.
- **R2. Balanced and Modern Expressive Motion**: Fluid motion using `MotionScheme.expressive()`, calibrated spatial springs (`FastSpatial`, `DefaultSpatial`, `SlowSpatial`), critically damped effects springs (`FastEffects`, `DefaultEffects`, `SlowEffects`), phase-deferred `graphicsLayer` press scale, and zero-recomposition scroll velocity stretching.
- **R3. Explore ViewModel and Data Model Overhaul**: Decoupled presentation UI models (`ExploreSongItemUiModel`, `ExploreAlbumItemUiModel`, `ExplorePlaylistItemUiModel`, `ExploreArtistItemUiModel`, `ExploreChipUiModel`, `ExploreSectionUiModel`), reactive single-source-of-truth state machine (`uiState`, `sectionsState`, `moodChipsState`, `activeChipState`), robust continuation pagination, 4-tier caching, and offline fallbacks.
- **R4. Repository & Git Remote Guardrails**: Strict constraint to repository `origin` (`https://github.com/ianshulyadav/ThePixelMusic.git`).

---

## 2. Opaque-Box Test Architecture

The E2E test suite adopts an **opaque-box** testing methodology:
- Tests are derived strictly from documented requirements, mathematical spring properties, and interface contracts without relying on implementation idiosyncrasies.
- Verifications validate observable contract surfaces: emitted reactive state sequences, data model integrity, component token constraints, motion physics parameters, and state transition invariants.
- All test components run deterministically on the JVM using JUnit 5 (`org.junit.jupiter.api.Test`), Turbine (`app.cash.turbine.test`), Kotlin Coroutines Test (`StandardTestDispatcher`, `runTest`), and MockK.

---

## 3. Four-Tier Testing Methodology

The test suite is structured into four explicit, hierarchical validation tiers:

```
+--------------------------------------------------------------------------+
|                  TIER 4: REAL-WORLD APPLICATION SCENARIOS               |
|      Full User Journeys, Interrupted Sessions, Rapid Mood Exploration     |
+--------------------------------------------------------------------------+
                                     ^
+--------------------------------------------------------------------------+
|                  TIER 3: CROSS-FEATURE INTERACTIONS                      |
|      Filter Switching + Continuation, Offline Fallback, Error Recovery   |
+--------------------------------------------------------------------------+
                                     ^
+--------------------------------------------------------------------------+
|                  TIER 2: BOUNDARY & CORNER CASES                         |
|      Extreme Values, Network Faults, Cache Expiry, High-Frequency Taps   |
|                     (>= 5 test cases per feature)                        |
+--------------------------------------------------------------------------+
                                     ^
+--------------------------------------------------------------------------+
|                  TIER 1: FEATURE CONTRACT COVERAGE                       |
|      R1 UI/Components, R2 Motion Physics, R3 ViewModel/Data Contracts    |
|                     (>= 5 test cases per feature)                        |
+--------------------------------------------------------------------------+
```

### Tier 1: Feature Contract Coverage (>= 5 test cases per feature)
Verifies nominal contract behaviors for each requirement:
- **Feature 1 (R1 - UI & Components)**:
  - Wavy progress indicator state contracts (determinate progress 0f..1f and indeterminate cycling).
  - Circular wavy progress indicator geometric properties, amplitude, and frequency compliance.
  - Expressive card container radius contracts across shape scale (large 20dp, extraLarge 32dp, extraExtraLarge 48dp).
  - Expressive button shapes touch-down morphing contracts and corner transitions.
  - Expressive button group selection, active/inactive pill geometry and container colors.
- **Feature 2 (R2 - Motion Schemes)**:
  - `MotionScheme.expressive()` token configuration and spring constants.
  - Spatial spring underdamping (damping 0.6f - 0.8f, stiffness 200f - 800f) enabling physical overshoot.
  - Effects spring critical damping (damping 1.0f, stiffness 800f - 3800f) preventing color/alpha overshoot.
  - Press feedback target scaling (0.95f - 0.975f) inside phase-deferred `graphicsLayer`.
  - Velocity-based scroll rubber-band stretch calculation, clamp boundaries (-1f..1f), and settle lerp.
- **Feature 3 (R3 - ViewModel & Data Flows)**:
  - Initial loading state transitioning to settled content emission.
  - Decoupled presentation model mapping integrity from raw InnerTube items to UI models.
  - Category / mood chip selection and state updates.
  - Continuation loading (`loadMore`) pagination append and deduplication.
  - Cache tier freshness policy (0-30m fresh, 30m-4h stale, >24h expired) and invalidation.

### Tier 2: Boundary & Corner Cases (>= 5 test cases per feature)
Stress tests extreme inputs, edge conditions, resource constraints, and error boundaries:
- **Feature 1 (R1 - UI & Components)**:
  - Empty or null section list handling (zero items, empty shelves).
  - Truncated text and ellipsis behavior on oversized song and album titles.
  - Missing artwork or null URL handling with fallback shapes.
  - Invalid or out-of-range progress values (<0f, >1f, NaN) in wavy indicators.
  - High-frequency consecutive clicks on expressive buttons / button groups.
- **Feature 2 (R2 - Motion Schemes)**:
  - Extreme scroll velocity flings and boundary clamping (exceeding 3 px/ms).
  - Immediate scroll halt with zero velocity settling to rest.
  - High-frequency press/release interleaving (flutter taps).
  - Reduced-motion / zero animation scale spec fallback.
  - Staggered reveal index clamping (large index overflow handling).
- **Feature 3 (R3 - ViewModel & Data Flows)**:
  - Network failure / HTTP exception propagation to `errorState` without crashing.
  - Offline mode transition when connectivity drops.
  - Out-of-order response rejection (stale generation dropped on rapid refresh).
  - Empty continuation token or exhausted pagination.
  - Corrupted cache file recovery (auto-delete and recover).

### Tier 3: Cross-Feature Interactions
Exercises integration points across UI components, motion parameters, and ViewModel state transitions:
- Category chip switch triggers loading state, cancels pending continuation, and updates sections atomically.
- Pull-to-refresh while category filter is active retains filter context or refreshes current category.
- Offline mode toggle immediately updates state from online sections to local/downloaded tracks.
- Network recovery / retry after error transitions cleanly to content state and resets errorState.
- Continuous scroll triggering continuation while switching mood chip maintains generational integrity.

### Tier 4: Real-World Scenarios
Simulates realistic, multi-step end-to-end user flows in the Explore experience:
- Full Explore User Journey: cold start -> cache restore -> fresh refresh -> mood chip select -> scroll & continuation -> song select.
- Interrupted Session: User enters Explore, starts refresh, loses connectivity, triggers retry, falls back to offline mode.
- Rapid Mood Browsing: User fast-switches between "Relax", "Workout", "Energize", "All" in rapid succession verifying final state matches last selected chip.
- Background Return: User leaves explore, returns after 10 minutes (stale cache), background refresh silently updates content without blanking screen.
- Heavy Catalog Scenario: Multi-section Explore feed with dozens of shelves, heterogeneous items (songs, albums, playlists, artists), verified against UI models.

---

## 4. Test Suite Organization

All tests are located in `app/src/test/java/com/unshoo/pixelmusic/e2e/`:

| Test Suite Class | Tier | Scope |
|---|---|---|
| `ExploreExpressiveComponentsTier1Test.kt` | Tier 1 | R1 UI & Expressive Components Contract Coverage |
| `ExploreExpressiveMotionTier1Test.kt` | Tier 1 | R2 Expressive Motion Schemes Contract Coverage |
| `ExploreViewModelDataTier1Test.kt` | Tier 1 | R3 ViewModel & Data Model Contract Coverage |
| `ExploreExpressiveComponentsTier2Test.kt` | Tier 2 | R1 UI & Components Boundary & Corner Cases |
| `ExploreExpressiveMotionTier2Test.kt` | Tier 2 | R2 Motion Schemes Boundary & Corner Cases |
| `ExploreViewModelDataTier2Test.kt` | Tier 2 | R3 ViewModel & Data Boundary & Corner Cases |
| `ExploreCrossFeatureTier3Test.kt` | Tier 3 | Cross-Feature Interactions & State Handshakes |
| `ExploreRealWorldScenariosTier4Test.kt` | Tier 4 | Real-World User Journeys & Lifecycle Flows |

---

## 5. Execution Command

To run the complete E2E test suite:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.unshoo.pixelmusic.e2e.*"
```
