# Build SoundWall APK from a phone

1. Create a GitHub repository.
2. Upload the contents of this project to the repository. The `.github/workflows/build-apk.yml`
   file must be uploaded too.
3. Open the repository's **Actions** tab.
4. Select **Build SoundWall APK**.
5. Tap **Run workflow**.
6. Wait for the green checkmark.
7. Open the completed workflow run.
8. Scroll to **Artifacts**.
9. Download **SoundWall-debug-apk**.
10. Extract the downloaded ZIP and install `app-debug.apk` on your Android phone.

No PC or Android Studio is required for the build.

Important:
- This workflow builds a DEBUG APK.
- The APK is not published to Google Play.
- The build uses GitHub's cloud runner.
- The project must compile successfully; if the workflow fails, open the run and copy the first Gradle error.
