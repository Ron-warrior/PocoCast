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

# tvOS 27 only runs the AirPlay pairing ceremony for a client that names itself:
# Apple's log says "passcodeString is empty or clientName is nil. Bailing." and
# the accessory rejects the identity exchange at M5 with kTLVError_Unknown.
# Upstream hardcodes the request headers, so add the name here (the header goes
# out on /pair-pin-start and every /pair-setup, matching pyatv's fix).
python3 - sender/src/main/java/com/airsonic/sender/pairing/AirplayHttpClient.kt <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
source = path.read_text()
if "X-Apple-Client-Name" not in source:
    needle = 'append("User-Agent: AirPlay/950.7.1\\r\\n")'
    if needle not in source:
        sys.exit("bootstrap: could not apply the tvOS 27 client-name patch")
    source = source.replace(
        needle,
        needle + '\n            append("X-Apple-Client-Name: PocoCast\\r\\n")',
        1,
    )
    path.write_text(source)
    print("Patched AirplayHttpClient with X-Apple-Client-Name")
PY

cp "$SRC/gradlew" .
cp "$SRC/gradlew.bat" .
mkdir -p gradle/wrapper
cp "$SRC/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/
chmod +x gradlew

echo "sender/ and the Gradle wrapper are ready. Next: ./gradlew :app:assembleDebug"
