# MusWall 1.9.3 crash audit

- Removed custom Application startup from the manifest.
- Chaquopy is initialized only by UI/service when needed.
- Media metadata bitmaps are copied instead of recycling player-owned bitmaps.
- Single debounced render job prevents overlapping Python renders.
- Source/result bitmaps are explicitly released after rendering.
- Slider values are clamped before Material Slider assignment.
- Static-image rendering only uses the selected image in Static mode.
- Original wallpaper backup uses a private copy and restores both system/lock where Android permits.
- Live wallpaper remains a real WallpaperService and refreshes only on wallpaper changes.
- Version: 1.9.3 / versionCode 5.
