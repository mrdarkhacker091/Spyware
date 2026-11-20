# Gallery Uploader App

## Configuration
The app reads credentials from `app/src/main/res/raw/config.json`.
You can edit this file before compilation, or modify `config.json` directly using any APK editor (or by extracting the APK, editing `res/raw/config.json`, and re-signing).

## Features
- WebView loading configured URL.
- Background ContentObserver tracking images and videos.
- Telegram Bot API uploader with size handling (photos, videos, documents).
- Rate limit handling (HTTP 429) and local Room DB duplicate tracking.
