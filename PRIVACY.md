# Privacy

## What this app collects (locally)
- Accessibility events (click/scroll/text/window changes)
- Accessibility tree snapshots
- Estimated coordinates derived from accessible node bounds
- Optional screenshots (when MediaProjection permission is granted)

## Storage location
- XML sessions: `Android/data/<package>/files/sessions/<sessionId>.xml`
- Snapshot images: `Android/data/<package>/files/sessions/<sessionId>/images/<snapshotId>.png`

## Data transmission
- By default, the app does not upload captured data to remote servers.
- Sharing/export actions are initiated explicitly by the user.

## Retention and deletion
- Data remains on local storage until deleted by the user.
- You can remove files in the app-specific `sessions` directory to permanently delete records.

## Permissions
- Accessibility Service: event/tree capture
- Overlay: floating start/stop control
- Foreground Service + Notifications: stable recording lifecycle
- MediaProjection (optional): screenshot capture
