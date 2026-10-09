# Jellyfin Android (nofocus)

[Jellyfin for Android](https://github.com/jellyfin/jellyfin-android) with one behavior change: **video keeps playing at full volume during phone calls** instead of pausing.

Stock Jellyfin lets ExoPlayer manage audio focus. A cellular call takes locked transient focus, so the stock app pauses and stays paused until the call ends (Samsung "Multi sound" does not override this). This build requests focus itself and keeps playing on a transient loss: at full volume during a call, ducked to 25% for other interruptions (e.g. navigation prompts). A permanent loss (another app taking over) still pauses. During a call the volume keys change the media volume (with the system volume UI) instead of the call volume, like TikTok.

## Install

Download the APK from [Releases](../../releases). It installs as `org.jellyfin.mobile.nofocus`, next to the stock app, with its own login and settings. Releases are signed release builds (minified) with a fixed key, so each new release updates over the previous one.

## How it stays up to date

This branch holds only the changes, not the Jellyfin source:

- `patches/` — the patch series applied on top of upstream
- `.github/workflows/sync-build.yml` — runs daily (and on demand)

Each run finds the newest stable upstream tag (`vX.Y.Z`, no pre-releases). If there is no release for it yet, it applies the patches to that tag, builds the signed release APK, and publishes release `nofocus-vX.Y.Z`. If the patches no longer apply cleanly, it opens an issue instead.

## Changes

1. `MediaExtensions.kt`: ExoPlayer audio focus handling turned off.
2. New `DuckingAudioFocus.kt`, wired into `PlayerViewModel.kt`: requests focus on play, volume 25% on transient loss, restore on gain, pause on permanent loss.
3. `app/build.gradle.kts`: app ID suffix `.nofocus` (debug and release).
4. `DuckingAudioFocus.kt` / `MainActivity.kt`: full volume while the audio mode is `MODE_IN_CALL`; volume keys adjust `STREAM_MUSIC` with `FLAG_SHOW_UI` during a call while the player is open.

Only the native video player is changed. Licensed under GPL v2 like upstream.
