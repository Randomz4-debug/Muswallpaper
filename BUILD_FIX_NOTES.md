# Build Fix Notes

- Fixed AndroidManifest.xml namespace warning by removing the deprecated `package` attribute; the Gradle `namespace` is authoritative.
- Updated Pillow from 10.2.0 to 11.0.0. Chaquopy's native package repository provides Android arm64-v8a and x86_64 wheels for Pillow 11.0.0, avoiding a source build which failed because JPEG development headers were unavailable on the GitHub runner.
- Removed a generated Python `__pycache__` file from the source tree.
