# PowerShell script to capture crash logs from Android device
# This will filter logcat for the app and show crash-related logs

Write-Host "=== TMM Relay Android - Crash Log Capture ===" -ForegroundColor Cyan
Write-Host ""

# Check if adb is available
$adbCheck = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCheck) {
    Write-Host "ERROR: adb not found. Please ensure Android SDK platform-tools are in your PATH." -ForegroundColor Red
    exit 1
}

# Get package name
$packageName = "com.hirenq.tmmrelay"

Write-Host "Clearing old logcat..." -ForegroundColor Yellow
adb logcat -c

Write-Host ""
Write-Host "Starting logcat capture for package: $packageName" -ForegroundColor Green
Write-Host "Press Ctrl+C to stop capturing" -ForegroundColor Yellow
Write-Host ""
Write-Host "=== Waiting for app activity... ===" -ForegroundColor Cyan
Write-Host ""

# Filter logcat for the app and important system messages
adb logcat -v time *:E *:F AndroidRuntime:E System.err:E $packageName:* | Select-String -Pattern "$packageName|AndroidRuntime|FATAL|Exception|Error|Crash" -Context 0,5

