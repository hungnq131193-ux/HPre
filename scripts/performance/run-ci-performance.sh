#!/usr/bin/env bash
set -euo pipefail

artifact_dir="performance-artifacts"
main_profile="app/src/main/baseline-prof.txt"

mkdir -p "$artifact_dir"
cat > "$artifact_dir/context.txt" <<'EOF'
Emulator-only performance run.
API level: 34. Architecture: x86_64. Runner: GitHub hosted ubuntu-latest.
Do not compare these timings with physical-device results.
EOF

./gradlew :app:generateReleaseBaselineProfile --no-daemon --stacktrace

generated_profile="$(find app/src -type f -path '*/generated/baselineProfiles/baseline-prof.txt' -print -quit)"
test -n "$generated_profile"
test -s "$generated_profile"

generated_dir="$(dirname "$generated_profile")"
cp "$main_profile" "$artifact_dir/previous-baseline-prof.txt"
cp "$generated_profile" "$main_profile"
cp "$generated_dir"/*.txt "$artifact_dir"/

./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest --no-daemon --stacktrace
