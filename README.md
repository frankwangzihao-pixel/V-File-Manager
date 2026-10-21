# V File Manager v0.1.0

Native Kotlin Android file manager, Android 8.0+ (API 26). Uses Storage Access Framework: choose a folder to grant access, no broad storage permission.

Features: folder navigation, name search, sort, create folder, rename, delete, details, copy/move with destination selection, ZIP compress/extract. Long-press an item to select it; action buttons appear below the list. Tap a file to open with another app. ZIP extraction rejects path traversal and has safety limits.

## Build
Push all files (including `.github`) to a GitHub repository, open Actions > Build V File Manager APK, download artifact. Or run `gradle :app:assembleDebug` with JDK 17 and Android SDK 35.

## Important
Some Android document providers may refuse operations. Moving is implemented as copy then delete and can take time. ZIP creation and extraction are best used for modest files/folders. The SAF folder picker may restrict system-managed folders. GitHub Actions debug APK signatures are not stable across fresh build environments; configure release signing for future updates.
