# Debugging TMM Relay App

## Quick Debug Guide

If the app crashes when pressing Start, follow these steps to capture the error:

### Option 1: Using the Debug Script (Recommended)

**Windows (PowerShell):**
```powershell
cd C:\Users\kunal.chakraborty\Desktop\TMM_AND_APP\tmm-relay-android
.\scripts\debug_app.ps1
```

**Linux/Mac:**
```bash
cd /path/to/tmm-relay-android
chmod +x scripts/debug_app.sh
./scripts/debug_app.sh
```

The script will:
1. Check for connected Android devices
2. Clear previous logs
3. Start capturing logcat output
4. Filter for TMM Relay, Catalyst, and error messages
5. Save logs to a file with timestamp

**Then:**
1. Press Start in the app
2. Wait for the crash
3. Press Ctrl+C to stop the script
4. Check the generated `debug_log_*.txt` file

### Option 2: Manual logcat

```bash
# Clear logs
adb logcat -c

# Start capturing (filter for errors and TMM Relay)
adb logcat -v time | grep -E "TmmRelay|CatalystClient|TrimbleLicensing|AndroidRuntime|FATAL|Exception|Error"

# Or save to file
adb logcat -v time > debug_log.txt
```

### Option 3: Android Studio Logcat

1. Open Android Studio
2. Connect your device
3. Open Logcat tab
4. Filter by: `TmmRelay|CatalystClient|TrimbleLicensing|AndroidRuntime`
5. Press Start in the app
6. Check for red error messages

## Common Issues and Solutions

### 1. "This app has bug" on Samsung
- **Cause**: Usually an uncaught exception or ANR (Application Not Responding)
- **Solution**: Check logcat for the actual exception. The crash handler should log it.

### 2. Catalyst SDK initialization fails
- **Check**: Look for "CRITICAL:" messages in logs
- **Common causes**:
  - Missing permissions (Location, Bluetooth)
  - Trimble Licensing not initialized
  - Driver loading failure

### 3. Property access errors
- **Check**: Look for "Error accessing" messages
- **Solution**: The code now has try-catch around all property accesses

## What to Look For in Logs

1. **FATAL EXCEPTION**: The actual crash reason
2. **CRITICAL:** messages: Critical failures in initialization
3. **Exception type**: The Java/Kotlin exception class
4. **Stack trace**: Shows where the error occurred

## Crash Reports

The app now includes a global crash handler that:
- Catches all uncaught exceptions
- Logs detailed crash reports
- Saves crash reports to `/sdcard/tmmrelay_crash_*.txt` (if storage is available)

## Next Steps

After capturing the logs:
1. Share the error message and stack trace
2. Note which step failed (Step 1-7 in CatalystClient)
3. Check if it's a permission issue
4. Verify Trimble SDK is properly initialized

