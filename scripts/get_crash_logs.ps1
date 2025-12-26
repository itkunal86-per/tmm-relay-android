# PowerShell script to get crash logs from Android device
# Run this after the app crashes to see the error

Write-Host "=== TMM Relay Android - Crash Log Retrieval ===" -ForegroundColor Cyan
Write-Host ""

# Check if adb is available
$adbCheck = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCheck) {
    Write-Host "ERROR: adb not found. Please ensure Android SDK platform-tools are in your PATH." -ForegroundColor Red
    exit 1
}

# Get package name
$packageName = "com.hirenq.tmmrelay"

Write-Host "Retrieving crash logs for: $packageName" -ForegroundColor Green
Write-Host ""

# Get last 500 lines of logcat filtered for errors and the app
Write-Host "=== Last 500 lines of logcat (errors and app logs) ===" -ForegroundColor Yellow
adb logcat -d -v time *:E *:F AndroidRuntime:E System.err:E $packageName:* | Select-Object -Last 500

Write-Host ""
Write-Host "=== Searching for crash reports ===" -ForegroundColor Yellow
adb logcat -d | Select-String -Pattern "FATAL|AndroidRuntime|Exception|Error|CrashHandler" -Context 10,10

Write-Host ""
Write-Host "=== Checking for crash files on device ===" -ForegroundColor Yellow
adb shell "ls -la /sdcard/tmmrelay_crash_*.txt 2>/dev/null || echo 'No crash files found'"

Write-Host ""
Write-Host "Done. Review the output above for crash details." -ForegroundColor Green

