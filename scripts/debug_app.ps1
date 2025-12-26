# Debug script for TMM Relay Android App
# This script captures logcat output to identify runtime errors

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "TMM Relay Debug Script" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

# Check if adb is available
$adbPath = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbPath) {
    Write-Host "ERROR: adb not found. Please ensure Android SDK platform-tools is in your PATH." -ForegroundColor Red
    Write-Host "Or set ADB path manually:" -ForegroundColor Yellow
    Write-Host '  $env:Path += ";C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools"' -ForegroundColor Yellow
    exit 1
}

Write-Host "Step 1: Checking connected devices..." -ForegroundColor Green
$devices = adb devices
Write-Host $devices

$deviceCount = ($devices | Select-String "device$" | Measure-Object).Count
if ($deviceCount -eq 0) {
    Write-Host "ERROR: No Android device connected or authorized." -ForegroundColor Red
    Write-Host "Please connect your device via USB and enable USB debugging." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "Step 2: Clearing previous logcat..." -ForegroundColor Green
adb logcat -c

Write-Host ""
Write-Host "Step 3: Starting logcat capture..." -ForegroundColor Green
Write-Host "Filtering for TMM Relay app logs..." -ForegroundColor Yellow
Write-Host ""
Write-Host "Press Ctrl+C to stop capturing logs" -ForegroundColor Yellow
Write-Host ""

# Create log file with timestamp
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$logFile = "debug_log_$timestamp.txt"

Write-Host "Logs will be saved to: $logFile" -ForegroundColor Cyan
Write-Host ""

# Filter for relevant packages and errors
adb logcat -v time | Tee-Object -FilePath $logFile | Select-String -Pattern "TmmRelay|CatalystClient|TrimbleLicensing|AndroidRuntime|FATAL|AndroidRuntime.*FATAL|Exception|Error" -Context 2,5

