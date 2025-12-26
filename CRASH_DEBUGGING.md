# Crash Debugging Guide

## Quick Steps to Capture Crash Logs

### Option 1: Using PowerShell (Windows)
```powershell
# Run this script to capture logs
.\scripts\get_crash_logs.ps1

# Or manually run:
adb logcat -d -v time *:E *:F AndroidRuntime:E System.err:E com.hirenq.tmmrelay:* | Select-Object -Last 500
```

### Option 2: Using Command Line
```bash
# Clear old logs first
adb logcat -c

# Start capturing (run this BEFORE pressing Start button)
adb logcat -v time *:E *:F AndroidRuntime:E System.err:E com.hirenq.tmmrelay:* > crash_log.txt

# Then press Start button in the app
# Press Ctrl+C to stop capturing
# Review crash_log.txt
```

### Option 3: Real-time Monitoring
```bash
# Monitor logs in real-time
adb logcat -v time | grep -E "TmmRelayService|CatalystClient|AndroidRuntime|FATAL|Exception"
```

## What to Look For

When the app crashes, look for these in the logs:

1. **FATAL EXCEPTION** - This shows the main crash
2. **TmmRelayService** - Service initialization logs
3. **CatalystClient** - Catalyst SDK initialization logs
4. **CrashHandler** - Custom crash reports
5. **AndroidRuntime** - System crash reports

## Common Issues Fixed

### 1. Property Access Issues
- Changed from Kotlin property access (`position.latitude`) to explicit Java getter methods (`position.getLatitude()`)
- This prevents runtime crashes when accessing Java object properties

### 2. Enhanced Error Handling
- Added try-catch blocks around all SDK calls
- Added detailed logging at each initialization step
- Service now logs each step of initialization

### 3. Null Safety
- Added null checks for `getExternalFilesDir()` which CatalystFacade uses
- Better handling of null values from SDK

## Current Logging Points

The app now logs these steps:
- Step 1: Trimble Licensing initialization
- Step 2: Device ID retrieval
- Step 3: CatalystClient creation
- Step 4: Notification channel creation
- Step 5: Foreground service start
- Step 6: Catalyst connect() call

Each step logs success or failure with detailed error messages.

## Next Steps

1. **Run the app** and press Start
2. **Capture logs** using one of the methods above
3. **Share the logs** - especially lines containing:
   - `FATAL EXCEPTION`
   - `TmmRelayService`
   - `CatalystClient`
   - Any error messages

The logs will show exactly where the crash occurs.

