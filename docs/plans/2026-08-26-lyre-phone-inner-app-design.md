# LYRE phone inner app — Technical Design

**Author:** GrokifyOS  
**Date:** 2026-08-26  
**Status:** Draft (product is user-approved; this is the technical design)  
**Revision:** round 4 — STITCH snapshots the pre-overwrite picture compile into `LyreMovieGens`; Pop rebuilds leftover parts only when that gen is still missing; `pop_missing_gen` only if leftover part files are also missing  
**Module id:** `lyre`  
**Channel:** phone (`:app`) — current `versionCode` 314 / `versionName` 0.1.314 in `android/app/build.gradle.kts`  
**Workspace:** `/root/grokifyos`

---

## Overview

LYRE on the phone is a **native Jetpack Compose inner app**, not a WebView of lyre.grok.me (contrast: `CexBotPane.kt` is a persisted WebView of cexbot.grokpot.io). It edits the same `BoardData` JSON and the same me.grokpot.io object-storage bucket as desktop LYRE, without forking or changing the lyre web repo.

The host already has the inner-app machinery this plugs into: `BuiltinPluginCatalog`, `AppsPane` routing in `GrokifyAppRoot.kt`, device-Bearer PHP APIs (`gos_require_access()`), `HostAiClient.complete` for Muse, Media3 ExoPlayer 1.5.1, Coil, OkHttp, camera/mic/media permissions. This design adds a `apps/lyre/` package, application-scoped `LyreSession`, a GrokifyOS `lyre_projects` table + `web/api/lyre.php` proxy (JSON vs streaming split), a local Media3 Transformer cutter behind a short-lived DATA_SYNC FGS, and an Imagine client that hits **me.grokpot.io first** then **SpaceXAI**.

Odysseus is the live `boards.id = 'lyre'` row so desktop LYRE keeps working. Phone-only projects get their own board ids the web app never loads.

---

## Background & Motivation

Desktop LYRE is a grok.me web app: Postgres board JSON (`BoardData` in `/tmp/lyre-inspect/types.ts`) plus files on `https://me.grokpot.io/v1/storage/...`. The web cutter is ffmpeg (`/tmp/lyre-inspect/cut-video.server.ts`: last-frame drop, in/out trim rewrite, mute via `-an`, stitch concat with scale/pad). Movie-prefix rules live in `movie.ts` (`resolvedMovie`, `canStitchClip`, `nextStitchTarget`). Media lives **twice**: `scenes[].frames[]` (`videoSrc`, …) and `videoLayers[].clips[]` (`src`, `linkedFrameId`, …). `movieProgramLayers` **keeps** stitched clips in `layers` and hides members via `movie.parts` / `clipInMovie`.

GrokifyOS has no video editor. CexBot’s WebView pattern is the wrong shape: LYRE needs offline cache, local MediaCodec cuts, a 100-deep file-swap undo, and camera/gallery/file pickers. The user approved a phone-native surface (player, stills/video/audio rails, four bottom chips, Muse in the app bar) and locked the product.

Pain points this design closes:

- Phone cannot currently round-trip Odysseus without a native codec that preserves unknown JSON keys (including web-only `ui`) **and** dual-writes Frame + LayerClip the way the web does.
- Gradle already has ExoPlayer but **no** `media3-transformer` / ffmpeg (`android/app/build.gradle.kts` lines 87–89). `defaultConfig` has **no** `testInstrumentationRunner`.
- grokme’s public inspect (`/tmp/grokme-inspect`) exposes `image_gen` / `image_edit` on `/v1/chat` (images as `{data, mimeType}` base64, not URLs) and authenticated storage, but **no** video REST and **no** unauthenticated object GET. Storage GET with empty rel or `?list=` lists all keys.
- GrokifyOS schema is at `009_discord_media_playlist.sql`; inner apps that need server state add `010_*.sql` + a device-auth PHP endpoint (pattern: `web/api/gbot.php`, `web/api/discord.php`). Apache PHP `memory_limit` is 128 M; vhost `Timeout` is 300s. `DiscordApi` / `GbotApi` parse entire bodies as JSON — cannot stream a 512 MB mp4.
- `rememberDiscordPlayer` builds `ProgressiveMediaSource` on `DefaultHttpDataSource.Factory` only — it cannot play `filesDir` `file://` URIs.

---

## Goals & Non-Goals

### Goals

- Ship LYRE as a built-in host module (`id = lyre`) in the Apps hub.
- Round-trip Odysseus `BoardData` last-write-wins with desktop; phone-only boards never appear in the web app.
- Player + stills rail + video rail + audio rails + one clock; app bar (project / Muse); chips **Scenes · Library · Bin · Activity**.
- Enforce movie-prefix vs leftover rules (stitch / pop / trim / split / mute / loop / generate / insert) with **dual-write** Frame + LayerClip and **no clip deletion on stitch**.
- Local cut: drop-frame stitch, in/out trim rewrite, split, extract audio, mute (no new rail), burn-audio mix. Keep origs. Undo last 100 editor actions by file swap.
- Generate stills via grokme Grok Build tools first; video via grokme REST first; SpaceXAI backup for both.
- Public watch MP4 at `public/watch/{token}.mp4` with a **file-only** unauthenticated GET matcher on me.grokpot.io; private deletes the object. GrokifyOS proxy until that grokme deploy lands.
- Muse via `HostAiClient.complete` with `INTERNAL_SESSION_TITLE_PREFIX` (`·`) so Chat history hides it.
- Kotlin unit tests for leftover rules (golden JSON fixtures), instrumented cutter tests, catalog wiring test. Phone OTA after shippable work.

### Non-Goals

- Do **not** change the lyre web repo. Do **not** WebView lyre.grok.me.
- Do **not** add a fifth bottom chip. Muse is app-bar only.
- Do **not** burn Caption / Dialogue / Notes into the watch MP4.
- Do **not** generate, trim, split, drag, or insert inside the locked movie prefix without Pop.
- Do **not** put `ME_API_KEY` / LYRE `DATABASE_URL` on the device. Server-side proxy only.
- Do **not** use the GrokifyOS Chat bridge (`bridge/server.js` / `media-ingest.js`) as the Imagine primary path (wrong workspace, pollutes Chat).
- Do **not** implement sleep/Samsung Health, watch-face LYRE, or a marketplace WebView package.
- Do **not** invent extra product surface (no extra chips, no desktop UI clone, no multi-user OT).
- Do **not** add phone-only keys to shared `Frame` JSON (desktop save may strip unknown fields).

---

## Proposed Design

### Architecture

```mermaid
flowchart TB
  subgraph phone["Phone APK :app"]
    Hub[Apps hub] --> Pane[LyrePane]
    Pane --> Store[LyreStore chrome prefs]
    Pane --> Session[LyreSession application-scoped]
    Session --> Rules[LyreRules pure CutPlan]
    Session --> Cutter[LyreCutter Media3 Transformer]
    Session --> Undo[LyreUndo 100 file-swaps]
    Session --> Gens[LyreMovieGens picture compiles]
    Session --> Cache[LyreCache + pending/*.json]
    Session --> FGS[LyreCutService DATA_SYNC FGS]
    Session --> Imagine[LyreImagineClient]
    Session --> Api[LyreApi JSON + GET stream + POST put]
    Pane --> Player[ExoPlayer DefaultDataSource concat]
    Pane --> Muse[LyreMuse HostAiClient]
  end

  subgraph gos["grokifyos.grokpot.io"]
    PHP["web/api/lyre.php JSON + GET stream + POST put"]
    WatchProxy["web/api/lyre-watch.php unauth stream"]
    MySQL[(lyre_projects)]
    Bridge[HostAiClient → bridge WS]
  end

  subgraph grokme["me.grokpot.io — same LYRE project"]
    PG[(boards.id JSON)]
    S3["/v1/storage"]
    Chat["/v1/chat image_gen"]
    Vid["/v1/imagine/video assumed"]
  end

  subgraph xai["SpaceXAI backup"]
    ImgAPI["POST /v1/images/generations"]
    VidAPI["POST /v1/videos/generations"]
  end

  Api --> PHP
  PHP --> MySQL
  PHP --> PG
  PHP --> S3
  PHP --> Chat
  PHP --> Vid
  WatchProxy --> S3
  Imagine -->|"start + poll"| PHP
  Imagine -->|"backup"| ImgAPI
  Imagine -->|"backup"| VidAPI
  Muse --> Bridge
  Cache -->|"local File"| Player
  Gens --> Cache
  FGS --> Cutter
```

Phone talks **only** to GrokifyOS with the existing device Bearer (`gos_…` via `TokenStore.tokenFlow`) except SpaceXAI backup (vault key) and the optional public watch URL. PHP holds `GROKIFY_LYRE_*` secrets and proxies board JSON + storage + Imagine to the LYRE grokme project.

`LyrePane` does **not** own cutter jobs. `LyreSession` is constructed from `applicationContext` (same lifetime as `GrokifyApp` / `TokenStore`) so leaving the pane does not cancel a 180s stitch.

### Package layout

All new Kotlin under `android/app/src/main/java/io/grokify/os/apps/lyre/`:

| File | Role | Lands in |
|------|------|----------|
| `LyrePane.kt` | Root Compose host module. Back to hub, permission hook, observes `LyreSession`. | PR 1 shell; PR 3 wires editor |
| `LyreStore.kt` | `SharedPreferences` (`lyre_prefs`): last project id, chip, muse open, loop, playhead. **Chrome only** — not board JSON, not pending uploads. | PR 2 |
| `LyreModels.kt` | `BoardData`, `Scene`, `Frame`, `BoardMovie`, `MediaLayer`, `LayerClip`, `RefImage`, `CutPlan`, project DTO. | PR 2 |
| `LyreBoardCodec.kt` | `org.json` parse/stringify that **preserves unknown keys** (esp. `ui`). | PR 2 |
| `LyreClip.kt` | **1:1** port of `/tmp/lyre-inspect/clip.ts` (see Kotlin surface below). Read-only. | **PR 3** |
| `LyreMovie.kt` | **1:1** port of `/tmp/lyre-inspect/movie.ts` including `moviePlayDuration`, `moviePartDurations`, `movieProgramLayers`. Read-only. | **PR 3** |
| `LyreRules.kt` | Pure mutations: `(BoardData) -> RuleResult(board, cutPlan?)`. **No** cutter, undo, or IO. | **PR 4** |
| `LyreSession.kt` | Application-scoped controller: apply `RuleResult`, run cutter, **patch movie from CutOk**, push undo, activity JSONL, debounce `save_board`, pending-file flush. | PR 5 |
| `LyreCutService.kt` | Short-lived FGS `foregroundServiceType=dataSync` on **CHANNEL_LYRE** for cutter jobs **> ~15s** (stitch / burn / long trim). | PR 5 |
| `LyreCutter.kt` | Media3 Transformer jobs + probe. `rebuild(partFiles, dropLast)` is **Pop fallback only** (missing gen). | PR 5 |
| `LyreUndo.kt` | 100-deep file-swap stack. | PR 5 |
| `LyreActivity.kt` | Infinite local JSONL log. | PR 8 (log writes from Session in PR 5) |
| `LyreCache.kt` | `filesDir/lyre/{boardId}/…` keyed by object key + `pending/{seq}.json`. `resolve` step 2 calls streaming `getStorage`. | PR 2 `board.json` + pending schema + **read** `resolve`/`getStorage`; PR 5 objects/undo/`movie-gens` |
| `LyreMovieGens.kt` | Picture-compile generation stack. Stitch **snapshots** the current picture compile into `g{parts.size-1}` before overwrite if that slot is empty; Pop restores previous gen. **Not** the undo dir. | PR 5 |
| `LyreApi.kt` | Device-auth client: JSON actions, streaming `getStorage` (PR 2), streaming `putStorage` POST (PR 5). | PR 2 JSON + **GET** stream; PR 5 **POST** stream |
| `LyreImagineClient.kt` | grokme first (start + poll), SpaceXAI backup. | PR 6 |
| `LyreMuse.kt` | Rails context → `HostAiClient.complete`. | PR 8 |
| `LyrePlayer.kt` | ExoPlayer + `DefaultDataSource` + concatenating items. **Not** a clone of `rememberDiscordPlayer`. | PR 3 |
| `ui/LyreEditor.kt` | Player + rails + clock + app bar + chips. | PR 3 |
| `ui/LyreClipSheet.kt` | One sheet, Picture / Clip, sideways chips. Stitch/Pop **visible** in PR 4; **click-to-mutate** in PR 5. | PR 4/5 |
| `ui/LyreScenes.kt` | Scenes chip pane. | PR 3 shell; PR 8 polish |
| `ui/LyreLibrary.kt` | Library pool. | PR 3 shell; PR 8 |
| `ui/LyreBin.kt` | Recycle bin. | PR 3 shell; PR 8 |
| `ui/LyreActivityPane.kt` | Infinite log + jump. | PR 3 shell; PR 8 |
| `ui/LyreWatch.kt` | Public/private, QR, native play. | PR 7 |
| `ui/LyreMuseSheet.kt` | Overlay chat. Not a chip. | PR 8 |

Catalog / host wiring (existing files) — **PR 1 must touch all of these**:

- `BuiltinPluginCatalog.kt` — `const val LYRE = "lyre"` + `PluginManifest`
- `PluginModels.kt` — `PluginIconKey.Lyre` (exhaustive enum)
- `PluginFavicon.kt` — `drawableRes(LYRE) -> R.drawable.plugin_ic_lyre` **and** `vectorIcon(PluginIconKey.Lyre) -> Icons.Filled.Theaters` (when is exhaustive; omitting the branch **does not compile**)
- `RemotePluginCatalog.kt` — `parseIcon("lyre") -> PluginIconKey.Lyre`
- `GrokifyAppRoot.kt` — `AppsPane` **parameter** `onRequestLyrePerms`, **call site** lambda (~630), `when (resolved)` branch (~4700)
- `res/drawable-nodpi/plugin_ic_lyre.png` — 1:1 mark. `PluginFaviconTest.everyBuiltinHasAFavicon` asserts `drawableRes(app.id)`, **not** the vector fallback.

`appsNavShortTitle` already has `else -> app.title.take(12)` so `"LYRE"` needs **no** extra branch.

### Catalog entry

```kotlin
const val LYRE = "lyre"

PluginManifest(
    id = LYRE,
    title = "LYRE",
    subtitle = "Native storyboard editor: rails, local cut, Imagine, watch link.",
    version = "1.0.0",
    source = PluginSource.Builtin,
    kind = PluginKind.HostModule,
    hostModuleId = LYRE,
    capabilities = listOf("Video", "AI", "Camera", "Media"),
    accent = PluginAccent.Rose,
    icon = PluginIconKey.Lyre,
    featured = true,
    requiredKeys = listOf(
        PluginRequiredKey(
            id = ApiKeyIds.SPACEXAI,
            label = "SpaceXAI API key",
            description = "Backup Imagine (stills + video) when me.grokpot.io fails. Muse uses Grok Build without it.",
            required = false,
        ),
    ),
)
```

**AppsPane plumbing (today the signature has only wifi/bt/place/notif):**

Call site in `GrokifyAppRoot.kt` ~630, next to `onRequestNotifPerms`:

```kotlin
2 -> AppsPane(
    // …existing params…
    onRequestNotifPerms = {
        onEnsurePermissions(listOf(AppPermissionId.NOTIFICATIONS.id))
    },
    onRequestLyrePerms = {
        onEnsurePermissions(
            listOf(
                AppPermissionId.CAMERA.id,
                AppPermissionId.MICROPHONE.id,
                AppPermissionId.MEDIA.id,
            ),
        )
    },
)
```

Signature (~4685) gains `onRequestLyrePerms: () -> Unit = {}`. Branch:

```kotlin
BuiltinPluginCatalog.LYRE, "lyre" -> io.grokify.os.apps.lyre.LyrePane(
    onBack = onBackToHub,
    onRequestPermissions = onRequestLyrePerms,
)
```

Same pattern as Discord/Gbot — **not** CexBot.

### Screen layout (locked product)

```mermaid
flowchart TB
  subgraph appbar [App bar]
    Back[Back]
    Name[Project name — tap switcher]
    Muse[Muse]
  end
  Player[Player]
  Clock[One clock — still poster stretches to clip / hold; scene titles as ticks]
  Stills[Stills rail]
  Video[Video rail — movie locked prefix + leftovers]
  Audio[Audio rails]
  subgraph chips [Bottom chips — exactly four]
    Scenes
    Library
    Bin
    Activity
  end
  appbar --> Player --> Clock --> Stills --> Video --> Audio --> chips
```

- Tap leftover still → one sheet. Default **Clip** if no `videoSrc`, **Picture** if changing the poster. Chip rows scroll sideways.
- Picture chips: generate still, edit still, camera, gallery, restore picture, aspect, extra still refs, Caption / Dialogue / Notes (editor text only).
- Clip chips: Imagine (prompt, duration, aspect, resolution, ≤3 extra refs, ≤3 voices), generate clip, edit clip (this clip as the video; new file; orig kept), camera / gallery / files, restore clip, remove clip, mute, loop, split at playhead.
- `+` on stills rail: after a leftover, or at the end — camera, gallery, generate still, or blank hold. Never insert inside the movie without Pop.
- **Stitch** chip is shown on the leftover whose `LayerClip.id == nextStitchTarget(...)?.id`, **not** on the next still (holds between movie and leftover video are skipped by `orderedVideoClips`).
- Scene titles on the clock match the Scenes chip.

UI chrome uses `GrokifyColors` (`ui/theme/Theme.kt`: Void / Panel / GlowRose / TextPrimary).

### Board JSON compatibility

Source of truth: `/tmp/lyre-inspect/types.ts` `BoardData`.

```kotlin
data class BoardData(
    val title: String,
    val brainstorm: String,
    val scenes: List<Scene>,
    val activeSceneId: String,
    val refFolders: List<RefFolder>,
    val activeFolderId: String,
    val videoGen: VideoGenLock?,
    val videoLayers: List<MediaLayer>,
    val audioLayers: List<MediaLayer>,
    val libraryAudio: List<LibraryAudio>,
    val libraryVideo: List<LibraryVideo>,
    val movie: BoardMovie?,
    val ui: JSONObject?,          // opaque — do not strip
    val extra: JSONObject?,       // any unknown top-level keys
)
```

**Codec rule (non-negotiable for Odysseus):** parse with `org.json.JSONObject`, map known fields, keep a copy of the original object. On save, write known fields back into that object; **never drop** `ui` or unknown keys. `kotlinx-serialization-json` is on the classpath but the serialization **plugin is not applied** in `android/app/build.gradle.kts`; even with it, typed decode would drop unknown keys. `org.json` is the host convention (`GrokifyApi`, `DiscordApi`, `GbotApi`).

Phone **ignores** web-only `SessionUi` fields at runtime (`canvasMode`, `asidePane`, `layouts`, `viewer`, `pictureHeight`, `timelinePps`) but **persists them unchanged**. Phone UI state (chip, playhead, muse open) lives in `LyreStore`, not in `BoardData.ui`, so a phone save cannot clobber desktop layout. Exception: `ui.loopClip` is a product control on the Clip sheet — write only that key, leave the rest of `ui` intact.

**No phone-only Frame keys.** Cut / generate failures reuse `Frame.generatingError` (stills) and `Frame.videoGeneratingError` (clips **and** local cuts). Desktop save may strip unknown fields on Odysseus; inventing `cutError` is forbidden.

Last-write-wins: debounce **800 ms** after a mutating edit, then `POST lyre.php action=save_board` with the full JSON. No ETag / merge. `LyreSession.flushSave()` on `Lifecycle.Event.ON_STOP` and on back-to-hub so a killed process does not lose the last edit. Odysseus races with desktop are a documented risk, not an extra protocol.

Empty board for **New** and for Odysseus-Postgres ensure (same JSON):

```json
{
  "title": "Untitled",
  "brainstorm": "",
  "scenes": [{ "id": "sc_1", "title": "Scene 1", "book": "", "durationTargetSec": 0,
               "logline": "", "dialogue": "", "notes": "", "frames": [] }],
  "activeSceneId": "sc_1",
  "refFolders": [{ "id": "lib", "name": "Library", "images": [] }],
  "activeFolderId": "lib",
  "videoLayers": [],
  "audioLayers": [],
  "libraryAudio": [],
  "libraryVideo": []
}
```

Ids: `sc_`, `fr_`, `ly_`, `lc_`, `rf_` + 8 hex, matching web vibe; phone-only **board** ids are `lyre_phone_<uuid>` so the web app (which loads `id=lyre`) never sees them. (Web loader filtering `lyre_phone_*` is **unverified** — lyre repo is not in this workspace; the id prefix is the safety hatch.)

### Dual-write contract (Frame + LayerClip)

`BoardData` has two media representations. Phone **must** keep them in sync the way `movie.ts` expects:

| Event | `Frame` | Linked `LayerClip` (`linkedFrameId == frame.id`) | `videoLayers` membership |
|-------|---------|--------------------------------------------------|--------------------------|
| Generate / camera / gallery clip | set `videoSrc`, `videoDurationSec`, `videoFps`; first time also `origVideoSrc` | set `src`, `durationSec`, `sourceDurationSec`; first time `origSrc` | **insert** clip if missing (leftover only) |
| Trim / mute / restore clip | same keys + `videoMuted` | same keys | **keep** |
| Split | original frame trimmed; new frame + copied still | original clip trimmed; **new** clip with new id linked to new frame | **keep both** |
| Remove clip | clear `videoSrc` (still holds); keep `origVideoSrc` | remove clip **or** clear `src` | clip gone; frame stays as hold |
| Remove beat | frame → Bin as `RefImage` | clip removed | gone |
| **Stitch** | unchanged | **unchanged** (`src` stays) | **do not remove** |
| **Pop** | unchanged | **unchanged** | **do not remove** |

Stitch/Pop only mutate `movie` (`src`, `durationSec`, `fps`, `playDurationSec`, `parts`). `movieProgramLayers` hides members via `clipInMovie`; leftovers “leave the program” **visually**, not by deletion.

**Pop is not an undo-dir restore and is not a byte-less JSON edit for 3+ parts.** `BoardMovie.origSrc` is a **single** slot (burn-audio keeps the picture compile there). After A+B+C the live `movie.src` is the 3-part picture compile; Pop of C must restore the **2-part picture compile**. That file is `g1`. STITCH **copies the current picture compile into `g{preStitch.parts.size-1}` before the cutter overwrites `movie.mp4`** when that slot is empty — that is the Odysseus path (desktop 2-part movie, no `movie.g1.mp4`, phone stitches C, Pop C must not 404). `ensureCurrent` reads **`boardBefore.movie`**, never `RuleResult.board.movie` (the result already has the new part appended, so `parts.size-1` would be the *new* gen and the snapshot would be overwritten by `push`). See [Picture-compile generation stack](#picture-compile-generation-stack).

Every leftover with non-blank `videoSrc` has exactly one `LayerClip` with `linkedFrameId`. Trim/mute/split/generate/restore/camera update **both**. Unit tests fail if Frame `videoSrc` ≠ clip `src` after those ops.

### Movie vs leftover rules

Port `movie.ts` / `clip.ts` **1:1** into pure Kotlin. Read-only objects land in **PR 3** (clock/rail need them). Mutating `LyreRules` lands in **PR 4**. Byte rewrites land in **PR 5**.

```kotlin
object LyreMovie {  // movie.ts — PR 3
    fun orderedVideoClips(layers: List<MediaLayer>): List<LayerClip>
    fun resolvedMovie(movie: BoardMovie?, layers: List<MediaLayer>): BoardMovie?
    fun moviePlayDuration(movie: BoardMovie): Double
    fun movieIsTrimmed(movie: BoardMovie): Boolean
    fun movieProgramLayers(movie: BoardMovie?, layers: List<MediaLayer>): List<MediaLayer>
    fun clipInMovie(movie: BoardMovie?, clipId: String, layers: List<MediaLayer>): Boolean
    fun frameInMovie(movie: BoardMovie?, layers: List<MediaLayer>, frameId: String): Boolean
    fun movieGroupOnLayer(layer: MediaLayer, movie: BoardMovie?, layers: List<MediaLayer>): MovieGroup?
    fun nextStitchTarget(layers: List<MediaLayer>, movie: BoardMovie?): LayerClip?
    fun canStitchClip(clipId: String, src: String?, layers: List<MediaLayer>, movie: BoardMovie?): Boolean
    fun moviePartDurations(originals: List<Double>, playDuration: Double, minDur: Double = 0.1): List<Double>
}

object LyreClip {  // clip.ts 1:1 — PR 3
    fun frameFps(frame: Frame): Double
    fun frameIn(frame: Frame): Double
    fun frameOut(frame: Frame): Double
    fun clipLength(frame: Frame): Double
    fun clipEdgeTrims(clip: LayerClip): ClipEdges
    fun clipBackup(clip: LayerClip, frame: Frame?): Pair<String, Double>?
    fun presentedVideoWindow(clip: LayerClip, fps: Double = 24.0): VideoWindow
    fun lastFrameTime(clip: LayerClip, fps: Double = 24.0): Double  // leftover presented window; NOT stitch drop-last
    fun movieClips(scenes: List<Scene>): List<StoryboardClip>
    fun movieDuration(scenes: List<Scene>): Double
    fun clipAtTime(clips: List<StoryboardClip>, t: Double): StoryboardClip?
    fun clipOf(scenes: List<Scene>, frameId: String): StoryboardClip?
    fun nextClipAfter(scenes: List<Scene>, frameId: String): StoryboardClip?
}

data class CutPlan(
    val kind: CutKind,                 // STITCH, POP, TRIM, MUTE, SPLIT, EXTRACT, BURN_AUDIO
    val movieKey: String?,
    val clipKey: String?,
    val dropLast: Boolean = false,
    val keepSec: Double? = null,       // stitchMovieBuffers keepSec
    val trimInSec: Double? = null,
    val trimOutSec: Double? = null,
    val splitAtSec: Double? = null,
    val beds: List<AudioBed> = emptyList(),
    // POP: Session restores LyreMovieGens (or first-clip src). Cutter only if gen missing → rebuild fallback.
    // STITCH snapshot uses boardBefore, not this result's movie.parts.
)

data class RuleResult(val board: BoardData, val plan: CutPlan?)

object LyreRules {  // PR 4 — pure; no IO
    fun stitch(board: BoardData, clipId: String): RuleResult
    fun pop(board: BoardData): RuleResult
    fun trim(board: BoardData, clipId: String, inn: Double, out: Double): RuleResult
    fun mute(board: BoardData, clipId: String): RuleResult
    fun split(board: BoardData, clipId: String, atSec: Double): RuleResult
    fun insertHold(board: BoardData, afterFrameId: String?): RuleResult  // null = end
    fun dumpScene(board: BoardData, sceneId: String): RuleResult
    fun restoreClip(board: BoardData, clipId: String): RuleResult
    fun restorePicture(board: BoardData, frameId: String): RuleResult
    fun removeClip(board: BoardData, clipId: String): RuleResult
    fun removeBeat(board: BoardData, frameId: String): RuleResult
    fun extractAudio(board: BoardData, clipId: String): RuleResult
    fun burnAudio(board: BoardData): RuleResult
}
```

`LyreRules.stitch` / `pop` **return** a `CutPlan`; they do **not** call the cutter and they **do not** probe duration. `LyreSession.apply(result)` (PR 5) takes `boardBefore = session.board` and `result`. On STITCH it calls `LyreMovieGens.ensureCurrent(preStitch = boardBefore.movie)` **before** Transformer may replace `movie.mp4`; on success it concatenates the **picture** compile (never `movie.burn.mp4`) with the leftover and `push`es `g{result.board.movie.parts.size-1}`. Then it **patches** `movie.src` / `movie.durationSec` / `movie.fps` from `CutOk` (or from the restored gen / rebuild on Pop) **before** committing. Until PR 5, the Clip sheet may **show** Stitch/Pop from `canStitchClip` / `movie.parts.size > 1` but the click is a no-op (or a toast “cutter in a later build”). JSON-only Pop that leaves `movie.src` still containing the popped clip is **not** shipped as playback.

**Locked prefix**

- First ordered **video** clip (`orderedVideoClips` — skips empty `src`) is the movie until `movie.src` exists (`resolvedMovie`).
- Stitched members are `movie.parts[].clipId` (or the first clip if parts empty).
- **Stitch** is shown on `nextStitchTarget(layers, movie)` — the next **video** clip after the last `movie.parts` member. Still-only holds on the stills rail between them **do not** steal the chip (`orderedVideoClips` skips them).
- **Stitch** = local concat; drop the last **encoded** frame of the current **compiled movie file** (`stitchMovieBuffers`: probe `frames`/`fps`, `trim=end_frame=frames-1` unless `keepSec` is set). This is **not** `lastFrameTime(LayerClip)` (that is leftover in/out presentation).
- **Pop** = drop the tail `movie.parts` entry; rewrite **picture** `movie.src` (never the burn-audio file). Ordered byte path (`n = remaining.parts.size - 1`):
  1. remaining `parts.size == 1` → `movie.src` = that clip’s `src` (no compiled file; same as `after_pop.json`). `dropAbove(0)`. No cutter.
  2. remaining `parts.size >= 2` → **happy path:** restore `g{n}` onto `boards/{id}/movie.mp4` and patch `durationSec`/`fps` from that gen (local `movie-gens/g{n}.mp4`, else `LyreCache.resolve("boards/{id}/movie.g{n}.mp4")`). **Do not** restitch leftover `parts[].src` as the happy path; **do not** pop by walking `undo/{seq}/`.
  3. **`g{n}` still missing** after local + GET (desktop 3-part movie the phone never stitched; gens wiped and grokme 404): **fallback** `LyreCutter.rebuild(remainingPartFiles, dropLast=true)` — pairwise `stitch` of remaining `parts[].src` in order, same growth as `stitchMovieBuffers`. Not the happy path; used only here. Write that CutOk onto `movie.mp4` and `push` it as `g{n}` so a later Pop hits the stack. Transformer; FGS if >15 s. Cutter throw → `videoGeneratingError = "cut_failed: pop_rebuild"`, no commit.
  4. **`pop_missing_gen` only if rebuild cannot run:** any remaining `parts[].src` fails local + `resolve` (404 / empty). Then `videoGeneratingError = "cut_failed: pop_missing_gen"`, no board commit. Do **not** use this string when `g{n}` is missing but leftover part files are present — that is the rebuild path. Stitch-then-pop of a desktop 2-part movie is **not** this case: snapshot already filled `g1`.
  - If live `movie.src` currently points at a burn (`origSrc` holds the picture compile), Pop still restores the **previous picture gen** (or rebuilds the picture movie), sets `movie.src` to that picture file, and clears `origSrc`. The burned file is not live after Pop (user re-burns). The popped clip **stays** in `videoLayers` and becomes `nextStitchTarget` again.
- Trim, extract, drag, split, generate, mute-rewrite, remove-clip: **leftovers only** (`!clipInMovie`).
- Still-only beats (no `videoSrc`) stay as **holds** after the movie; poster stretches to `durationSec`.
- `+` on the stills rail: after a leftover, or at the end. `LyreRules.insertHold` rejects indices that land inside the movie prefix (frames whose linked clips `clipInMovie`).
- Mute on Clip rewrites the leftover file with no audio stream (`stripAudioBuffer` equivalent). **Does not** create an audio rail. Dual-write `videoMuted` + clip volume unchanged.
- Loop is player-only (`ui.loopClip` / `LyreStore.loopClip`) on the leftover under the playhead.

#### Golden JSON fixtures (`LyreRulesTest`, `src/test/resources/.../fixtures/`)

Hold `fr_hold` sits **between** movie clip A and leftover video B on the stills rail. Stitch still targets `lc_b`.

**`unstitched.json`** (resolved movie = first clip; B is stitch target):

```json
{
  "title": "Odysseus",
  "brainstorm": "",
  "scenes": [{
    "id": "sc_1", "title": "Scene 1", "book": "", "durationTargetSec": 0,
    "logline": "", "dialogue": "", "notes": "",
    "frames": [
      {"id": "fr_a", "src": "boards/lyre/frames/fr_a.jpg", "caption": "A", "durationSec": 4,
       "videoSrc": "boards/lyre/clips/lc_a.mp4", "videoDurationSec": 4, "videoFps": 24},
      {"id": "fr_hold", "src": "boards/lyre/frames/fr_hold.jpg", "caption": "Hold", "durationSec": 2},
      {"id": "fr_b", "src": "boards/lyre/frames/fr_b.jpg", "caption": "B", "durationSec": 3,
       "videoSrc": "boards/lyre/clips/lc_b.mp4", "videoDurationSec": 3, "videoFps": 24}
    ]
  }],
  "activeSceneId": "sc_1",
  "refFolders": [{"id": "lib", "name": "Library", "images": []}],
  "activeFolderId": "lib",
  "videoLayers": [{
    "id": "ly_v", "kind": "video", "name": "V",
    "clips": [
      {"id": "lc_a", "src": "boards/lyre/clips/lc_a.mp4", "name": "A", "startSec": 0,
       "durationSec": 4, "sourceDurationSec": 4, "linkedFrameId": "fr_a"},
      {"id": "lc_b", "src": "boards/lyre/clips/lc_b.mp4", "name": "B", "startSec": 6,
       "durationSec": 3, "sourceDurationSec": 3, "linkedFrameId": "fr_b"}
    ]
  }],
  "audioLayers": [],
  "libraryAudio": [],
  "libraryVideo": [],
  "movie": {
    "src": "boards/lyre/clips/lc_a.mp4",
    "durationSec": 4,
    "parts": [{"clipId": "lc_a", "src": "boards/lyre/clips/lc_a.mp4", "durationSec": 4}]
  }
}
```

Assert: `nextStitchTarget == lc_b`; `canStitchClip("lc_b")`; `!canStitchClip` on `fr_hold` (no clip); `movieClips` length 3 (A, hold, B); `frameInMovie(fr_a)`; `!frameInMovie(fr_b)`.

**`after_stitch.json`** (compiled movie; **clips still in layers**):

```json
{
  "movie": {
    "src": "boards/lyre/movie.mp4",
    "durationSec": 6.958,
    "fps": 24,
    "parts": [
      {"clipId": "lc_a", "src": "boards/lyre/clips/lc_a.mp4", "durationSec": 4},
      {"clipId": "lc_b", "src": "boards/lyre/clips/lc_b.mp4", "durationSec": 3}
    ]
  }
}
```

All `videoLayers` / `frames` from `unstitched.json` are **byte-identical**. Assert: `clipInMovie(lc_a)` and `clipInMovie(lc_b)`; `nextStitchTarget == null`; `movieProgramLayers` has one `lc_movie` block + no leftover video clips; hold still on the stills clock.

**`after_pop.json`**: same as `unstitched.json` (movie falls back to first clip `src`). `lc_b` still in `videoLayers`. Assert: `nextStitchTarget == lc_b` again.

**`unstitched_3.json` / `after_stitch_3.json` / `after_pop_3.json`** — 3-part movie (A+B+C). Hold still sits between A and B; C is a third leftover video `lc_c` (`fr_c`, 2 s, 24 fps). PR 4 asserts **parts / keys / layers membership** only:

- `after_stitch_3.json`: `parts = [lc_a, lc_b, lc_c]`; `movie.src = "boards/lyre/movie.mp4"`; `durationSec` placeholder (e.g. 8.9); all three clips **remain** in `videoLayers`; `nextStitchTarget == null`.
- `after_pop_3.json`: `parts = [lc_a, lc_b]`; `movie.src` still `"boards/lyre/movie.mp4"` (compiled 2-part picture file, **not** `lc_a.src`); `lc_c` still in `videoLayers`; `nextStitchTarget == lc_c`. PR 4 does **not** assert the 2-part duration. PR 5 restores gen `g1` bytes onto that key (or `rebuild` leftover A/B if `g1` was never snapshotted) and patches `durationSec`/`fps`.

`LyreRules.stitch` / `pop` JSON-only tests in PR 4 compare against these fixtures (`durationSec` of compiled movie is a placeholder until PR 5 probe; PR 4 asserts **parts / keys / layers membership**, not the 6.958 / 8.9). PR 5 instrumented tests assert **board** `movie.durationSec` after CutOk and after 3-part Pop.

```mermaid
sequenceDiagram
    participant U as User
    participant P as LyrePane
    participant S as LyreSession
    participant R as LyreRules
    participant C as LyreCutter
    participant G as LyreMovieGens
    participant FGS as LyreCutService
    participant Undo as LyreUndo
    U->>P: Stitch leftover L
    P->>S: requestStitch(L)
    S->>S: boardBefore = session.board
    S->>R: stitch(boardBefore, L.id)
    R-->>S: RuleResult(boardAfter, CutPlan STITCH dropLast)
    Note over S,Undo: stage undo copies; do not push yet
    S->>G: ensureCurrent(preStitch=boardBefore.movie)
    Note over G: n=boardBefore.parts.size-1 — NOT boardAfter
    alt parts.size was >= 2 and g{n} missing
        G->>G: copy picture file into g{n} (never Os.link) + enqueue storage_put movie.g{n}.mp4
        Note over G: picture = movie.src, or origSrc if live is movie.burn.mp4
        alt picture file missing
            G-->>S: false
            S->>Undo: discard staging
            S-->>P: videoGeneratingError stitch_snapshot; no overwrite
        end
    end
    alt plan longer than 15s
        S->>FGS: startForeground dataSync CHANNEL_LYRE
    end
    S->>C: stitch(pictureCompileFile, clipFile, dropLast=true)
    Note over C: input is picture compile, never movie.burn.mp4
    alt CutOk
        C-->>S: CutOk(file, durationSec, fps)
        S->>S: patch movie.src=movie.mp4, durationSec, fps; clear origSrc if it was a burn
        S->>G: push gen n=boardAfter.parts.size-1 (copy, not link)
        S->>Undo: push staged files + boardBefore/After
        S->>S: commit patched board (clips NOT deleted)
        S-->>P: rails refresh
    else CutErr / exception
        C-->>S: fail
        S->>Undo: discard staging (no undo entry)
        S->>S: Frame.videoGeneratingError; orig/live unchanged; no board commit
        Note over G: snapshot of g{n} may remain (harmless)
        S-->>P: Retry chip
    end
```

```mermaid
sequenceDiagram
    participant U as User
    participant S as LyreSession
    participant G as LyreMovieGens
    participant Cache as LyreCache
    participant C as LyreCutter
    U->>S: Pop
    S->>S: boardBefore; RuleResult remaining parts
    alt remaining.size == 1
        S->>G: dropAbove(0)
        S->>S: movie.src = remaining[0].src; commit
    else remaining.size >= 2
        S->>G: restore(n=remaining.size-1)
        alt local g{n} or resolve movie.g{n}.mp4
            G-->>S: gen file
            S->>S: copy onto movie.mp4; patch duration/fps; dropAbove(n); clear origSrc if burn; commit
        else g{n} missing
            S->>Cache: resolve every remaining parts[].src
            alt any part 404 / empty
                S-->>U: cut_failed: pop_missing_gen; no commit
            else all leftover part files present
                S->>C: rebuild(partFiles, dropLast=true)
                alt CutOk
                    C-->>S: CutOk
                    S->>G: push g{n}; write movie.mp4; commit
                else cutter fail
                    S-->>U: cut_failed: pop_rebuild; no commit
                end
            end
        end
    end
```

### Local media cache

```
filesDir/lyre/{boardId}/
  objects/{sha1(key)}.{ext}     # live files, keyed by storage path
  orig/{sha1(key)}.{ext}        # first orig for restore
  tmp/                          # cutter scratch
  movie-gens/g{n}.mp4           # picture compile for n = parts.size-1 (snapshot of current + push of new CutOk)
  movie-gens/meta.json          # [{n, partCount, durationSec, fps, key}]
  undo/{seq}/meta.json          # 100 slots — NOT the Pop implementation
  activity.jsonl                # infinite log
  board.json                    # last known BoardData (offline)
  pending/{seq}.json            # offline upload/save queue — NOT SharedPreferences
```

`LyreCache.resolve(boardId, objectKey): File?` — **lands in PR 2** (needed by PR 3 Coil/ExoPlayer):

1. Return local file if present and length > 0.
2. If online, `LyreApi.getStorage(key)` **streaming** (`Response.body.byteStream()` → `*.part` then rename). Never `body.string()` (`DiscordApi.execute` / `GbotApi.execute` / `GrokifyApi` JSON `body.string()` is the anti-pattern for mp4). HTTP **404 / empty body → `null`** (caller branches: UI “missing”, or cutter `pop_missing_gen` / `stitch_snapshot` / rebuild). Do not throw.
3. Offline miss → UI “waiting for net”; **play/cut still work** for any object already cached. Snapshot/Pop treat an offline miss the same as `null` and follow the documented fallback (copy local picture if present; else rebuild leftover parts if present; else `pop_missing_gen` / `stitch_snapshot`).

There is no other byte path. Coil’s `GrokifyApp.newImageLoader()` has **no** Bearer interceptor; grokme storage requires `gme_` (never on device). PR 3 therefore loads Coil stills and ExoPlayer items only from `resolve()` local `File`s. `putStorage` stays with the cutter in **PR 5**.

Object keys match the grokme bucket, e.g. `boards/{boardId}/frames/{frameId}.jpg`, `boards/{boardId}/clips/{clipId}.mp4`, `boards/{boardId}/movie.mp4`, `boards/{boardId}/movie.g{n}.mp4` (picture-compile gens; not a BoardData key), `boards/{boardId}/orig/...`. Phone never invents a second namespace.

**Pending queue (crash-safe, not prefs).** `DiscordStore`-style `SharedPreferences` is chrome only (`lyre_prefs`: project id, chip, muse, loop, playhead). Board JSON + waveforms exceed prefs (~1 MB). Video bytes cannot go in prefs.

```json
{
  "seq": 17,
  "type": "save_board",
  "boardSnapshot": "board.json",
  "createdAtMs": 0
}
```

```json
{
  "seq": 18,
  "type": "storage_put",
  "key": "boards/lyre/clips/lc_b.mp4",
  "localPath": "objects/….mp4"
}
```

```json
{
  "seq": 19,
  "type": "publish",
  "key": "public/watch/{token}.mp4",
  "localPath": "objects/….mp4"
}
```

Op types: **`save_board` | `storage_put` | `publish` only**. Generate/publish **calls** require net — queue the **upload/save**, never the Imagine HTTP. FIFO flush on reconnect / process start; last-write-wins still applies (latest `save_board` snapshot wins; skip older snapshots for the same board). Crash recovery: leftover `pending/*.json` with a live `localPath` retry; give up after 5 failures and record an Activity line (not a Frame key).

**Disk budget:** 2 GiB per board. Evict cached objects that are not live `src` / `origSrc` / undo-referenced / **movie-gens** / **movie.g{n}.mp4**, LRU. Picture-compile gens are **not** LRU-evicted (Pop of 3+ parts needs them). Undo origs count toward the cap; drop oldest undo entries first (already capped at 100).

### LyreSession + process lifetime

```kotlin
class LyreSession(app: Application) {
    val board: StateFlow<BoardData?>
    val busy: StateFlow<LyreJob?>          // STITCH, TRIM, IMAGINE, …
    fun apply(result: RuleResult)          // stage undo → STITCH ensureCurrent(boardBefore) → cutter/restore/rebuild → patch movie → commit
    fun flushSave()                        // debounce fire now
    fun enqueuePending(op: PendingOp)
}
```

Constructed once from `GrokifyApp` / `applicationContext`, **not** `remember { }` in `LyrePane`. Compose `rememberCoroutineScope()` **cancels on leave**; Transformer jobs are 90s/180s.

Jobs with expected wall time **> ~15s** (stitch, burn-audio, long trim, split of long clips): start `LyreCutService` as a foreground service with `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` (permission already in `AndroidManifest.xml` line 42; twin is `GbotWatchService` at lines 392–395). Notification channel is **`GrokifyApp.CHANNEL_LYRE`** (created in `GrokifyApp.createChannels()`, **PR 5** — see Permissions). Text: “LYRE is cutting…”. Tap: `WidgetNav.openPluginIntent(this, BuiltinPluginCatalog.LYRE)` (same pattern as `GbotWatch` / `GbotWatchService.startAsForeground`). `stopForeground` + `stopSelf` when the job finishes or fails. API 35: override `Service.onTimeout(int, int)` and `stopSelf()` within a few seconds (dataSync 6 h/24 h cap; 180 s jobs will not hit it; omitting the override ANRs if the cap ever fires). Leaving the pane **must not** abort the cut; `LyrePane` rebinds to `busy` on return.

**`LyreSession.apply` contract (PR 5, non-negotiable):**

`boardBefore` = current `LyreSession.board` (pre-mutation). `RuleResult.board` is JSON-only (`parts` already appended on stitch / dropped on pop). **Never** pass `result.board.movie` into `ensureCurrent` — that object’s `parts.size-1` is the *new* gen slot, so a snapshot there would be overwritten by `push`.

Live-file replacement is always `tmp/` → `Files.move(..., REPLACE_EXISTING)` onto the `objects/` path so undo `Os.link` copies keep the old inode. **Gens never `Os.link` to live `movie.mp4` / `movie.burn.mp4`** (copy only) — a hardlink would make `g{n}` track the next overwrite or a Pop restore.

1. Stage copies of about-to-change live files into `undo/{seq}/`. **Do not** push the undo entry yet.
2. **STITCH only — snapshot before overwrite.** `ok = LyreMovieGens.ensureCurrent(preStitch = boardBefore.movie, boardId)`.
   - `n = boardBefore.movie.parts.size - 1`. No-op (`true`) when `parts.size < 2` (first stitch A+B) or `has(n)` (local `movie-gens/g{n}.mp4` length > 0) or `resolve("boards/{id}/movie.g{n}.mp4")` adopts an uploaded gen.
   - Else **copy** (not link) the current **picture** file into that gen slot, probe `durationSec`/`fps` into `movie-gens/meta.json`, enqueue `storage_put` of `boards/{id}/movie.g{n}.mp4`. Picture file = `movie.src` when it is the picture compile; = `origSrc` when live `movie.src` is `boards/{id}/movie.burn.mp4`. Local copy is required for stitch-then-pop; if `storage_put` is offline, enqueue pending — do **not** fail the stitch for the upload.
   - If a snapshot was required and the picture file cannot be resolved (404 / empty / live is burn with blank `origSrc`): return `false`. Session discards staging, sets `videoGeneratingError = "cut_failed: stitch_snapshot"`, **does not** run the cutter, **does not** overwrite `movie.mp4`, no commit.
3. **Bytes for this `CutPlan`:**
   - **STITCH / TRIM / MUTE / SPLIT / EXTRACT / BURN_AUDIO:** Transformer (FGS if >15 s). STITCH input file = `cache.resolve(pictureCompileKey(boardBefore.movie))` — the **picture** compile, **never** `movie.burn.mp4`.
   - **POP, remaining `parts.size == 1`:** no cutter. `movie.src` = remaining[0].`src`; duration/fps from that clip; `dropAbove(0)`; clear `origSrc` if live was a burn.
   - **POP, remaining `parts.size >= 2`:** `n = remaining.size - 1`. `LyreMovieGens.restore(n)` = local gen, else `resolve("boards/{id}/movie.g{n}.mp4")`. If found: **copy** gen onto `movie.mp4` (copy, not link); patch `durationSec`/`fps` from gen meta or probe; `dropAbove(n)`; clear `origSrc` if burn. No FGS. If missing: step 3b.
4. **POP rebuild fallback (only when `g{n}` is still missing after local + GET):** resolve **every** remaining `parts[].src`.
   - If **any** leftover part file is missing (404 / empty): `videoGeneratingError = "cut_failed: pop_missing_gen"`, discard staging, **no commit**. This is the **only** `pop_missing_gen` path. Stitch-then-pop of a desktop 2-part movie must not land here (`ensureCurrent` already filled `g1`).
   - Else `LyreCutter.rebuild(remainingPartFiles, dropLast=true)` (Transformer; FGS if >15 s). `CutOk`: write onto `movie.mp4`, `LyreMovieGens.push(n, cut)` (copy + `storage_put movie.g{n}.mp4`), patch duration/fps, `dropAbove(n)`, clear `origSrc` if burn. Cutter throw/timeout: `cut_failed: pop_rebuild`, no commit.
5. **Success patch** (before save; not the raw `RuleResult.board` duration):
   - STITCH: `movie.src = boards/{id}/movie.mp4`; `durationSec`/`fps` from CutOk; `LyreMovieGens.push(n = result.board.movie.parts.size - 1)` of the **new** CutOk (copy, not link, + `storage_put movie.g{n}.mp4`); if `boardBefore` live src was `movie.burn.mp4`, **clear `origSrc`** (the burn does not include the new clip; user re-burns).
   - POP: as in 3 / 4 (`src` is the picture compile key or the remaining clip `src`).
   - BURN_AUDIO: `movie.origSrc` = current picture compile key; `movie.src` = `boards/{id}/movie.burn.mp4`. Does **not** push a picture gen.
   - TRIM / MUTE / SPLIT / EXTRACT: dual-write Frame + clip from CutOk.
6. Push the staged undo entry and commit the **patched** board.
7. **Failure / exception / timeout:** discard staging (no undo entry), **do not** commit `RuleResult.board`, set **`Frame.videoGeneratingError`** (reuse, no `cutError`) to a short reason (`cut_failed: stitch` / `stitch_snapshot` / `pop_missing_gen` / `pop_rebuild`) on the leftover Frame being operated on (stitch target for STITCH; popped leftover for POP), orig/live files unchanged, Activity line, Retry chip keys off that string. A successful snapshot of `g{n}` that happened before a failed stitch may stay on disk (harmless; next stitch sees the slot filled).

`LyreRules` is pure and cannot probe — `after_stitch.json` `durationSec: 6.958` is a placeholder; PR 4 does not assert it. Player item 0 is clipped to `moviePlayDuration(movie)` (`LyreMovie.moviePlayDuration`); if the committed board kept the pre-stitch duration (4 s in the fixture), ExoPlayer would play only the prefix of the new compile. PR 5 instrumented test asserts **`board.movie.durationSec`** (and `fps`) after `apply`, not only the output file length.

On `ON_STOP` / back: `flushSave()`.

### Cutter

**Decision: Media3 Transformer 1.5.1 as primary.** Add:

```kotlin
implementation("androidx.media3:media3-transformer:1.5.1")
implementation("androidx.media3:media3-effect:1.5.1")
implementation("androidx.media3:media3-muxer:1.5.1")
implementation("androidx.media3:media3-common:1.5.1")
```

Web ffmpeg (`cut-video.server.ts`) always re-encodes (`libx264 veryfast crf 18`, `aac 192k`, `yuv420p`, `+faststart`, `scale=w:h:force_original_aspect_ratio=decrease,pad=w:h:(ow-iw)/2:(oh-ih)/2:color=black`). Transformer also re-encodes. Orig files are kept for restore/undo.

| Job | Web | Phone Transformer **1.5.1** (`androidx.media3:media3-transformer:1.5.1`, pin with exoplayer 1.5.1 at `android/app/build.gradle.kts` 87–89) |
|-----|-----|------------------------|
| In/out trim rewrite | `trim=start:end` + re-encode | `ClippingConfiguration` start/end Us + `Transformer.start()` |
| Drop last frame on **compiled movie file** | `stitchMovieBuffers`: ffprobe `-count_frames`; `trim=end_frame=frames-1` (or `keepSec`) | See [Drop-last frame count](#drop-last-frame-count) below. Clip end Us to `(frames-1)/fps` (or `keepSec`). **Not** `lastFrameTime(LayerClip)` (`clip.ts` leftover presentation). |
| Stitch concat | `filter_complex` concat n=2, drop last on input 0, scale/pad black | `EditedMediaItemSequence`: movie (clipped) + leftover; `Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)` — exists in `media3-effect` 1.5.1 (letterbox/pillarbox); matches web decrease+pad, **not** `LAYOUT_SCALE_TO_FIT_WITH_CROP` |
| Split at playhead | two trims | two trim exports; still copied to both leftover beats; dual-write two frames + two clips |
| Mute | `-an` copy then encode fallback | `EditedMediaItem.Builder.setRemoveAudio(true)`; try muxer copy first |
| Extract clip sound | (web audioClips) | Transformer audio-only export → new `audioLayers` clip |
| Burn-audio mix | (not in inspect snippet) | See [Burn-audio 1.5.1 gap API](#burn-audio-151-gap-api) below. **One audio sequence per bed.** Do **not** call `addGapUs` (that identifier does not exist on 1.5.1). Do **not** `addGap` on a video sequence (1.5.1: “Gaps are only supported in sequences of audio”). Do **not** set a composition `startUs` on `EditedMediaItem`. Do **not** construct `DefaultAudioMixer` — Transformer mixes the sequences. |
| Hold (still-only) | durationSec on frame | Clock/Coil only; **no** movie rewrite |
| Pop 3+ parts | (not in inspect `movie.ts` / `cut-video.server.ts`) | Happy path: file restore from `LyreMovieGens` (no Transformer). Fallback only: `rebuild(remaining part files, dropLast=true)`. |

Transformer / `EditedMediaItemSequence` / `Presentation` are `@UnstableApi` (`androidx.media3.common.util.UnstableApi`). Host `:app` currently has **no** UnstableApi opt-in (`android/app/build.gradle.kts` `kotlinOptions` is only `jvmTarget = "17"`; Discord uses stable ExoPlayer bits). **PR 5** adds:

```kotlin
kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
}
```

and `@file:OptIn(UnstableApi::class)` on `LyreCutter.kt` + `LyreCutterInstrumentedTest.kt`.

Probe fps / duration Us / hasAudio / width / height with `MediaMetadataRetriever` + `MediaExtractor`. If fps ≤ 1, assume 24 (web `frameFps`). Frame **count** is the drop-last subsection — `MediaExtractor` has no frame-count getter.

#### Drop-last frame count

`MediaExtractor` has **no** `getFrameCount()`. `stitchMovieBuffers` uses ffprobe `-count_frames`. Phone probe:

```kotlin
fun videoFrameCount(file: File): Int? {
    if (Build.VERSION.SDK_INT >= 28) { // METADATA_KEY_VIDEO_FRAME_COUNT is API 28+; minSdk is 26
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()?.takeIf { it > 0 }
                ?.let { return it }
        }
    }
    // API 26–27, and API 28+ if the metadata key is missing: count video-track samples.
    val ex = MediaExtractor()
    try {
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { i ->
            ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return null
        ex.selectTrack(track)
        var n = 0
        while (true) {
            if (ex.sampleTrackIndex == track) n++
            if (!ex.advance()) break
        }
        return n.takeIf { it > 0 }
    } finally {
        ex.release()
    }
}
```

Clip end Us = `((frames - 1).toDouble() / fps * 1_000_000.0).toLong()`. Last resort if both probes fail: `durationUs - (1_000_000L / fps.roundToLong())` — this is the existing **Med** risk (time-based drop ≠ ffmpeg `end_frame=N-1`). Instrumented 24 fps committed fixtures (`clip_10f_24fps.mp4`, `clip_3f_24fps.mp4`) stay the gate.

#### Burn-audio 1.5.1 gap API

Pinned source: `androidx/media` tag **1.5.1** `EditedMediaItemSequence.java` `Builder.addGap(long durationUs)` — javadoc says “milliseconds”; the value is passed to `EditedMediaItem.Builder.setDurationUs`, so callers pass **microseconds**. Class javadoc: “Gaps are only supported in sequences of audio.”

```kotlin
// Per bed. startSec=1.5 → 1_500_000 Us. Audio sequence only.
val bedItem = EditedMediaItem.Builder(MediaItem.fromUri(bed.file.toUri()))
    .setRemoveVideo(true)
    .build()
val audioSeq = EditedMediaItemSequence.Builder()
    .addGap((bed.startSec * 1_000_000L).toLong())
    .addItem(bedItem)
    .build()

val videoSeq = EditedMediaItemSequence.Builder()
    .addItem(videoItem) // no addGap on this sequence
    .build()

val composition = Composition.Builder(videoSeq, *audioSeqs.toTypedArray()).build()
```

`Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)` stays on the video items.

#### Picture-compile generation stack

Pick **(a)** as the happy path: a stack of compiled **picture** movie files, one per stitch. Do **not** use `undo/{seq}/` as Pop (a later leftover trim/mute still leaves the 3-part file as live; `BoardMovie.origSrc` is a single burn-audio slot, not a stack). Do **not** pairwise-`rebuild(partFiles)` as the happy path (re-encode drift vs the file stitch just produced). `rebuild` exists **only** as the Pop fallback when `g{n}` was never snapshotted (desktop 3-part movie, phone never stitched).

`LyreMovieGens` (local, **not** a BoardData key — phone-only Frame/movie keys are forbidden):

```
movie-gens/meta.json:
{ "generations": [
    { "n": 1, "partCount": 2, "durationSec": 6.958, "fps": 24,
      "key": "boards/lyre/movie.g1.mp4" }
]}
```

`has(n)` is `movie-gens/g{n}.mp4` exists **and** `length > 0`. Meta-only does not count. `copyIntoGen` / `push` / `restore` onto live `movie.mp4` are **byte copies** (`File.copyTo` / channel transfer into a tmp then `Files.move`). **Never `Os.link`** a gen to or from live `movie.mp4` / `movie.burn.mp4` — stitch overwrites the live key, and Pop copies a gen back over it.

**Picture-compile key** (do not snapshot a burn file):

```kotlin
fun pictureCompileKey(movie: BoardMovie): String? =
    when {
        movie.src.endsWith("/movie.burn.mp4") ->
            movie.origSrc?.takeIf { it.isNotBlank() }   // null → stitch_snapshot fail
        else -> movie.src.takeIf { it.isNotBlank() }
    }
```

**Snapshot before overwrite** — required so Odysseus stitch-then-pop of a desktop 2-part movie does not 404. Desktop / inspect `movie.ts` / `cut-video.server.ts` never write `movie.g{n}.mp4`. `LyreCache.resolve` on a cutter/snapshot/pop path: local hit → File; online GET 200 → File; online GET 404 / empty → `null`; do not treat a 404 as “waiting for net”.

```kotlin
/**
 * Call on STITCH after undo staging, **before** Transformer may replace movie.mp4.
 * [preStitch] is LyreSession.board.movie — NOT RuleResult.board.movie.
 * n = preStitch.parts.size - 1  → g1 for a 2-part compile.
 * RuleResult already appended the new part; using it would snapshot into g{n+1}
 * and push would overwrite that slot with the 3-part CutOk (the Odysseus hole).
 */
suspend fun LyreMovieGens.ensureCurrent(preStitch: BoardMovie, boardId: String): Boolean {
    if (preStitch.parts.size < 2) return true          // first stitch A+B: no previous compile
    val n = preStitch.parts.size - 1
    if (has(n)) return true
    cache.resolve(boardId, "boards/$boardId/movie.g$n.mp4")
        ?.takeIf { it.length() > 0 }
        ?.let { uploaded ->
            copyIntoGen(n, uploaded)                   // adopt a gen another phone uploaded
            return true
        }
    val pictureKey = pictureCompileKey(preStitch) ?: return false
    val picture = cache.resolve(boardId, pictureKey)?.takeIf { it.length() > 0 } ?: return false
    copyIntoGen(n, picture)                            // real copy into movie-gens/g{n}.mp4 + probe
    enqueuePending(storage_put of "boards/$boardId/movie.g$n.mp4")
    return true
}

suspend fun LyreMovieGens.restore(n: Int, boardId: String): File? {
    if (has(n)) return genFile(n)
    val uploaded = cache.resolve(boardId, "boards/$boardId/movie.g$n.mp4")
        ?.takeIf { it.length() > 0 } ?: return null
    copyIntoGen(n, uploaded)
    return genFile(n)
}

/** Delete local g{k} for k > n (n=0 drops all). Local only — pending ops stay `save_board`/`storage_put`/`publish`. Stale remote `movie.g{k}.mp4` is overwritten on the next push of that key. */
fun dropAbove(n: Int)
```

| Event | Picture stack | Live `movie.src` | `origSrc` |
|-------|---------------|------------------|-----------|
| Stitch A+B (2 parts) | no snapshot (`boardBefore.parts.size` was 1); push `g1` = CutOk; `storage_put` `movie.g1.mp4` | `boards/{id}/movie.mp4` (same bytes) | unchanged |
| Stitch +C onto a 2-part compile **with** `g1` | snapshot skipped (slot filled); push `g2`; `storage_put` `movie.g2.mp4` | `movie.mp4` (3-part picture) | unchanged |
| Stitch +C onto a **desktop** 2-part compile **without** `g1` | **snapshot** current picture `movie.mp4` → `g1` **before** overwrite (`ensureCurrent(boardBefore)`); then push `g2` | 3-part picture | unchanged |
| Stitch +C while live is **burn** and `g1` missing | snapshot **`origSrc`** (picture) → `g1` before overwrite; concat **picture+C** (not the burn); push `g2` | `movie.mp4` (3-part picture) | **clear** — burn does not include C; user re-burns |
| Pop of 3-part (drop C) | restore `g1` bytes onto `movie.mp4`; `dropAbove(1)` (drops `g2`) | picture 2-part compile | if it was a burn, **clear** it — Pop rewrites the picture movie, not the burned one |
| Pop of 2-part (drop B) | `dropAbove(0)` (drops `g1`); no compiled file | first remaining clip `src` (`lc_a.mp4`) | clear if set |
| Pop of desktop 3-part, phone never stitched (`g1` missing, leftover A/B files present) | **fallback** `rebuild([lc_a, lc_b] files, dropLast=true)`; write CutOk as `g1` + live `movie.mp4` | rebuilt 2-part picture | clear if burn |
| Pop of desktop 3-part, `g1` missing **and** leftover part files missing | no rebuild; `cut_failed: pop_missing_gen`; **no commit** | unchanged | unchanged |
| Burn-audio | **no** gen push | `boards/{id}/movie.burn.mp4` (new CutOk) | picture compile key (`movie.mp4`) |

Happy-path Pop of n>2 is a **file copy + probe/meta duration patch**, not a 180 s Transformer job, so no FGS. If `g{n}` is missing locally, `LyreCache.resolve` the uploaded `movie.g{n}.mp4`. A missing gen after a phone stitch of that compile is unexpected (snapshot should have filled it) but Pop **still** runs the same fallback so the user is not stuck — `rebuild`, not `pop_missing_gen`. `pop_missing_gen` remains **only** when rebuild cannot run (any remaining `parts[].src` 404 / empty).

**Odysseus stitch-then-pop (the hole this round closes):**

```mermaid
flowchart LR
  D["Desktop A+B compile movie.mp4, no movie.g1.mp4"] --> S["Phone STITCH C"]
  S --> Snap["ensureCurrent(boardBefore): copy movie.mp4 → g1 + storage_put movie.g1.mp4"]
  Snap --> Cut["Transformer: picture+C → movie.mp4; push g2"]
  Cut --> P["Phone Pop C"]
  P --> R["restore g1 onto movie.mp4; drop g2"]
  R --> Out["board duration/src = original 2-part compile, not pop_missing_gen"]
```

PR 5 tests: `LyreRulesTest` 3-part pop membership (`after_pop_3.json`) **and** instrumented cutter. Reuse committed `clip_10f_24fps.mp4` + `clip_3f_24fps.mp4` (C may be a second copy of the 3-frame clip). Do not add a third asset unless a distinct duration is required to tell `g1` from `g2`.

1. stitch A+B, stitch C, Pop C → **board** `movie.durationSec`/`fps`/`src` match `g1` (not `g2`, not `lc_a.src`).
2. **Start from a 2-part compile without `g1`** (seed `after_stitch` board + compiled `movie.mp4`; do **not** go through phone stitch A+B, or delete `g1` after it). Probe the seeded 2-part file. Stitch C, Pop C → board `movie.durationSec`/`fps` match that probe (ε ≤ 1 frame), `movie.src` stays `boards/{id}/movie.mp4`, `videoGeneratingError` is **not** `pop_missing_gen`. Assert `has(1)` after the stitch (snapshot ran) and after the pop (restore used it).
3. **Cold desktop 3-part movie, no gens, leftover A/B files present, Pop C** → rebuild path; board `movie.src` is the compiled key; duration ≈ pairwise `stitch(A,B,dropLast)` of the fixtures; **not** `pop_missing_gen`.
4. **Cold 3-part, no `g1`, leftover `parts[].src` 404** → `cut_failed: pop_missing_gen`, board JSON + live `movie.mp4` unchanged, no undo entry.

**Failure policy:** cutter exceptions leave orig/live untouched, set `videoGeneratingError` on the leftover Frame, Activity line, UI retry.

**Why not ffmpeg-kit by default**

- APK size: full ffmpeg-kit is tens of MB; phone already bundles `libadb.so` for Watch Deploy.
- GPL vs host Apache-style deps.
- Transformer covers the job list **if** we re-encode (web does too) and drop-last is file-probe `(frames-1)/fps`.

**Fallback (only if instrumented stitch **or** burn-audio tests fail A/V sync on sample buffers):** bundle a small LGPL ffmpeg as `jniLibs/arm64-v8a/libffmpeg.so` and exec in place from `nativeLibraryDir` — same noexec workaround as `WatchAdbClient` (`useLegacyPackaging = true` already). Do **not** add `ffmpeg-kit-full` from Maven in v1. Gate with `LyreCutter.useFfmpegFallback`.

Interface:

```kotlin
interface LyreCutter {
    suspend fun probe(file: File): Probe
    suspend fun trim(input: File, startSec: Double, endSec: Double, fpsHint: Double?): CutOk
    suspend fun stitch(movie: File?, clip: File, dropLast: Boolean, keepSec: Double?): CutOk
    suspend fun mute(input: File): CutOk
    suspend fun extractAudio(input: File): CutOk
    suspend fun split(input: File, atSec: Double): Pair<CutOk, CutOk>
    suspend fun burnAudio(video: File, beds: List<AudioBed>): CutOk
    /**
     * Pop fallback only. Pairwise stitch remaining part files with dropLast,
     * same growth as stitchMovieBuffers. Do not call on the happy path.
     *
     * require(partFiles.size >= 2)
     * acc = stitch(partFiles[0], partFiles[1], dropLast, keepSec=null)
     * for i in 2 until size: acc = stitch(acc.file, partFiles[i], dropLast, keepSec=null)
     * return acc
     * Timeout 180s per pairwise stitch (same as stitch).
     */
    suspend fun rebuild(partFiles: List<File>, dropLast: Boolean): CutOk
}
data class AudioBed(val file: File, val startSec: Double, val durationSec: Double)
data class CutOk(val file: File, val durationSec: Double, val fps: Double)
```

Scratch files in `tmp/`; on success move to `objects/` and enqueue `storage_put`. Timeouts: trim/mute 90s, stitch/burn 180s (match web `run(..., 180000)`).

**Burn-audio spike is in PR 5 instrumented tests**, not deferred to PR 7: two beds, one with `startSec = 1.5`, using `addGap((1.5 * 1_000_000L).toLong())` on **audio** sequences. If that mix fails A/V sync, defer burn-audio UI (same gate as stitch). PR 7 only ships the chip if that test is green.

### Undo stack

Undo is **file swap + JSON patch**, not re-encode. Covers the last **100** editor actions: trim, drop-frame stitch, remove, restore, extract, stitch, unstitch (pop), burn-audio. Generate is **not** an undo action (retry lives on `generatingError` / `videoGeneratingError`). Activity is a separate infinite log.

```kotlin
data class UndoEntry(
    val seq: Int,
    val type: UndoType,
    val atMs: Long,
    val boardBefore: String,
    val boardAfter: String,
    val files: List<FileSwap>,
)
data class FileSwap(val liveRel: String, val savedRel: String)
```

On action (`LyreSession.apply`) — order matches the [apply contract](#lyresession--process-lifetime):

1. Copy each about-to-change live file into `undo/{seq}/` (hardlink via `Os.link` when the same filesystem allows; else copy). This is **staging**, not a committed undo entry.
2. Write `meta.json` in that staging dir.
3. Run `CutPlan` if present. STITCH: `LyreMovieGens.ensureCurrent(preStitch = boardBefore.movie)` **before** the cutter overwrites `movie.mp4` (copy, not `Os.link`; abort with `stitch_snapshot` if the picture file is missing). On `CutOk`, patch `movie.src` / `durationSec` / `fps` (and `LyreMovieGens.push` of the new compile on stitch). POP: restore gen, else rebuild leftover parts, else `pop_missing_gen`. On failure: **delete** `undo/{seq}/`, do not commit, stop.
4. Push the staged entry onto the undo stack; commit the **patched** board.
5. If stack size > 100, delete `undo/{oldest}/`.

On undo: restore JSON fragment, rename saved files back over live. Redo is **not** required.

**Pop ≠ Undo.** A leftover trim/mute after A+B+C stitch leaves the 3-part picture file as live `movie.src`; the previous A+B compile is in `movie-gens/g1.mp4`, not necessarily in the latest undo slot. Walking `undo/` to implement Pop is forbidden.

Restore picture / Restore clip (product control, not the same as Undo): flip `src` ↔ `origSrc` / `videoSrc` ↔ `origVideoSrc` if `clipBackup()` is non-null — **both** Frame and clip. First destructive edit copies live → orig if orig missing.

### Activity log

`activity.jsonl`, append-only, no rotation. Record `{ts, type, projectId, sceneId, frameId, clipId, summary}`. Activity chip is infinite scroll. Tap a **recent** event (still in the undo window) to jump playhead / focus. Events older than the undo stack are **inspect-only**.

### Generate client

```mermaid
sequenceDiagram
    participant Sheet as Clip/Picture sheet
    participant Img as LyreImagineClient
    participant PHP as lyre.php
    participant Me as me.grokpot.io
    participant X as api.x.ai
    Sheet->>Img: generate still / clip
    alt still
        Note over Img: phone reads local files; base64 in JSON
        Img->>PHP: imagine_still {images:[{data,mimeType}…]}
    else video
        Img->>PHP: await storage_put poster + ref keys
        Img->>PHP: imagine_video {image_key, ref_keys}
        Note over PHP,Me: PHP GET grokme with gme_; never phone cache
    end
    PHP-->>Img: {request_id} or grokme_unavailable
    alt grokme started
        loop poll 5s
            Img->>PHP: imagine_status
            PHP->>Me: poll / harvest
            PHP-->>Img: pending or {path,url}
        end
    else grokme_unavailable / 404
        Img->>X: SpaceXAI backup (vault spacexai_api_key)
        X-->>Img: image url / video request_id
        Img->>PHP: storage_put compiled bytes
    end
    Img->>Sheet: dual-write Frame+clip; clear generatingError
```

**Stills — grokme first (exists today).** `/tmp/grokme-inspect/server.js` `writePromptFile` only inlines `img.data || img.base64` (max 4). URL-only entries are **dropped**. grokme storage GET requires `me_require_project_key()`, so the agent **cannot** `web_fetch` `boards/…` URLs.

PHP **cannot** read `filesDir/lyre/…`. Camera / gallery / generate-still write local objects and `enqueuePending(storage_put)`. If `imagine_video` ran before those **uploads** reached grokme, a server-side GET 404s and extra refs silently drop (the original grokme URL-drop bug, one hop later). `GrokifyApp.newImageLoader()` has no Bearer; grokme GET requires `gme_`.

**Stills — phone inlines bytes; PHP never reads the phone cache.**

`imagine_still` JSON body includes `images: [{ "data": "<base64>", "mimeType": "image/jpeg" }, …]` (poster + ≤3 extra refs, **4-image cap**). The **phone** reads its own cache files (or freshly captured bytes), base64-encodes, and POSTs them. PHP forwards that array to `POST /v1/chat` (`writePromptFile` only inlines `img.data || img.base64`; URL-only entries are dropped). Prompt: *Call `image_gen` / `image_edit` once. Do not search the web.* Harvest: markdown/URL in SSE `done.content`, or a later grokme `media` event. Copy result into `boards/{id}/frames/{frameId}.jpg`. Optional `/v1/imagine/image` with URLs is **best-effort** if HEAD/POST does not 404; never the only path.

**Video — await `storage_put` then PHP GET grokme.**

Generate already requires net: **do not** queue the Imagine HTTP. Before `imagine_video`, `LyreImagineClient` **awaits** `putStorage` of the poster key and every extra ref key (flush those `pending` ops to 200). Only then POST `imagine_video` with `image_key` / `ref_keys[]`. PHP fetches those keys from grokme with the server `gme_` key to build the assumed REST payload. If a required key 404s, return `{ok:false, error:"grokme_unavailable"}` rather than dropping the ref.

`imagine_still` **may** run the SSE harvest server-side with `set_time_limit(120)` (bounded; Apache `Timeout` 300). If that is too tight in practice, return `{request_id}` and let `imagine_status` finish the harvest. On timeout/fail: `{ok:false, error:"grokme_unavailable"}` so the phone uses SpaceXAI.

**Video — grokme REST is not in the inspect copies.** `/tmp/grokme-inspect/server.js` `ALLOWED_TOOLS` has no `image_to_video`. Do not block the app.

`imagine_video` **always** returns immediately:

```
POST lyre.php  { action: "imagine_video", project_id, prompt, duration, aspect, resolution,
                 image_key, ref_keys[], voice_ids[] }
→ { ok: true, request_id } | { ok: false, error: "grokme_unavailable" }
```

PHP starts assumed grokme `POST /v1/imagine/video` (or 404 → `grokme_unavailable`). **Phone** polls `GET lyre.php?action=imagine_status&request_id=` every 5s for up to 10 min. PHP is **not** on a 10 min blocking loop (Apache Timeout 300 would kill it).

Assumed grokme contract (spike in **PR 6 before wiring UI**; grokme server, not lyre repo):

```
POST /v1/imagine/video
Authorization: Bearer gme_…
{ "prompt", "duration", "aspect_ratio", "resolution",
  "image": { "url": "…" }, "reference_images": [{"url"}], "reference_audios": [{"voice_id":"eve"}] }
→ { "request_id": "…" }

GET /v1/imagine/video/{request_id}
→ { "status": "pending"|"done"|"failed"|"expired", "video": { "url", "path", "duration" } }
```

If 404, SpaceXAI is the working video path.

**SpaceXAI backup (phone, vault `spacexai_api_key` via `HostApiKeyStore` / `ApiKeyIds.SPACEXAI`).** Generate paths (verified enough to ship; poll is the guaranteed shape):

```
POST https://api.x.ai/v1/images/generations
{ "model": "grok-imagine-image", "prompt", "aspect_ratio", "n": 1 }

POST https://api.x.ai/v1/videos/generations
{
  "model": "grok-imagine-video-1.5",
  "prompt": "… <IMAGE_0> … <AUDIO_0>",
  "duration": 6, "aspect_ratio": "16:9", "resolution": "720p",
  "image": { "url": "data:image/jpeg;base64,…" },
  "reference_images": [ { "url": "…" } ],
  "reference_audios": [ { "voice_id": "eve" } ]
}
→ { "request_id" }
GET https://api.x.ai/v1/videos/{request_id}
→ { "status": "done", "video": { "url": "https://vidgen.x.ai/….mp4" } }
```

Voice ids: `GROK_VOICES` in `SpotifyLiveDj.kt`. Unknown ids 400 on xAI.

**Edit clip (unverified `POST /v1/videos/edits`).** PR 6 **spikes** grokme edit + `https://api.x.ai/v1/videos/edits` **before** wiring the chip.

- If either returns a job: new object key, orig kept, dual-write Frame+clip.
- If **404 / unknown route**: fallback = **image-to-video generate** from this still (`image` = poster) with the edit prompt; write a **new** file; keep orig. Same product (“new file; orig kept”).
- If fallback also fails: set `videoGeneratingError = "edit_unavailable"` and leave orig. Do **not** block Generate clip on this spike.

Timeouts: still harvest ≤120s server-side or poll; video poll 10 min @ 5s on the **phone**. On failure set `generatingError` / `videoGeneratingError`; Retry chip; do not touch orig.

`+` generate still / camera / gallery / blank hold: leftover-only insert (`LyreRules`). Dual-write when a clip is attached.

### Audio

- **Extract clip sound** → new `audioLayers` clip (`src` = extracted m4a, `startSec` = leftover start, `linkedFrameId` set). Mute on Clip does **not** do this.
- New beds / mic: camera (`RECORD_AUDIO`), files, Library (`libraryAudio`).
- Picture stitch = cheap video concat (Stitch). Audio rails stay separate until burn.
- **Burn-audio stitch** = `LyreCutter.burnAudio` with per-bed **audio** `EditedMediaItemSequence.Builder().addGap((startSec * 1_000_000L).toLong()).addItem(bedItem)` → new `movie.src` (`movie.burn.mp4`); picture compile kept as `origSrc`; undoable. Does **not** push `LyreMovieGens`. A later **picture** Stitch concatenates `origSrc` (not the burn), snapshots that picture into `g{n}` if missing, writes live `movie.mp4`, and **clears `origSrc`**. Pop after burn rewrites the picture movie (previous gen), not the burned file. UI in PR 7 only if PR 5 instrumented mix test is green.
- Publish copies the compiled file (`movie.src` after burn if that is the intended film; else the picture-stitched movie). Caption/Dialogue/Notes stay JSON.

### Projects + PHP API

New table `schema/010_lyre_projects.sql`, applied by `scripts/install.php` (globs `schema/*.sql`, skip ids in `schema_migrations`).

```sql
CREATE TABLE IF NOT EXISTS lyre_projects (
    id CHAR(32) NOT NULL PRIMARY KEY,
    user_id INT UNSIGNED NOT NULL,
    name VARCHAR(128) NOT NULL,
    visibility ENUM('private','public') NOT NULL DEFAULT 'private',
    board_id VARCHAR(64) NOT NULL,
    watch_token CHAR(32) NULL,
    is_odysseus TINYINT(1) NOT NULL DEFAULT 0,
    compiled_key VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_lyre_user_board (user_id, board_id),
    UNIQUE KEY uq_lyre_watch_token (watch_token),
    KEY idx_lyre_user_updated (user_id, updated_at),
    CONSTRAINT fk_lyre_projects_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO schema_migrations (id) VALUES ('010_lyre_projects');
```

Odysseus: `board_id = 'lyre'`, `is_odysseus = 1`, `name = 'Odysseus'`. **Not** deletable. Shared Postgres row with desktop LYRE (one grokme project per GrokifyOS instance).

**`GET projects` ensure (MySQL + Postgres):**

1. If no MySQL row for this user + `board_id='lyre'`, INSERT it.
2. `SELECT` LYRE Postgres `boards` where `id = 'lyre'`.
3. If the Postgres row is **missing**, `INSERT` the empty `BoardData` JSON (same document as New). Map columns in the adapter after `\d boards` (assumed `id` / `data` / `updated_at`; if live uses `payload` etc., adapter-only).
4. If Postgres connect/auth/table fails: `{ok:false, error:"lyre_pg_unavailable"}` — the pane shows that error, **not** an empty local project that would later clobber Odysseus.

Phone-only `create`: MySQL row **and** Postgres INSERT (already specified). `board_id = 'lyre_phone_' || uuid`.

`web/api/lyre.php` — `gos_require_access()` like `gbot.php` / `discord.php`. Device Bearer or session.

**`gos_lyre_pdo()`** — first pgsql connection in this codebase. `pdo_pgsql` is loaded on the host Apache/FPM. `postgresql://user:pass@host:5432/db?sslmode=require` is **not** a PDO DSN:

```php
function gos_lyre_pdo(): PDO {
    static $pdo = null;
    if (gos_pdo_alive($pdo)) {
        return $pdo;
    }
    $url = (string) (gos_env('GROKIFY_LYRE_DATABASE_URL', '') ?? '');
    $p = parse_url($url);
    if ($p === false || empty($p['host']) || empty($p['path'])) {
        throw new RuntimeException('lyre_pg_unconfigured');
    }
    $db = ltrim((string) $p['path'], '/');
    $dsn = sprintf(
        'pgsql:host=%s;port=%d;dbname=%s;sslmode=require',
        $p['host'],
        (int) ($p['port'] ?? 5432),
        $db
    );
    $user = isset($p['user']) ? urldecode($p['user']) : '';
    $pass = isset($p['pass']) ? urldecode($p['pass']) : '';
    $pdo = new PDO($dsn, $user, $pass, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    return $pdo;
}
```

Never log the URL or DSN. Spike `\d boards` in PR 2.

| Action | Method | Response | Notes |
|--------|--------|----------|-------|
| `projects` | GET | JSON | Ensure Odysseus MySQL **and** Postgres row |
| `project` | GET `id=` | JSON | |
| `create` | POST | JSON | Postgres INSERT + MySQL |
| `rename` | POST | JSON | |
| `delete` | POST | JSON | Phone-only; refuse Odysseus |
| `visibility` | POST | JSON | public copies mp4; private DELETE object; keep token |
| `board` | GET | JSON | SELECT boards JSON |
| `save_board` | POST | JSON | last-write-wins UPDATE |
| `storage_get` | GET `action=storage_get&key=` | **raw bytes** on 200; JSON only on error | `header_remove('Content-Type')` like `media-cache.php` GET. Stream grokme → client. Never `file_get_contents` the object. **PR 2.** |
| `storage_put` | **POST** `action=storage_put&key=` raw body | JSON metadata after stream | **Not PUT.** Host siblings 405 anything that is not GET/POST (`gbot.php` 602–604, `discord.php` 903–905). `_common.php` 11–13 forces `Content-Type: application/json`; `_common.php` 29–36 `gos_json_body()` is `file_get_contents('php://input')` and would consume the mp4. Branch **method+action before** `gos_json_body()` / the POST-only 405. Spool `php://input` in 1 MiB chunks to `tmpfile()`, `rewind`, curl `CURLOPT_INFILE` **that handle** (php://input is not seekable; libcurl rewinds on 401/redirect). Never `file_get_contents`. grokme `v1.php` 166 accepts PUT **or** POST; host `media-cache.php` already POSTs raw bodies. Apache vhost **Timeout 300** still caps the hop (streaming does not help a 512 MB mobile upload) — fail with typed `storage_put_timeout` / HTTP 504 rather than a hung FPM worker. **PR 5.** Key stays query-string (allowlist as written). |
| `storage_delete` | POST | JSON | |
| `imagine_still` | POST | JSON `{request_id}` or `{url,path}` or `grokme_unavailable` | SSE harvest ≤120s **or** request_id + poll |
| `imagine_video` | POST | JSON `{request_id}` or `grokme_unavailable` | Immediate. Phone polls. |
| `imagine_status` | GET `request_id=` | JSON pending/done/failed | PHP GET grokme; no long block |
| `publish` | POST | JSON | copy compiled to `public/watch/{token}.mp4` |

Key allowlist: `^boards/[A-Za-z0-9_./-]+$` and `^public/watch/[a-f0-9]{32}\.mp4$`.

Env (`.env.example`, never shipped to the APK):

```
GROKIFY_LYRE_DATABASE_URL=postgresql://…@db.grokpot.io:5432/…?sslmode=require
GROKIFY_LYRE_ME_API_KEY=gme_…
GROKIFY_LYRE_ME_API_BASE=https://me.grokpot.io/v1
GROKIFY_LYRE_ME_STORAGE_BASE=https://me.grokpot.io/v1/storage
GROKIFY_LYRE_ODYSSEUS_BOARD_ID=lyre
```

**`LyreApi` is not a DiscordApi clone.** Two OkHttp clients:

```kotlin
class LyreApi(private val tokenProvider: () -> String?) {
    private val jsonClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // still SSE harvest
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    fun projects(): JSONObject
    fun saveBoard(id: String, data: JSONObject): JSONObject
    fun startImagineStill(body: JSONObject): JSONObject
    fun startImagineVideo(body: JSONObject): JSONObject
    fun imagineStatus(requestId: String): JSONObject

    /** Raw 200 body. Caller must close. JSON error body if !isSuccessful. PR 2. */
    fun getStorage(key: String): Response
    /** POST raw body. Query action=storage_put&key= (URLEncoder). PR 5. */
    fun putStorage(key: String, file: File): JSONObject
}
```

Key encoding (both get and put): `URLEncoder.encode(key, "UTF-8")` so slashes in `boards/lyre/clips/lc_b.mp4` survive as `%2F` in the query (same helper as `DiscordApi.enc`). PHP `$_GET['key']` urldecodes; allowlist runs on the decoded value.

```kotlin
fun getStorage(key: String): Response {
    val encoded = URLEncoder.encode(key, "UTF-8")
    val req = auth("/lyre.php?action=storage_get&key=$encoded").get().build()
    return streamClient.newCall(req).execute() // caller closes; never body.string()
}

fun putStorage(key: String, file: File): JSONObject {
    val encoded = URLEncoder.encode(key, "UTF-8")
    val body = file.asRequestBody(mime.toMediaType())
    val req = auth("/lyre.php?action=storage_put&key=$encoded").post(body).build()
    return streamClient.newCall(req).execute().use { parseJsonMetadata(it) }
}
```

**`lyre.php` method+action branch (PR 2 skeleton; PR 5 fills put).** Mirror `discord.php` 865–905 (GET actions, then POST-only 405, then `gos_json_body()`), with **raw** exceptions **first**:

```php
$httpMethod = strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$qsAction = strtolower(trim((string) ($_GET['action'] ?? '')));

if ($httpMethod === 'GET' && $qsAction === 'storage_get') {
    gos_require_access();
    gos_lyre_storage_get((string) ($_GET['key'] ?? '')); // header_remove; stream; exit
}
if ($httpMethod === 'POST' && $qsAction === 'storage_put') { // PR 5
    gos_require_access();
    gos_lyre_storage_put((string) ($_GET['key'] ?? '')); // spool + curl INFILE; JSON exit
}
if ($httpMethod === 'GET') {
    // JSON GET: projects, board, imagine_status, …
}
if ($httpMethod !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}
$body = gos_json_body(); // JSON writes only — never on the mp4 path
$action = strtolower(trim((string) ($body['action'] ?? '')));
```

`gos_lyre_storage_put` spool (never `file_get_contents('php://input')`):

```php
$tmp = tmpfile();
$in = fopen('php://input', 'rb');
if ($tmp === false || $in === false) {
    gos_api_json(['ok' => false, 'error' => 'storage_put_spool'], 500);
}
stream_copy_to_stream($in, $tmp); // chunked
fclose($in);
rewind($tmp);
$size = (int) (fstat($tmp)['size'] ?? 0);
// CURLOPT_INFILE $tmp, CURLOPT_INFILESIZE $size, POST to grokme /v1/storage/{key}
```

### Public watch

Storage key: `public/watch/{token}.mp4` in the **same** grokme bucket. Token = `bin2hex(random_bytes(16))` (`[a-f0-9]{32}`).

**Grokme patch (not a GrokifyOS PR file; parallel deploy, repo `/root/grokme`).** Today `v1.php` calls `me_require_project_key()` **before** storage routes; GET with empty rel or `?list=` lists **all** keys. A naive “`$rel` starts with `public/`” skip would leak `GET /v1/storage/public/watch?list=`.

Exact rule, **before** `me_require_project_key()`:

```php
$rel = /* storage key */;
$method = $_SERVER['REQUEST_METHOD'] ?? '';
if (
    $method === 'GET'
    && preg_match('#^public/watch/[a-f0-9]{32}\.mp4$#', $rel)
    && is_file($full = me_resolve_storage_path($slug, $rel))
) {
    header_remove('Content-Type');
    header('Content-Type: video/mp4');
    header('Content-Length: ' . filesize($full));
    header('Cache-Control: public, max-age=60');
    header('X-Content-Type-Options: nosniff');
    readfile($full); // or streamed fread
    exit;
}
// listing, PUT, DELETE, any other public/ path → still require project key
```

**Until that grokme deploy is live**, public QR would 401. Temporary GrokifyOS unauth GET:

`web/api/lyre-watch.php` — **no** Bearer. Query `token` must match `^[a-f0-9]{32}$`. Lookup `lyre_projects` where `watch_token=? AND visibility='public'`. Stream the object from grokme using the **server** `ME_API_KEY` (`CURLOPT_FILE`). 404 if private/missing.

UI: `watch_url_proxy = https://grokifyos.grokpot.io/api/lyre-watch.php?token=` always works after Public. `watch_url_grokme = https://me.grokpot.io/v1/storage/public/watch/{token}.mp4` used when a HEAD/GET of that URL returns 200 (HEAD may still 401 — treat 401 as “use proxy”). Product URL on me.grokpot.io can wait; do **not** block the Public chip.

Private: PHP `DELETE /v1/storage/public/watch/{token}.mp4`. Storyboard JSON and `boards/**` stay auth-only.

QR: `com.google.zxing:core:3.5.3`. Native ExoPlayer with `DefaultDataSource` (https).

### Muse

Not a chip. App-bar button opens `LyreMuseSheet`.

```kotlin
val title = INTERNAL_SESSION_TITLE_PREFIX + " LYRE Muse"  // "· LYRE Muse"
HostAiClient.complete(
    ctx,
    prompt = userText,
    optionsJson = JSONObject()
        .put("session_title", title)
        .put("system", museSystem + railsDigest)
        .toString(),
)
```

Must run on `Dispatchers.IO` (`complete` is blocking, `CountDownLatch`, 300s) from `LyreSession`, not the Compose scope.

`isInternalAppSessionTitle` in `HostAiClient.kt` + `GrokifyViewModel` already hides titles starting with `·` or `•`. Do **not** copy Grok Assistant’s `"Grok Assistant"` title (missing prefix).

Rails digest (cap ~6k chars): project name, scene titles, focused leftover caption/dialogue/notes, movie duration, leftover count, last 8 activity lines. No binary media.

Persist `ui.museMessages` only if already present from web; phone also keeps a local transcript in `LyreStore` capped at 100.

### Permissions

Manifest already has CAMERA, RECORD_AUDIO, READ_MEDIA_* (`AndroidManifest.xml` 26–34). `AppPermissionId.CAMERA` / `MICROPHONE` / `MEDIA` already exist. LYRE does not add a new group.

If missing at camera / gallery / mic: `onRequestPermissions` → `GrokifyViewModel.ensurePermissions`. FileProvider already exposes `files-path` (`res/xml/file_paths.xml`) for capture URIs (`${applicationId}.fileprovider`).

PR 5 adds:

```xml
<service
    android:name=".apps.lyre.LyreCutService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

and **`GrokifyApp.CHANNEL_LYRE`** in `createChannels()` (today the function is `GrokifyApp.kt` 37–161; companion constants 195–205; **no** LYRE channel — `GbotWatch` uses `CHANNEL_GBOT`). `startForeground` with an unknown channel id throws on API 26+ (`minSdk` 26) and the cut dies as soon as the user leaves the pane.

```kotlin
// GrokifyApp.kt companion + createChannels()
const val CHANNEL_LYRE = "grokify_lyre"

nm.createNotificationChannel(
    NotificationChannel(
        CHANNEL_LYRE,
        getString(R.string.notification_channel_lyre),
        NotificationManager.IMPORTANCE_LOW, // ongoing FGS; not an alert (Gbot is HIGH)
    ).apply {
        description = getString(R.string.notification_channel_lyre_desc)
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
)
```

Strings (alongside `notification_channel_gbot` in `res/values/strings.xml`): name “LYRE”, desc “Ongoing notification while LYRE is cutting a clip.” `LyreCutService` builds with `GrokifyApp.CHANNEL_LYRE`, `setContentTitle("LYRE is cutting…")`, `setContentIntent(PendingIntent.getActivity(this, id, WidgetNav.openPluginIntent(this, BuiltinPluginCatalog.LYRE), FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE))` — same as `GbotWatch.kt` 544–548 / 695–699. Optional API 35: `override fun onTimeout(startId: Int, fgsType: Int) { /* fail the job; */ stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }`. `dataSync` is the right type (`targetSdk` 35; Android 15 lists local file processing under dataSync; host already uses it on `GbotWatchService`).

### Player / clock

**Do not clone `rememberDiscordPlayer`.** That helper (`DiscordMedia.kt`) builds `ProgressiveMediaSource` on `DefaultHttpDataSource.Factory` only — `file://` cache URIs fail.

`LyrePlayer` uses one `ExoPlayer`:

```kotlin
val dataSource = DefaultDataSource.Factory(ctx)   // file + http + asset
val itemFactory = ProgressiveMediaSource.Factory(dataSource)
```

**Program (concatenating items, not one flattened mp4 at play time):**

1. Compiled movie (`resolvedMovie.src`) clipped to `moviePlayDuration` (`ClippingMediaSource` / `MediaItem` clipping).
2. Leftover **video** clips in `orderedVideoClips` order whose ids are **not** `clipInMovie`.

`ConcatenatingMediaSource` / `ConcatenatingMediaSource2` or `player.setMediaItems(...)`. Holds have **no** video item; the stills clock still includes them.

**Two clocks:**

| | Stills clock (UI) | Movie file time |
|--|-------------------|-----------------|
| Duration | `LyreClip.movieDuration(scenes)` = sum of `clipLength` (holds included) | `moviePlayDuration` + leftover clip durations |
| Seek | `clipAtTime(movieClips(scenes), t)` | If `frameInMovie`, map via `moviePartDurations(partOriginals, moviePlayDuration)` into item 0; else offset inside the leftover item |

Drop-frame stitch makes compiled `movie.src` **shorter** than the sum of stitched `durationSec`s (one frame per join). **Acceptable drift** vs the stills clock — do not stretch the compiled file to match holds. Playhead on a hold shows the poster and does not seek the ExoPlayer into a ghost segment.

Loop: `player.repeatMode = REPEAT_MODE_ONE` only while the focused leftover is the current concat item and `loopClip` is on.

### Gradle / tests / OTA

`android/app/build.gradle.kts` in **PR 5**:

```kotlin
defaultConfig {
    // existing fields…
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
}

dependencies {
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-muxer:1.5.1")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

PR 7 adds `implementation("com.google.zxing:core:3.5.3")`. **Do not** add ffmpeg-kit unless the fallback is proven necessary.

There is **no** `androidTest` source set today (`androidTestImplementation(composeBom)` only; no runner). Create:

- `android/app/src/test/java/io/grokify/os/apps/lyre/LyreRulesTest.kt` — golden fixtures stitch/pop/split/mute/loop/insert; hold-between-clips still stitches `lc_b`; dual-write asserts; **3-part pop** (`after_stitch_3` → `after_pop_3`: parts `[lc_a,lc_b]`, `lc_c` still in layers, `movie.src` stays compiled key).
- `android/app/src/test/resources/io/grokify/os/apps/lyre/fixtures/{unstitched,after_stitch,after_pop,unstitched_3,after_stitch_3,after_pop_3}.json`
- `android/app/src/test/java/io/grokify/os/apps/lyre/LyreBoardCodecTest.kt` — unknown `ui` keys survive stringify.
- `android/app/src/test/java/io/grokify/os/apps/plugin/PluginFaviconTest.kt` — `parseIcon("lyre") == PluginIconKey.Lyre`; `everyBuiltinHasAFavicon` (drawable, not vector).
- `android/app/src/androidTest/assets/lyre/clip_10f_24fps.mp4`, `clip_3f_24fps.mp4` — **committed** 24 fps 320×240 yuv420 + silent aac. Do **not** generate at test time.
- `android/app/src/androidTest/java/io/grokify/os/apps/lyre/LyreCutterInstrumentedTest.kt` — trim shortens; mute has no audio; stitch duration ≈ movie `(frames-1)/fps` + clip; **after `LyreSession.apply`, `board.movie.durationSec`/`fps` equal `CutOk`**; burn-audio two beds with `startSec=1.5` via `addGap((1.5 * 1_000_000L).toLong())` on an **audio** sequence; **3-part pop restores gen g1 duration/src on the board** (not `lc_a.src`, not g2); **start from a 2-part compile with no `g1`, stitch C, Pop C → board matches the original 2-part duration/src, not `pop_missing_gen`**; **cold 3-part no gens, leftover parts present, Pop C → rebuild, not `pop_missing_gen`**; **cold 3-part no gens and leftover `parts[].src` missing → `pop_missing_gen`, board unchanged**; orig untouched on failure; no undo entry on failure.

Instrumented tests run on a **physical API 26+ device or emulator with MediaCodec** (`./gradlew :app:connectedDebugAndroidTest`). `./gradlew test` does **not** execute them. The ffmpeg-fallback decision gate **depends** on this connected run.

OTA after shippable work (AGENTS.md): bump `versionCode` / `versionName` in `android/app/build.gradle.kts`, then:

```bash
cd android && ./scripts/publish.sh debug --channel phone --changelog "LYRE native inner app"
```

---

## API / Interface Changes

### Android catalog / routing

- `BuiltinPluginCatalog.LYRE`
- `PluginIconKey.Lyre` + `vectorIcon` + `drawableRes` + `parseIcon("lyre")`
- `AppsPane` **new parameter** `onRequestLyrePerms`, call-site lambda, `when` branch
- `GrokifyApi` does **not** grow LYRE methods; `LyreApi` is a sibling with a **streaming** client in addition to JSON (`getStorage` in PR 2, `putStorage` POST in PR 5)

### `web/api/lyre.php` (new)

JSON envelope `{ok, error?, …}` for JSON actions only. `storage_get` success is **raw bytes**. `storage_put` is **POST** raw body (`?action=storage_put&key=`), never PUT, never `gos_json_body()`. Auth: `gos_require_access()` except `lyre-watch.php`. Touch device version on token auth (`gos_touch_device`) same as `plugins.php`. Branch method+action **before** the GET JSON router and the POST-only 405.

### `web/api/lyre-watch.php` (new, PR 7)

Unauthenticated public stream by token. Temporary until grokme matcher ships; remains a fallback.

### grokme (not lyre repo; **parallel**, not a file in a GrokifyOS PR)

1. Unauthenticated **file** GET matching `^public/watch/[a-f0-9]{32}\.mp4$` only, **before** `me_require_project_key()`. No listing leak.
2. Optional `/v1/imagine/image` + `/v1/imagine/video` (assumed; spike in PR 6). Phone works with SpaceXAI if these 404.

### No GrokifyOS Chat API changes

Muse reuses `admin-system-chat-sessions.php` via `HostAiClient`.

---

## Data Model Changes

### GrokifyOS MySQL

`lyre_projects` as above. Migration id `010_lyre_projects`.

### LYRE Postgres (untouched schema, phone writes rows)

- Odysseus: `id = 'lyre'` **INSERT empty BoardData if missing**, else UPDATE in place.
- Phone boards: insert `id = lyre_phone_<uuid>`.

### BoardData fields the phone writes

All fields in `types.ts` that the product mutates: `scenes[].frames[]` media + caption/dialogue/notes, `videoLayers` / `audioLayers`, `library*`, `movie`, `videoGen`, `title`. Recycle-bin stills become `RefImage` with `fromSceneId` / `fromFrameId` / `holdSec` as on web. **No new Frame keys.**

### Local-only

Undo dir, activity.jsonl, `LyreStore` chrome prefs, `pending/*.json`, `movie-gens/g{n}.mp4` + `meta.json`. Gens are also uploaded as `boards/{id}/movie.g{n}.mp4` (not a BoardData key). Not LRU-evicted.

---

## Alternatives Considered

### 1. WebView of lyre.grok.me (CexBot pattern)

**Rejected.** Product lock: native Compose. WebView cannot do offline MediaCodec cuts, 100 file-swap undo, or camera sheets without a large JS bridge. `CexBotPane.kt` is the anti-pattern to copy.

### 2. Phone holds `ME_API_KEY` and talks to grokme + Postgres directly

**Rejected.** Device vault would need a new key id and would leak the project key in backups / debug. Host pattern is PHP proxy (`gbot.php` loopback, `discord.php` loopback). LYRE secrets stay in `.env`.

### 3. ffmpeg-kit as the only cutter

**Deferred.** Correctness twin of web `cut-video.server.ts`, but 40–80 MB and GPL. Transformer re-encodes the same way the web already does. Fallback binary only if instrumented stitch/burn desyncs.

### 4. GrokifyOS Chat bridge for Imagine (`HostAiClient` + `media-ingest.js`)

**Rejected as primary.** Pollutes Chat sessions, uses the GrokifyOS workspace (`GROKIFY_WORKSPACE`) not the LYRE grokme project, and host `HostAiClient.complete` ignores tool/media events (`else -> { /* thinking/tools ignored */ }`). Product: me.grokpot.io first.

### 5. Optimistic concurrency on Odysseus (ETag / updated_at)

**Rejected for v1.** Product: last-write-wins, same as web. An ETag would desync from desktop which does not send one. Surface a status line “shared with desktop LYRE” on Odysseus instead.

### 6. Compose-scoped cutter (`rememberCoroutineScope` in LyrePane)

**Rejected.** Pane leave cancels 180s Transformer jobs and looks like a cut failure while files are half-written. Application-scoped `LyreSession` + DATA_SYNC FGS for long jobs.

### 7. `pendingOps` in SharedPreferences

**Rejected.** Prefs ~1 MB; board JSON + waveforms + mp4 paths do not fit. `pending/{seq}.json` + local files.

### 8. Pop via undo directory or pairwise `rebuild(partFiles)` as the happy path

**Rejected as the Pop implementation.** `undo/{seq}/` is the last 100 editor actions (a leftover trim after A+B+C stitch is the latest undo, while live `movie.src` is still the 3-part compile). Pairwise restitch of remaining `parts[].src` re-encodes and re-drops last frames; it is **not** the inverse of `stitchMovieBuffers` (which dropped the last frame of the **compiled** file). **Chosen happy path:** `LyreMovieGens` picture-compile stack, with **`ensureCurrent(preStitch = boardBefore.movie)` copying the current picture compile into `g{boardBefore.parts.size-1}` before stitch overwrites `movie.mp4`** when that slot is empty (never `Os.link`; never `RuleResult.board.movie`). **`rebuild(partFiles, dropLast=true)` is the Pop fallback only** when `g{n}` is still missing after local + GET (desktop 3-part movie, phone never stitched). `pop_missing_gen` only if leftover part files are also missing so rebuild cannot run.

### 9. `storage_put` as HTTP PUT + `CURLOPT_INFILE(php://input)`

**Rejected.** `gbot.php` / `discord.php` 405 anything that is not POST; `gos_json_body()` consumes `php://input`; `php://input` is not seekable for libcurl rewind. **Chosen:** POST raw body, query `action=storage_put&key=`, spool to `tmpfile()`. grokme itself accepts PUT or POST (`v1.php` 166).

---

## Security & Privacy Considerations

| Threat | Mitigation |
|--------|------------|
| Stolen device token lists/saves boards | Same as Chat/Gbot: Bearer `gos_…`, `gos_require_access()`, per-user `lyre_projects`. |
| Phone reads another user’s Odysseus | Odysseus is the single grokme board for this instance (self-hosted — `docs/ARCHITECTURE.md`). Phone-only boards filtered by `user_id`. |
| `ME_API_KEY` on device | Never. PHP only. |
| Path traversal on storage proxy | Allowlist `^boards/[A-Za-z0-9_./-]+$` and `^public/watch/[a-f0-9]{32}\.mp4$`. |
| Unauth public watch listing leak | Matcher is **file GET only** `^public/watch/[a-f0-9]{32}\.mp4$`. Listing still requires project key. Proxy `lyre-watch.php` looks up token + `visibility=public`. |
| Unauth public watch hotlink | 128-bit token; private deletes the object. Residual leak if URL is shared — accepted. |
| Imagine prompt injection via Muse | Muse is Grok Build chat, not a tool loop that can PUT storage. Generate goes through explicit PHP imagine actions. |
| Caption burned into public MP4 | Publish copies compiled video only; text fields stay in private JSON. |
| Camera/mic surprise | Existing `AppPermission` dialogs; no background capture. |
| SpaceXAI key | Existing vault; optional; never logged. Video bytes downloaded to app files then uploaded to grokme via PHP. |
| PHP 128 M OOM on upload | POST raw `storage_put`; spool `php://input` → `tmpfile()`; `CURLOPT_INFILE` that handle; never `file_get_contents`. Apache `Timeout` 300 caps the hop. |

---

## Observability

- Android `Log.i("Lyre", …)` on save, cut, imagine, publish. Errors `Log.w` with leftover id, never secrets. Cut failures log the reason string (`stitch`, `stitch_snapshot`, `pop_missing_gen`, `pop_rebuild`) and whether `ensureCurrent` copied `g{n}`.
- PHP: `error_log` with `user_id`, `board_id`, `action`, grokme HTTP status. Do not log `gme_` keys, Bearer, or `DATABASE_URL`.
- Activity JSONL is the user-visible audit.
- Imagine latency: record `provider=grokme|spacexai`, `kind=still|video`, `ms`, `ok` in Activity (not a new Frame field).
- FGS notification on `GrokifyApp.CHANNEL_LYRE` (`IMPORTANCE_LOW`, no sound) while cutting; tap opens LYRE via `WidgetNav.openPluginIntent`.
- Optional: `system_chat_events` `category=lyre` for PHP failures.

Alerting: none beyond existing bridge/host health. Generate/cut failures stay on `generatingError` / `videoGeneratingError`.

---

## Rollout Plan

1. Land PRs 1–8 independently (see PR Plan). Each compiles.
2. Apply `010_lyre_projects.sql` via `php scripts/install.php` on grokifyos.grokpot.io.
3. Set `GROKIFY_LYRE_*` in `/root/grokifyos/.env`. Confirm `gos_lyre_pdo()` with `sslmode=require`.
4. **Parallel** grokme: file-only unauth GET matcher; optional imagine REST. Public chip uses `lyre-watch.php` until grokme 200s.
5. Bump `:app` versionCode/versionName; `./scripts/publish.sh debug --channel phone --changelog "LYRE inner app"`.
6. Install via in-app updater. Smoke: Apps → LYRE → Odysseus loads (Postgres ensure), leftover generate, stitch (clips remain in layers), pop, public watch via proxy, Muse hidden from Chat.
7. Rollback: republish previous phone APK. PHP API is additive; table can remain. Feature is catalog-gated — removing the `when` branch hides the app.

Staged exposure: Odysseus read-only in PR 2, rails in PR 3 (read-only movie grouping), JSON rules in PR 4 (no byte mutate), cuts in PR 5, generate in PR 6, publish in PR 7. If grokme video REST is late, SpaceXAI backup still ships video.

---

## Open Questions

None that block implementation. Spikes engineers can finish without a product call:

1. **Live `boards` DDL** — `\d boards` against `GROKIFY_LYRE_DATABASE_URL`. PHP adapter maps column names. Default assumption: `id text`, `data jsonb`, `updated_at timestamptz`.
2. **Grokme video REST + `/v1/videos/edits`** — assumed shapes in Generate. 404 → SpaceXAI generate (and edit-clip fallback to image-to-video). Spike in PR 6 **before** UI.
3. **Grokme public-watch matcher** — parallel deploy; GrokifyOS `lyre-watch.php` is the fallback so Public does not stall.

Do not reopen product (chips, WebView, lyre-repo edits, fifth chip, burning captions).

---

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Last-write-wins race on Odysseus with desktop | **High** | Debounce 800 ms; `flushSave` on stop; preserve `ui`; no phone-only Frame keys; banner “shared with desktop”; phone-only projects for serious edits. |
| Media3 concat A/V desync / fps mismatch | **High** | Instrumented stitch **and** burn-audio tests on committed fixtures; `LAYOUT_SCALE_TO_FIT`; ffmpeg `libffmpeg.so` fallback if tests fail. |
| Time-based last-frame drop ≠ ffmpeg `end_frame=N-1` | **Med** | API 28+: `METADATA_KEY_VIDEO_FRAME_COUNT`; API 26–27: `MediaExtractor` sample iteration on the video track; duration−1/fps only as last resort. 24 fps fixtures stay the gate. |
| Pop of 3+ parts with no previous compile | **Med** | STITCH `ensureCurrent(boardBefore)` **copies** the current picture compile into `g{boardBefore.parts.size-1}` before overwrite when that slot is empty (desktop 2-part → phone stitch C → Pop C hits `g1`, not `pop_missing_gen`). Snapshot miss aborts stitch (`stitch_snapshot`) instead of silently overwriting. Gens uploaded + not LRU-evicted; never hardlinked to live `movie.mp4`. Cold desktop 3-part Pop (phone never stitched): `rebuild` remaining `parts[].src` with drop-last. `pop_missing_gen` **only** if those part files are also missing. |
| FGS `startForeground` unknown channel | **High** | PR 5 `GrokifyApp.CHANNEL_LYRE` in `createChannels()`; `LyreCutService` uses that id. |
| `imagine_*` before objects exist on grokme | **High** | Stills: inline `{data,mimeType}` in the PHP JSON body. Video: await `storage_put` of poster+refs before `imagine_video`. PHP never reads the phone disk cache. |
| `storage_put` PUT / `gos_json_body` eats mp4 | **High** | POST + query action; branch before JSON body; spool to tmpfile. |
| Transformer `@UnstableApi` without opt-in | **Med** | PR 5 `freeCompilerArgs` UnstableApi opt-in + `@file:OptIn` on cutter/tests. |
| Grokme video REST missing | **Med** | SpaceXAI `/v1/videos/generations` + poll is the guaranteed video path. |
| Edit clip `/v1/videos/edits` 404 | **Med** | Fallback image-to-video from still; orig kept. |
| Generate cost / latency | **Med** | Leftover spinner; error fields + retry; no auto-retry loop; video poll 10 min on **phone**; Muse does not trigger Imagine. |
| Orig + undo disk growth | **Med** | 100 cap, 2 GiB LRU, hardlinks, orig only on first destructive edit. |
| Unauth public watch listing | **High if naive patch** | Exact 32-hex `.mp4` matcher; listing stays authed; proxy token lookup. |
| PHP 128 M / Timeout 300 | **High if JSON clone** | Stream storage; imagine_video returns `request_id`; still SSE ≤120s. |
| Pane-leave cancels cutter | **High if Compose scope** | `LyreSession` + DATA_SYNC FGS >15s. |
| grokme `/v1/chat` images[] URL drop | **High if ignored** | PHP sends `{data, mimeType}` base64; max 4. |
| Transformer on API 26 devices | **Low** | minSdk 26; catch and `videoGeneratingError`. |
| kotlinx.serialization dropping `ui` | **Low** | Do not use it for BoardData; `org.json` codec + tests. |
| Odysseus Postgres row missing | **Med** | Ensure INSERT empty BoardData; typed `lyre_pg_unavailable`. |

---

## References

- Product lock (this prompt). Do not change lyre web repo.
- `/tmp/lyre-inspect/types.ts`, `clip.ts`, `movie.ts`, `cut-video.server.ts`
- `/tmp/grokme-inspect/README.md`, `v1.php`, `server.js` (`writePromptFile` base64 only)
- `docs/ARCHITECTURE.md`, `AGENTS.md`
- `docs/plans/2026-07-20-grok-assistant-design.md` (tone: catalog + pane + store + HostAiClient)
- `docs/plans/2026-08-18-cexbot-grokpot-inner-app.md` (WebView **anti-pattern**)
- `android/app/src/main/java/io/grokify/os/apps/plugin/BuiltinPluginCatalog.kt`
- `android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt` `AppsPane` (~4685, call site ~630)
- `android/app/src/main/java/io/grokify/os/apps/plugin/HostAiClient.kt` (`INTERNAL_SESSION_TITLE_PREFIX`, `complete`)
- `android/app/src/main/java/io/grokify/os/data/GrokifyApi.kt`
- `android/app/src/main/java/io/grokify/os/apps/discord/DiscordMedia.kt` (`rememberDiscordPlayer` — **do not copy** for file playback)
- `android/app/src/main/java/io/grokify/os/apps/CexBotPane.kt` (do not copy)
- `bridge/media-ingest.js` (`IMAGINE_TOOLS`)
- `web/api/_common.php` `gos_require_access()`; `web/api/media-cache.php` GET raw bytes
- `schema/009_discord_media_playlist.sql` (next id `010_`)
- SpaceXAI Imagine generate: `POST /v1/images/generations`, `POST /v1/videos/generations`, `GET /v1/videos/{id}` (edits unverified)
- Media3 **1.5.1** source (`androidx/media` tag 1.5.1): `EditedMediaItemSequence.Builder.addGap(long durationUs)` (javadoc wrongly says ms; value is Us; audio sequences only); `Presentation.createForWidthAndHeight(..., LAYOUT_SCALE_TO_FIT)` in `media3-effect` 1.5.1; types `@UnstableApi`
- `GrokifyApp.createChannels()` / `CHANNEL_GBOT`; `GbotWatch` + `WidgetNav.openPluginIntent`
- `web/api/_common.php` `gos_json_body()`; `gbot.php` 602–604 / `discord.php` 903–905 POST-only 405
- grokme `v1.php` 166 PUT **or** POST storage; `server.js` `writePromptFile` `{data, mimeType}` max 4
- `GROK_VOICES` in `SpotifyLiveDj.kt`

---

## Key Decisions

1. **Native Compose host module `lyre`, not a WebView.** Catalog + `AppsPane` parameter/call-site/branch + `apps/lyre/`. CexBot is explicitly the contrast.
2. **GrokifyOS PHP proxy holds grokme secrets.** Phone uses device Bearer only. Matches gbot/discord. `gos_lyre_pdo()` forces `sslmode=require`.
3. **Odysseus is `boards.id = 'lyre'`.** First `GET projects` ensures MySQL **and** Postgres empty `BoardData`. Phone-only boards are `lyre_phone_<uuid>`.
4. **`org.json` BoardData codec preserves unknown keys / `ui`.** Last-write-wins, 800 ms debounce + `flushSave` on stop. Chrome state in `LyreStore`; pending ops in files.
5. **Read-only `LyreClip`/`LyreMovie` in PR 3; pure `LyreRules` + `CutPlan` in PR 4; cutter bytes in PR 5.** Streaming **read** `getStorage` + `LyreCache.resolve` land in **PR 2** so PR 3 Coil/ExoPlayer have local files. Stitch does **not** delete `LayerClip`s. Dual-write Frame+clip. Golden fixtures including 3-part pop. Stitch chip = `nextStitchTarget`, not next still.
6. **`LyreSession` is application-scoped.** Cutter jobs >15s use `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on `GrokifyApp.CHANNEL_LYRE` (`IMPORTANCE_LOW`, no sound; tap → `WidgetNav.openPluginIntent(LYRE)`). `LyreRules` never calls the cutter. After `CutOk`, Session patches `movie.src` / `durationSec` / `fps` then commits; on failure no board commit, no undo, `videoGeneratingError`, orig/live unchanged.
7. **Media3 Transformer 1.5.1; `LAYOUT_SCALE_TO_FIT`; burn-audio via `EditedMediaItemSequence.Builder.addGap((startSec * 1_000_000L).toLong()).addItem(bedItem)` on an audio sequence.** Javadoc unit bug (says ms, value is Us). No `addGapUs`. No video-sequence gaps. UnstableApi opt-in in PR 5. Drop-last: API 28+ `METADATA_KEY_VIDEO_FRAME_COUNT`, else `MediaExtractor` sample iteration. ffmpeg-kit not in v1. Burn-audio spiked in PR 5 tests. Pop happy path is `LyreMovieGens` (picture compiles): STITCH **`ensureCurrent(preStitch = boardBefore.movie)` copies** the current picture file into `g{boardBefore.parts.size-1}` before overwrite if missing (never `Os.link`; never `RuleResult.board`). `rebuild` is Pop fallback only. `pop_missing_gen` only when leftover part files are also missing. Burn `movie.src` is a separate file; stitch concatenates the picture compile, not the burn.
8. **Imagine: me.grokpot.io first, SpaceXAI backup.** PHP never reads the phone disk cache. Stills: phone POSTs `{data, mimeType}` base64 in the `imagine_still` JSON body (max 4) to `/v1/chat`. Video: phone **awaits `storage_put`** of poster + ref keys, then `imagine_video`; PHP GETs those keys from grokme with `gme_`. `{request_id}` + **phone** poll; Apache 300s must not own the 10 min loop. Edit clip 404 → image-to-video fallback. Failures use existing `generatingError` / `videoGeneratingError`.
9. **`storage_get` is raw GET bytes (PR 2); `storage_put` is POST raw body (PR 5) with query `key=` URLEncoder-encoded.** PHP branches method+action before `gos_json_body()` / 405; spools `php://input` to `tmpfile()` then `CURLOPT_INFILE` that handle. Apache Timeout 300 caps the hop. JSON envelope is **not** universal.
10. **Public watch matcher is file-only `^public/watch/[a-f0-9]{32}\.mp4$`.** Parallel grokme deploy. `lyre-watch.php` unauth proxy until then. No listing leak.
11. **Muse = `HostAiClient.complete` with title `· LYRE Muse`.** Not a fifth chip.
12. **Permissions via existing `AppPermissionId` CAMERA/MICROPHONE/MEDIA.** PR 1 wires `onRequestLyrePerms` at the AppsPane call site.
13. **Instrumented tests need `testInstrumentationRunner = AndroidJUnitRunner` + committed fixtures + `connectedDebugAndroidTest`.**
14. **OTA is the last PR:** bump `:app` 314 → next, `publish.sh debug --channel phone`.

---

## PR Plan

Each PR compiles and is reviewable on its own. Later PRs depend on earlier ones as noted. Do not mix wear channels. **Do not wire Stitch/Pop click-to-mutate until PR 5 can rewrite `movie.src`.**

### PR 1 — Catalog shell

**Title:** `LYRE: builtin catalog, icon, AppsPane shell`

**Files:** `BuiltinPluginCatalog.kt`, `PluginModels.kt` (`PluginIconKey.Lyre`), `PluginFavicon.kt` (`drawableRes(LYRE)`, `vectorIcon(Lyre)`), `RemotePluginCatalog.kt` (`parseIcon("lyre")`), `GrokifyAppRoot.kt` (**AppsPane signature** `onRequestLyrePerms`, **call-site** lambda with CAMERA/MICROPHONE/MEDIA, **when** branch), `res/drawable-nodpi/plugin_ic_lyre.png` (required — `everyBuiltinHasAFavicon` keys off drawable), `apps/lyre/LyrePane.kt` (back + title + placeholder), `PluginFaviconTest.kt`

**Depends on:** none

**Changes:** Register `lyre`, Rose accent, empty pane with back chevron. Unit test: `parseIcon("lyre")` and `everyBuiltinHasAFavicon`. `appsNavShortTitle` needs no branch. No network. Does not bump version yet.

### PR 2 — Board models, codec, PHP projects, Odysseus load

**Title:** `LYRE: BoardData codec + lyre_projects + Odysseus fetch`

**Files:** `schema/010_lyre_projects.sql`, `web/api/lyre.php` (JSON projects/board/save; `gos_lyre_pdo()`; **method+action branch**; **GET `storage_get` raw stream** with `header_remove`), `.env.example` `GROKIFY_LYRE_*`, `LyreModels.kt`, `LyreBoardCodec.kt`, `LyreApi.kt` (JSON client **+ streaming `getStorage`**, 15 min read timeout; `URLEncoder.encode` the key), `LyreStore.kt` (chrome prefs only), `LyreCache.kt` (`board.json` + `pending/` schema + **`resolve` step 2 = `getStorage` byteStream → `*.part` rename**), `LyreBoardCodecTest.kt`

**Depends on:** PR 1

**Changes:** Device-auth list/create/rename; ensure Odysseus **MySQL + Postgres empty BoardData**; `lyre_pg_unavailable` on PG failure; last-write-wins save; codec preserves `ui`. Spike `\d boards` and map columns. Phone can open Odysseus JSON (no rails yet). **Streaming GET is here** so PR 3 Coil/ExoPlayer can populate local files; `putStorage` stays PR 5. Pending-file format documented; flush comes with Session in PR 5. Do **not** call `gos_json_body()` on the `storage_get` path.

### PR 3 — Rails + player UI + read-only movie/clip

**Title:** `LYRE: player, rails, clock, LyreClip/LyreMovie`

**Files:** `LyreClip.kt` (full `clip.ts` 1:1 including `movieClips` / `clipAtTime` / `presentedVideoWindow` / `frameIn`/`frameOut`), `LyreMovie.kt` (full `movie.ts` including `moviePlayDuration` / `moviePartDurations` / `movieProgramLayers` / `nextStitchTarget`), `ui/LyreEditor.kt`, `LyrePlayer.kt` (`DefaultDataSource` + concatenating compiled movie + leftovers — **not** `rememberDiscordPlayer`), chip shells, Coil stills, app bar project name (switcher stub)

**Depends on:** PR 2

**Changes:** Locked layout. Video rail uses `movieProgramLayers` (locked prefix + leftovers). Clock duration = `movieClips(scenes)`; seek via `clipAtTime` then movie-file offset (`moviePartDurations`) or leftover item. Coil stills and ExoPlayer items load from `LyreCache.resolve` **local Files** (PR 2 streaming GET fills the cache). Do **not** point Coil at grokme URLs (ImageLoader has no Bearer). Offline: play cached objects if present. **No** `LyreRules`, no cutter, no generate, no `putStorage`.

### PR 4 — Mutating leftover rules (JSON only)

**Title:** `LYRE: movie-prefix rules + golden fixtures`

**Files:** `LyreRules.kt` (`RuleResult` + `CutPlan`), `LyreRulesTest.kt`, `src/test/resources/.../fixtures/{unstitched,after_stitch,after_pop,unstitched_3,after_stitch_3,after_pop_3}.json`, `ui/LyreClipSheet.kt` (Stitch/Pop **visibility** from `canStitchClip` / parts; clicks no-op or toast)

**Depends on:** PR 3 (needs `LyreMovie`/`LyreClip`)

**Changes:** Pure functions only. Tests: dual-write; stitch appends `parts` and **does not** remove clips; 2-part pop restores stitch target + falls back to first clip `src`; **3-part pop** drops `lc_c` from `parts` but keeps `movie.src` as the compiled key and keeps `lc_c` in layers; hold between A and B still stitches `lc_b`; insert refused inside prefix; mute does not add a rail; loop is player-only. **Do not** rewrite `movie.src` bytes. **Do not** assert compiled `durationSec`. **Do not** ship JSON-only Pop as playback.

### PR 5 — Local cutter, Session, FGS, undo, instrumented tests

**Title:** `LYRE: Transformer cutter, LyreSession, FGS, undo`

**Files:** `android/app/build.gradle.kts` (`testInstrumentationRunner`, UnstableApi `freeCompilerArgs`, transformer/effect/muxer, androidTest runner/ext-junit/coroutines-test), `LyreCutter.kt` (`@file:OptIn(UnstableApi::class)`, including `rebuild`), `LyreSession.kt` (`ensureCurrent(boardBefore)` then CutOk → patch `movie.src`/`durationSec`/`fps` then commit), `LyreMovieGens.kt` (`ensureCurrent` / `restore` / `push` / `dropAbove`; copy not link), `LyreCutService.kt` + `AndroidManifest.xml` `dataSync` service + API 35 `onTimeout`, `GrokifyApp.kt` (`CHANNEL_LYRE` in `createChannels()`), `res/values/strings.xml` (`notification_channel_lyre`), `LyreUndo.kt` (stage then push only on CutOk), `LyreCache.kt` (objects/orig/undo/`movie-gens`/pending flush; gens not LRU-evicted), `LyreApi.kt` **`putStorage` POST**, `web/api/lyre.php` `storage_put` spool-to-tmpfile, `ui/LyreClipSheet.kt` (trim/split/mute/restore/remove + **wire Stitch/Pop clicks**), `androidTest/.../LyreCutterInstrumentedTest.kt` + **committed** `androidTest/assets/lyre/*.mp4`

**Depends on:** PR 4

**Changes:** `LyreSession.apply` runs `CutPlan`. Jobs >15s raise DATA_SYNC FGS on **CHANNEL_LYRE**. Trim/split/mute/stitch/extract rewrite files, keep orig, undo by swap. **On STITCH, before overwrite:** `ensureCurrent(preStitch = boardBefore.movie)` — if `boardBefore.parts.size >= 2` and `g{that size-1}` is missing, **copy** (not `Os.link`) the current picture compile (`movie.src`, or `origSrc` when live is `movie.burn.mp4`) into that gen slot + `storage_put movie.g{n}.mp4`. Snapshot miss → `stitch_snapshot`, no overwrite. Stitch input is the picture compile, never the burn; success clears `origSrc` if live was burn. **Pop restores `LyreMovieGens` (picture compile)**; 2-part pop falls back to first clip `src`; 3-part pop copies `g1` onto `movie.mp4` and patches board duration — **not** undo-dir, **not** the burn file. Missing gen after resolve: **fallback** `LyreCutter.rebuild(remaining parts, dropLast=true)` only. `pop_missing_gen` **only** if leftover part files are also missing. After CutOk: patch board duration/fps, then commit; on failure: no commit, no undo, `videoGeneratingError`, orig/live unchanged. Instrumented: stitch duration **on the board**, mute, **burn-audio `addGap` two beds (audio sequences, Us)**, **3-part pop board duration**, **2-part compile without `g1` → stitch C → Pop C restores the original 2-part duration/src (not `pop_missing_gen`)**, **cold 3-part Pop rebuild**, **`pop_missing_gen` when part files are gone**. Gate: if stitch or burn A/V sync fails, follow-up `libffmpeg.so` / defer burn UI — do not silently ship a frozen join. `flushSave` on stop. Apache Timeout 300 documented on `storage_put`.

### PR 6 — Imagine generate (grokme first, SpaceXAI backup)

**Title:** `LYRE: Imagine stills + clips via grokme then SpaceXAI`

**Files:** `LyreImagineClient.kt`, `lyre.php` `imagine_still` / `imagine_video` / `imagine_status` (request_id; still images as `{data, mimeType}` **in the PHP JSON body**; video keys only after upload), Clip/Picture sheet generate/edit/camera/gallery, leftover `generatingError` / `videoGeneratingError`

**Depends on:** PR 5 (insert only on leftovers; `putStorage` for video refs)

**Changes:** **Spike first** (same PR, before UI): grokme `/v1/imagine/video`, grokme edit, `POST https://api.x.ai/v1/videos/edits`. Stills: phone reads local cache files, POSTs `{data, mimeType}` in `imagine_still` (max 4) to `/v1/chat` (and `/v1/imagine/image` if live). **PHP does not read the phone disk cache** and does not GET grokme for stills. Video: **await `storage_put` of poster + ref keys**, then `imagine_video`; PHP GETs those keys from grokme with `gme_`; 404 → SpaceXAI generate + phone poll. Edit clip 404 → image-to-video fallback, orig kept. Offline: queue `storage_put` / `save_board`, **never** Imagine HTTP.

### PR 7 — Audio, burn-audio UI, publish, watch QR

**Title:** `LYRE: audio rails, public watch proxy + QR`

**Files:** audio extract/new bed/mic, burn-audio chip (**only if PR 5 mix test green**), `ui/LyreWatch.kt`, zxing core, `lyre.php` visibility/publish, **`web/api/lyre-watch.php`**, `LyreApi.putStorage` already in PR 5

**Depends on:** PR 5 (cutter), PR 2 (projects)

**Changes:** Extract → new audio rail. Mute ≠ rail. Burn-audio writes new `movie.src`. Public copies compiled mp4; private deletes it. QR uses grokifyos proxy until grokme file-GET matcher 200s. Caption not burned. **Grokme matcher is a parallel deploy, not a file in this PR.**

### PR 8 — Muse, Activity, Scenes/Library/Bin polish, tests, OTA

**Title:** `LYRE: Muse, Activity, scenes/library/bin, tests, phone OTA`

**Files:** `LyreMuse.kt`, `ui/LyreMuseSheet.kt`, `LyreActivity.kt`, Scenes rename/reorder/dump/`+`, Library drop onto leftover rails, Bin recycle, permanent delete only from Library/Bin, remaining unit tests, bump `versionCode`/`versionName`, `publish.sh debug --channel phone`

**Depends on:** PR 3–7

**Changes:** Muse `session_title = "· LYRE Muse"` from `LyreSession` IO dispatcher. Activity infinite log, tap-to-jump inspect-only past undo. Product chips complete. Catalog wiring test green. Changelog e.g. `LYRE native editor (Odysseus + phone boards)`.

**Suggested publish command (PR 8 only):**

```bash
# after bumping android/app/build.gradle.kts
cd android && ./scripts/publish.sh debug --channel phone --changelog "LYRE native inner app"
```
