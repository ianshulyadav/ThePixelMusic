# Cast and Android Auto, rewritten

This document describes the cast / Android Auto stack as it is **after** the
rewrite in `pixelmusic-cast-android-auto-rewrite.patch`, what was deleted and
why, and how to check that each part works.

Base commit: `ac9549d` (`fix(youtube): resolve georestricted empty content and
apply player/auth improvements`).

---

## 1. What was wrong

The old implementation had **three owners of the same receiver**:

| Layer | Owned | Result |
|---|---|---|
| `CastBottomSheet` (UI) | route selection, volume, session observation | could start/stop a session behind the service's back |
| `CastStateHolder` / `CastRouteStateHolder` / `CastTransferStateHolder` (ViewModel) | the whole "transfer" state machine, the LAN HTTP server, a *copy* of the queue (`lastRemoteQueue`) | re-loaded the receiver's queue on play/pause, reconciled by diffing two queues, and handed the queue back on disconnect |
| `MusicService.CastMediaSessionPlayer` | a second `Player` implementation pretending to be the session | swapped the `MediaSession`'s player, with its own synthetic one-window `Timeline`, so Android Auto, the notification and the app each saw a different queue |

Everything that made casting unusable follows from that:

* **Two queues.** The phone kept a local queue and the ViewModel kept a remote
  copy; they were compared with `matchesSongOrder` and re-loaded when they
  disagreed. Autoplay appends to the local queue, so they disagreed constantly —
  every auto-queue refill ended in a full receiver reload or in playback
  stopping.
* **One-track sessions.** `CastPlayer.loadQueue` pushed the whole queue with a
  guessed MIME type per song (FLAC/ALAC sniffing, transcode checks, ID3
  parsing…), and gave up on the whole queue when one song failed.
* **Second session surface.** The framework's own media session/notification were
  disabled, but the *service's* notification was driven by a fake player whose
  `Timeline` had one window, so the queue in the notification and in Android Auto
  was not the queue being played.
* **Local-only sources.** Only files served by `MediaFileHttpServerService` could
  be cast; a YouTube / lossless / Telegram track had no castable form at all.
* **Android Auto.** The browse tree was local-DB only and returned a single
  `MediaItem` for a tap, so "play this album" queued one song and stopped. There
  was no `onPlaybackResumption`, no content-style hints, and search paged a list
  it had already truncated to 30 items.

## 2. What replaced it

```
data/service/cast/
  CastController.kt     route discovery + session lifecycle (the only place a
                        CastSession is created, watched or ended)
  CastPlayback.kt       what plays on the receiver: queue window, reconcile,
                        loading, seek grace period
  CastSessionPlayer.kt  the Player the MediaSession hands out: local timeline,
                        receiver clock
  RemoteSpeaker.kt      the contract between the two halves
  LanCastSource.kt      serves local files to the receiver over the LAN
  CastMirror.kt         the few facts the UI is allowed to observe
  CastOptionsProvider.kt receiver id, no framework notification/session
data/service/auto/
  CarLibraryTree.kt     the library Android Auto browses
presentation/components/
  CastSheet.kt          device picker (talks to CastController only)
```

The rule the whole design follows: **the phone owns the queue, the receiver owns
the clock.**

* `CastSessionPlayer` *is* the phone's player to everything outside the service —
  same media items, same timeline, same queue. While a receiver is the speaker it
  answers `getCurrentPosition()`, `isPlaying()`, `getPlaybackState()`,
  `getPlayWhenReady()`, `getDuration()`, `getDeviceVolume()` from `CastPlayback`,
  and it forwards `play/pause/seek/next/previous` there. Because the `MediaSession`
  never changes owner, the notification, the lock screen, Android Auto, the
  widgets and Wear all read the receiver's state with no separate code path.
* `CastPlayback` keeps **three tracks ahead** on the receiver (`AHEAD = 3`) and
  reconciles that window against the local queue whenever either side moves. Only
  the track being started is resolved before playback begins; the rest are
  resolved while it plays. A track that cannot be resolved is skipped over rather
  than failing the queue.
* Stream resolution happens on the phone: `DualPlayerEngine.resolveMediaItem`
  turns `youtube://`, `monochrome://`, `telegram://`, `gdrive://` into an absolute
  URL; local `content://`/`file://` tracks are served by `LanCastSource` with the
  session's auth token and an allow-list that **grows with the queue**, so
  autoplay tracks appended after the cast started are fetchable too (the old code
  fixed the allow-list once, which is why refills 403'd).

### Queue, auto-queue and autoplay

* The car's tap now queues **the folder the row came from**: every playable item
  carries `CONTEXT_TYPE` / `CONTEXT_ID` / `CONTEXT_PARENT` in its metadata extras,
  and `MusicService.resolveContextQueueForRequestedItem` expands that to the whole
  album / artist / playlist / recents / favourites / all-songs list, with the
  start index on the tapped song.
* Because the queue loaded into the session is that real context queue, and the
  session player *is* the local player, the existing `AutoQueueManager` (which
  listens to the local player and refills near the end of the queue) keeps working
  in the car — previously the car drove a synthetic player that `AutoQueueManager`
  never saw.
* When the receiver runs out of its window, `CastPlayback.onReceiverFinished()`
  advances the **phone's** queue, which is what the auto-queue extends; it only
  reports `STATE_ENDED` when the phone's queue itself is finished.
* `onPlaybackResumption` returns the live queue, else recently played, so a
  headset or car reconnecting after the process died resumes instead of sitting
  on an empty session.

## 3. Deleted

```
app/src/main/java/com/unshoo/pixelmusic/data/service/player/CastPlayer.kt
app/src/main/java/com/unshoo/pixelmusic/presentation/viewmodel/CastRouteStateHolder.kt
app/src/main/java/com/unshoo/pixelmusic/presentation/viewmodel/CastTransferStateHolder.kt
app/src/main/java/com/unshoo/pixelmusic/presentation/components/CastBottomSheet.kt
app/src/main/java/com/unshoo/pixelmusic/data/service/auto/AutoMediaBrowseTree.kt
```

and from `MusicService`: the `CastMediaSessionPlayer` class (a second `Player`
with a one-window timeline), `initializeCastSync` / `attachCastRemoteClient` /
`stopCastSync`, `syncCastListeningStatsFromRemote`, `resolveCastRemoteSnapshot`
and the widget's cast overlay (the session player already reports receiver
metadata). From `PlayerViewModel`: the entire transfer state machine, the
`RemoteMediaClient` play/pause/seek/next/previous branches, the LAN-server
route observer and the route/volume plumbing.

`CastSessionSecurity` and `MediaFileHttpServerService` are **kept** — they are
still how a local file gets to a receiver — but they are now driven only by
`LanCastSource`.

## 4. Kept on purpose (and how to finish the job)

`CastStateHolder` survives as an inert shim: `castSession` is always `null`,
`castPlayer` is always `null`, and the three flows the UI reads
(`isRemotePlaybackActive`, `isCastConnecting`, `remotePosition`) are mirrors of
`CastMirror`. It exists only so the ~30 legacy `if (castSession != null …)`
branches in `PlaybackStateHolder` and a few in `PlayerViewModel` keep compiling
without a blind rewrite of two 5000-line files — those branches cannot run
because the session is never handed to them.

Follow-up (mechanical, one commit): delete those branches, then delete
`CastStateHolder` and the `LegacyCastPlayer` interface. Nothing else depends on
them.

## 5. How to verify

Cast:

1. Phone and receiver on the same Wi-Fi. Play a **local** FLAC → cast → the
   receiver plays, phone is silent, notification shows the receiver's position.
2. Skip next three times, then pause on the receiver's own remote → the phone's
   notification and the in-app player follow (this is the clock-delegation check).
3. Let an album play to its end with auto-queue on → the receiver keeps going into
   the auto-queue tracks (this is the check that used to fail: the allow-list and
   the `AHEAD` window now follow the queue as it grows).
4. Cast a **YouTube** track and a **Telegram** track → they resolve on the phone
   and play; a track with no castable form shows a message instead of silence.
5. Pull the Wi-Fi → the session ends, the phone's player is put back at the same
   track and position, without starting to play on its own (unless you chose
   "Stop casting", which resumes here).

Android Auto (or the Desktop Head Unit):

1. Browse → Albums → an album → tap track 5: the queue is the whole album,
   starting on track 5, and it continues past the end of the album into the
   auto-queue.
2. Same for Playlists, Artists, Liked songs, Recently played and Songs.
3. Search from the car's microphone → results, then tap → folder queue.
4. Kill the app from recents while the car is connected → the car's play button
   resumes the last queue (`onPlaybackResumption`) instead of doing nothing.
5. Repeat / shuffle from the car's controls act on the same queue the app shows.

## 6. Build

The patch was authored against `ac9549d` without an Android SDK available in the
authoring environment, so it is **syntax-checked, not compiled**. Apply it and
build:

```bash
git checkout -b cast-rewrite
git apply --check pixelmusic-cast-android-auto-rewrite.patch
git apply pixelmusic-cast-android-auto-rewrite.patch
./gradlew :app:assembleDebug
```

Places to look first if the compiler complains (all are API-name guesses that the
SDK will confirm in one line):

* `CastSessionPlayer` — `Player.PositionInfo(...)` argument list, and the
  `onPositionDiscontinuity` / `onDeviceVolumeChanged` callbacks.
* `CarLibraryTree` — `MediaMetadata.Builder.setDurationMs(Long)`.
* `MusicService.onPlaybackResumption` — the `MediaItemsWithStartPosition`
  constructor arity.
* `LanCastSource` — `MediaFileHttpServerService.configureCastSessionAccess`
  visibility (`internal`, same module).
