# MusWall 1.9.2 crash/performance fix

- Removed the whole-activity fallback path which hid the real MusWall UI whenever one optional control threw during startup.
- MainActivity now loads the full UI first and initializes modes, effects, sliders, actions, state, and preview independently.
- Slider preferences are clamped to the XML slider ranges so old/corrupt preferences cannot crash launch.
- Persisted static wallpaper URI is restored safely.
- Python remains lazy: the Application stores only the application context; Python starts only when rendering is requested.
- MediaNotificationListenerService no longer initializes Python itself.
- Media metadata artwork extraction is moved off the main callback path and album art is bounded to reduce memory spikes and UI lag.
- PythonBridge bounds render inputs and logs failures instead of crashing the UI.
- Live wallpaper rendering runs on a dedicated HandlerThread and refreshes only when artwork changes.
- Live wallpaper bitmap decoding uses RGB_565 and is released after drawing.
- Version 1.9.2 / versionCode 4.
