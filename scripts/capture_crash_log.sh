#!/bin/bash
# Bash script to capture crash logs from Android device
# This will filter logcat for the app and show crash-related logs

echo "=== TMM Relay Android - Crash Log Capture ==="
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "ERROR: adb not found. Please ensure Android SDK platform-tools are in your PATH."
    exit 1
fi

# Get package name
PACKAGE_NAME="com.hirenq.tmmrelay"

echo "Clearing old logcat..."
adb logcat -c

echo ""
echo "Starting logcat capture for package: $PACKAGE_NAME"
echo "Press Ctrl+C to stop capturing"
echo ""
echo "=== Waiting for app activity... ==="
echo ""

# Filter logcat for the app and important system messages
adb logcat -v time *:E *:F AndroidRuntime:E System.err:E ${PACKAGE_NAME}:* | grep --line-buffered -E "${PACKAGE_NAME}|AndroidRuntime|FATAL|Exception|Error|Crash"

