#!/bin/bash

# MCServerHost Build Script
# This script downloads all dependencies and builds the MCServerHost Minecraft plugin
# Requires: curl, tar, unzip
# Downloads: Java 11 JDK (~185MB), Gradle 7.6.1 (~116MB)
# Note: Dependencies must be downloaded each time as they cannot be persisted

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="/tmp/mcsh-build-$$"
mkdir -p "$TMP_DIR"

echo "MCServerHost Build Script"
echo "========================="
echo ""

# Clean up on exit
cleanup() {
    echo "Cleaning up temporary files..."
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# Download Java 11 JDK
echo "[1/4] Downloading Java 11 JDK (~185MB)..."
if [ ! -f "$TMP_DIR/jdk11.tar.gz" ]; then
    curl -L https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.21%2B9/OpenJDK11U-jdk_x64_linux_hotspot_11.0.21_9.tar.gz -o "$TMP_DIR/jdk11.tar.gz" 2>&1 | grep -E "100|^curl"
fi
echo "Extracting Java 11..."
tar -xzf "$TMP_DIR/jdk11.tar.gz" -C "$TMP_DIR"
JAVA_HOME="$TMP_DIR/jdk-11.0.21+9"
export JAVA_HOME
echo "Java version:"
$JAVA_HOME/bin/java -version

# Download Gradle 7.6.1
echo ""
echo "[2/4] Downloading Gradle 7.6.1 (~116MB)..."
if [ ! -f "$TMP_DIR/gradle-7.zip" ]; then
    curl -L https://services.gradle.org/distributions/gradle-7.6.1-bin.zip -o "$TMP_DIR/gradle-7.zip" 2>&1 | grep -E "100|^curl"
fi
echo "Extracting Gradle..."
unzip -q "$TMP_DIR/gradle-7.zip" -d "$TMP_DIR"
GRADLE_HOME="$TMP_DIR/gradle-7.6.1"
export PATH="$JAVA_HOME/bin:$GRADLE_HOME/bin:$PATH"
echo "Gradle version:"
gradle --version | head -3

# Set up environment
echo ""
echo "[3/4] Preparing build..."
cd "$SCRIPT_DIR"

# Clean previous build
if [ -d "build" ]; then
    echo "Cleaning previous build artifacts..."
    gradle clean
fi

# Build project
echo ""
echo "[4/4] Building MCServerHost..."
gradle build

# Copy JAR to project root
echo ""
echo "Build successful! Copying JAR to project root..."
if [ -f "build/libs/MCServerHost-1.1.0.jar" ]; then
    cp build/libs/MCServerHost-1.1.0.jar "$SCRIPT_DIR/MCServerHost.jar"
    echo "JAR copied to: $SCRIPT_DIR/MCServerHost.jar"
    ls -lh "$SCRIPT_DIR/MCServerHost.jar"
else
    echo "ERROR: JAR not found at build/libs/MCServerHost-1.1.0.jar"
    exit 1
fi

echo ""
echo "Build complete!"
echo "Output: $SCRIPT_DIR/MCServerHost.jar"
