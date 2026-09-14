# Crash fix

Version 1.9.0 crash fix:
- Chaquopy/Python is no longer started during Application.onCreate().
- Python is initialized lazily only when rendering is requested.
- Added application-context initialization for MainActivity and media listener.
- Kept the real Android WallpaperService live-wallpaper implementation.
- Reduced render JPEG quality slightly to lower memory pressure.
- Live wallpaper refresh is posted through the engine Handler instead of drawing directly from the broadcast callback.
