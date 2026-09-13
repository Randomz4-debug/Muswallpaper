# MusWall 1.9.0 update

## Added
- Real Android `WallpaperService` live wallpaper.
- Music artwork updates the live wallpaper without a continuous animation loop.
- Low-resolution intermediate rendering to reduce CPU/RAM use on budget phones.
- Debounced media-session callbacks so one track change does not trigger multiple renders.
- Atomic wallpaper cache writes to avoid half-written images/glitches.
- Music / Static modes.
- Blur, Cover, CD, Square and Cover Color effects.
- Gaussian, Solid, Motion and Glass blur choices.
- Cover height, offset, transition height, darkness and scale controls.
- Image picker for static wallpaper mode.
- Save/history menu.
- Settings screen with theme, restore wallpaper, service, FAQ and feedback entries.

## Important Android limitation
Android requires the user to confirm a live wallpaper through the system wallpaper picker. MusWall cannot silently activate a live wallpaper in the background. On POCO/Xiaomi, also enable Autostart and set battery usage to No restrictions.

If Android shows **Restricted setting** for Notification access after installing a sideloaded APK, open:
Settings -> Apps -> MusWall -> top-right menu -> Allow restricted settings
Then return to Notification access and enable MusWall.
