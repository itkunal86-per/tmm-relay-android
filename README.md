# TMM Relay (Kotlin, Android 9+)

Minimal scaffold for relaying Trimble Mobile Manager (local WebSocket `ws://127.0.0.1:4949/positionStream`) telemetry to your backend as JSON every time a message arrives, while running as a foreground service and auto-starting on boot.

## Structure
- `app/src/main/java/com/example/tmmrelay/model/TelemetryPayload.kt` â€“ data model including health flag.
- `service/` â€“ WebSocket client, API client, and foreground relay service.
- `BootReceiver` â€“ launches service after reboot.
- `MainActivity` â€“ simple UI to start/stop relay and request runtime permissions.
- `util/DeviceInfoUtil` â€“ device id, battery, and health helpers.

## Build
1) Open in Android Studio (Giraffe+).  
2) Use SDK 34, JDK 17.  
3) If the Gradle wrapper JAR is missing, run `gradle wrapper --gradle-version 8.7` from the project root.  
4) Build/Run on Android 9+ device where TMM is installed and exposes the local WebSocket.

## Configure
- `tenantId` and `apiKey`: set in `TmmRelayService`.
- `ApiClient.API_URL`: point to your backend endpoint.
- Notification icon: uses `@drawable/ic_tracker`; replace as needed.
- Permissions: INTERNET, location, foreground service, boot completed, and (optional) POST_NOTIFICATIONS on Android 13+.

## Health rules
- `LOW_BATTERY`: battery < 20%.
- `NO_SIGNAL`: fixType == NO_FIX.
- `OFFLINE`: no inbound TMM data for 10 minutes (emitted by service watchdog).
- else `OK`.

## Notes
- WebSocket client uses OkHttp with pings and retry on connection failure.
- Foreground service is `START_STICKY` to survive process kills; includes offline watchdog.
- BootReceiver restarts service on boot.
- Add logging, retry/backoff, and auth token rotation as appropriate for production.
