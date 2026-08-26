# Upstream Smoke Test

The upstream suite is diagnostic and separate from deterministic CI. Supply an approved public query at invocation time; never hard-code content IDs or stream URLs.

```powershell
$env:JAVA_HOME='C:\Users\HUNG\AppData\Local\Temp\opencode\jdk17\jdk-17.0.14+7'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.hpreSmokeQuery=<approved-query>'
```

The test evaluates at most five search candidates. For each candidate it maps search, video details and stream information into provider-neutral domain types, then performs at most three bounded direct-range or manifest probes. It does not instantiate Media3 and does not prove decode, rendering or playback.

Accepted outcomes:

- A mapped domain result with a bounded accessibility probe that verifies a direct range response or recognized HLS/DASH manifest.
- A mapped domain error such as `NetworkError`, `RateLimited`, `ContentUnavailable`, `AgeRestricted`, `GeoRestricted`, `LoginRequired`, `UnsupportedFormat` or `ExtractionFailed`.

Privacy requirements:

- Do not persist stream URLs, input queries, content identifiers, cookies, authorization data, tokens or response bodies.
- Diagnostic output may contain only candidate index, stream type/container, HTTP status, probe outcome and mapped error category.
- A mapped upstream failure is evidence of current provider behavior, not test success and not grounds to bypass access controls.

Real playback remains a separate gate. Run the live playback instrumentation with externally supplied arguments and record first-frame render, playback advance, seek and available-quality switch facts without sensitive values.
