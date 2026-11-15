#!/usr/bin/env bash
#
# Vendors AirSonic's sender library into ./sender.
#
# That library is licensed under the PolyForm Noncommercial License 1.0.0
# (personal, educational and other noncommercial use only). It is fetched at
# build time rather than committed here so its licence and copyright notice
# travel with the source. Commercial use needs the author's written consent.
set -euo pipefail

cd "$(dirname "$0")/.."

REF="97e98120686c0d96673e7f3138956450ee6c486c"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Fetching AirSonic sender @ ${REF:0:7} ..."
curl -fsSL "https://github.com/chunguangwei/AirSonic/archive/${REF}.tar.gz" -o "$TMP/src.tar.gz"
tar -xzf "$TMP/src.tar.gz" -C "$TMP"
SRC="$TMP/AirSonic-${REF}"

rm -rf sender
mkdir -p sender
cp -r "$SRC/airsonic-sender/src" sender/src
cp "$SRC/airsonic-sender/build.gradle.kts" sender/
if [ -f "$SRC/airsonic-sender/consumer-rules.pro" ]; then cp "$SRC/airsonic-sender/consumer-rules.pro" sender/; fi
if [ -f "$SRC/airsonic-sender/proguard-rules.pro" ]; then cp "$SRC/airsonic-sender/proguard-rules.pro" sender/; fi
cp "$SRC/LICENSE" sender/LICENSE

cp "$SRC/gradlew" .
cp "$SRC/gradlew.bat" .
mkdir -p gradle/wrapper
cp "$SRC/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/
chmod +x gradlew

echo "sender/ and the Gradle wrapper are ready. Next: ./gradlew :app:assembleDebug"
