# V File Manager v0.2.0

Native Kotlin Android file manager, API 26+. Midnight Aurora theme.

## Improvements
- Custom adaptive launcher icon
- Multi-selection (tap Multi-select, then tap items), select all and clear
- Batch copy, cut, paste, delete and ZIP compression
- Android Sharesheet file forwarding (multiple files)
- Text/code file viewer and editor (UTF-8, max 1 MB; make backups before editing)
- Background document listing to reduce UI stalls; metadata cached per folder
- Search, sorting, rename, details, new folder, ZIP extract

## Limitations
- Android Storage Access Framework grants access only to user-selected folders. Some protected folders are unavailable.
- No native source-code compilation/runtime is included. Use external IDE/compiler apps.
- Large directory UI still creates rows eagerly; virtualization and pagination are future work.
- ZIP operations may be slow on providers with high latency; extraction limited to 512 MB and 5000 entries.
- Not all providers support overwrite, rename or move. Always test on expendable files first.

## Build
Push to GitHub and run Actions workflow; artifact contains debug APK.
