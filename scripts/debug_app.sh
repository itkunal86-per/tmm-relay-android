#!/bin/bash
# Debug script for TMM Relay Android App
# This script captures logcat output to identify runtime errors

echo "========================================="
echo "TMM Relay Debug Script"
echo "========================================="
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "ERROR: adb not found. Please ensure Android SDK platform-tools is in your PATH."
    exit 1
fi

echo "Step 1: Checking connected devices..."
adb devices

DEVICE_COUNT=$(adb devices | grep -c "device$")
if [ $DEVICE_COUNT -eq 0 ]; then
    echo "ERROR: No Android device connected or authorized."
    echo "Please connect your device via USB and enable USB debugging."
    exit 1
fi

echo ""
echo "Step 2: Clearing previous logcat..."
adb logcat -c

echo ""
echo "Step 3: Starting logcat capture..."
echo "Filtering for TMM Relay app logs..."
echo ""
echo "Press Ctrl+C to stop capturing logs"
echo ""

# Create log file with timestamp
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
LOG_FILE="debug_log_$TIMESTAMP.txt"

echo "Logs will be saved to: $LOG_FILE"
echo ""

# Filter for relevant packages and errors
adb logcat -v time | tee "$LOG_FILE" | grep --line-buffered -E "TmmRelay|CatalystClient|TrimbleLicensing|AndroidRuntime|FATAL|Exception|Error" -A 5 -B 2

