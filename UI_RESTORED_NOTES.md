# MusWall UI restoration

The previous buildable repair used a minimal fallback layout because the original source archive did not contain `app/src/main/res/layout/activity_main.xml`.

This version restores a full MusWall control screen with:
- Now Playing / detection status
- Automatic wallpaper toggle
- Restore-on-pause toggle
- Blur, darkness, and album-art size controls
- Home / Lock / Home + Lock target selection
- Notification-access status
- Xiaomi/POCO autostart and battery controls
- Reset-to-default style button
- Persistent loading of saved preferences

The wallpaper engine, notification listener, Python bridge, and Xiaomi helpers remain in the project.
