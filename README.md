# Annotation Recorder (Android)

Android accessibility annotation tool for capturing:
- Accessibility events
- Accessibility tree snapshots
- Optional screenshot snapshots linked to tree snapshots

## Status
Pre-1.0 prototype.

## Features
- Overlay start/stop control (transparent floating edge button)
- AccessibilityService-based event + tree capture
- Session XML output with strict event->snapshot mapping
- Optional screenshot capture (MediaProjection), delayed 2s after each event
- First screenshot at session start
- Rename recording file on stop
- One-tap open/share (including Feishu share fallback)

## Architecture
- `RecorderAccessibilityService`: event listener + tree extraction
- `SessionRecorder`: recording lifecycle, buffering, snapshot-image mapping
- `SessionXmlWriter`: XML serialization
- `OverlayControlService`: persistent overlay control
- `SessionForegroundService`: foreground recording lifecycle
- `ScreenCaptureManager`: MediaProjection screenshot pipeline

## Build
Requirements:
- Android Studio (recent stable)
- JDK 17

Commands:
```bash
./gradlew clean assembleDebug
```

## Run setup
1. Install app
2. Enable Accessibility Service
3. Enable Overlay permission
4. (Optional) Grant screen capture permission when prompted
5. Start recording

## Output
- XML: `Android/data/com.annotation.recorder/files/sessions/<sessionId>.xml`
- Images: `Android/data/com.annotation.recorder/files/sessions/<sessionId>/images/<snapshotId>.png`

`treeSnapshots/snapshot@imagePath` points to the linked image.

## Known limitations
- Global raw touch coordinates are not available under standard Android app permissions.
- Coordinate fields are estimated from accessible node bounds.
- Screenshot capture depends on runtime MediaProjection consent.

## Compliance
Read:
- [DISCLAIMER.md](DISCLAIMER.md)
- [PRIVACY.md](PRIVACY.md)
- [SECURITY.md](SECURITY.md)

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
MIT, see [LICENSE](LICENSE).
