# Image Resizer & Crop (Android)
One app, two tools: **Resize/Compress** and **Crop** (Crop button sits next to Upload on the main screen).

## Get the APK from GitHub
1. Create a GitHub repo and push this folder.
2. Open **Actions → Build APK → latest run → Artifacts → ImageTool-debug-apk**.
3. Unzip, copy the APK to your phone, install (allow "unknown sources").

Or open the folder in Android Studio and press Run.

## Features
- Upload image; Crop button beside it
- Required size H × L in mm (A4 default 297×210), px shown in brackets (uses DPI)
- Manual dimension: L, H (px) or %, with aspect-ratio lock
- Quality bar + DPI; quick presets 20/50/100/200/500 KB; "Compress to KB" button next to the quality bar
- Original vs Resized preview with file sizes
- Output: JPEG (default), PNG, JPG, PDF -> saved in Downloads/ImageTool
- Crop: blurred outside area, crop box with 8 handles (every corner and edge), pinch to zoom/pan/rotate, free-angle rotation slider, Passport/ID size presets
