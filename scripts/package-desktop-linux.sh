#!/usr/bin/env bash
# Smart VRMS — Ubuntu 24.04 LTS (x64) Native DEB Packaging (Stage 9)
# Requires JDK 17 with jlink and jpackage on an Ubuntu x64 host/runner.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== Packaging Smart VRMS for Ubuntu 24.04 LTS (x64) ==="

mvn -B -DskipTests package

DIST_DIR="dist"
INPUT_DIR="${DIST_DIR}/client-jars"
OUTPUT_DIR="${DIST_DIR}/package-linux64"
RUNTIME_DIR="${DIST_DIR}/runtime-linux64"

mkdir -p "${INPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
rm -rf "${RUNTIME_DIR}"

# Collect client JARs only
cp contracts/target/contracts-0.1.0-SNAPSHOT.jar "${INPUT_DIR}/"
cp application/target/application-0.1.0-SNAPSHOT.jar "${INPUT_DIR}/"
cp desktop/target/desktop-0.1.0-SNAPSHOT.jar "${INPUT_DIR}/"
cp "${HOME}/.m2/repository/com/google/code/gson/gson/2.15.0/gson-2.15.0.jar" "${INPUT_DIR}/"
cp "${HOME}/.m2/repository/com/google/errorprone/error_prone_annotations/2.50.0/error_prone_annotations-2.50.0.jar" "${INPUT_DIR}/"

# Build minimal runtime
jlink --no-header-files --no-man-pages --strip-debug --compress=2 \
      --add-modules java.base,java.desktop,java.logging,java.net.http,java.sql,java.xml,jdk.crypto.ec,jdk.charsets \
      --output "${RUNTIME_DIR}"

# Run jpackage to produce .deb package
jpackage \
    --type deb \
    --input "${INPUT_DIR}" \
    --dest "${OUTPUT_DIR}" \
    --name "smart-vrms" \
    --main-jar "desktop-0.1.0-SNAPSHOT.jar" \
    --main-class "in.smartvrms.desktop.DesktopMain" \
    --runtime-image "${RUNTIME_DIR}" \
    --app-version "0.1.1" \
    --vendor "Smart VRMS Team" \
    --copyright "Copyright 2026 Smart VRMS" \
    --description "Smart VRMS - Professional Vehicle Rental Management System" \
    --linux-shortcut \
    --linux-menu-group "Office;Finance;" \
    --linux-package-name "smart-vrms"

echo "=== SUCCESS: Linux native DEB package created at ${OUTPUT_DIR} ==="
ls -lh "${OUTPUT_DIR}"
