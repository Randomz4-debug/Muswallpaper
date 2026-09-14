# MusWall 1.9.1 — crash/performance repair

This build keeps the full MusWall-style UI and live-wallpaper features while fixing the main failure paths found during review.

## Fixes
- Removed eager Chaquopy/Python startup from `Application.onCreate()`.
- Python now starts only when rendering is actually needed.
- Added a protected startup path in `MainActivity` so a UI component/inflation problem cannot create a crash loop.
- Added a minimal fallback screen with Retry.
- Prevented lifecycle callbacks from touching uninitialized views.
- Rewrote Settings XML to avoid the AAPT `attr/FAQ... not found` resource-linking failure.
- Added safe/sampled image decoding to reduce RAM usage and freezes.
- Limited incoming album artwork to 1600px before rendering.
- Debounced media callbacks and kept live wallpaper redraw event-driven.
- Back up the original wallpaper before applying a generated wallpaper, including live-wallpaper mode.
- Live wallpaper remains a real Android `WallpaperService` and redraws only when artwork changes.
- Bumped version to 1.9.1 / versionCode 3.

## Build
GitHub Actions provisions Gradle 8.4 directly, so the workflow does not depend on a missing wrapper JAR.
Pillow is pinned to 11.0.0 because Chaquopy provides Android wheels for this setup.
