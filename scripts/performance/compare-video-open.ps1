#Requires -Version 5.1
<#
.SYNOPSIS
    Compares HPre video-open latency between baseline and candidate benchmark logs.
#>

[CmdletBinding()]
param(
    [string]$BaselineLog,
    [string]$CandidateLog,
    [ValidateRange(1, 1000)][int]$MinimumSamples = 10,
    [ValidateRange(0.01, 99.99)][double]$RequiredImprovementPercent = 15.0,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Median([double[]]$Values) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { throw 'Median requires at least one value.' }
    $middle = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return (([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0)
}

function Parse-PerformanceLog([string[]]$Lines) {
    $metadata = $null
    $rawSessions = [System.Collections.Generic.Dictionary[long, System.Collections.Generic.List[PSObject]]]::new()
    $malformedCount = 0

    foreach ($line in $Lines) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }

        if ($trimmed.StartsWith('# METADATA')) {
            $metadata = $trimmed.Substring(10).Trim()
            continue
        }

        # Expect format: ... [optional logcat prefix] EVENT generation=N elapsedMs=N [segment=SEGMENT segmentMs=N] [category=CATEGORY]
        $match = [regex]::Match($trimmed, '(?:.*HPrePerformance:\s*)?([A-Z_]+)\s+generation=(\d+)\s+elapsedMs=(\d+)(?:\s+segment=([A-Z_]+))?(?:\s+segmentMs=(\d+))?(?:\s+category=([A-Za-z0-9_:]+))?')
        if (-not $match.Success) {
            $malformedCount++
            continue
        }

        $event = $match.Groups[1].Value
        $gen = [long]$match.Groups[2].Value
        $elapsed = [long]$match.Groups[3].Value
        $segment = if ($match.Groups[4].Success) { $match.Groups[4].Value } else { $null }
        $segmentMs = if ($match.Groups[5].Success) { [long]$match.Groups[5].Value } else { $null }
        $category = if ($match.Groups[6].Success) { $match.Groups[6].Value } else { $null }

        $rec = [PSCustomObject]@{
            Event = $event
            Generation = $gen
            ElapsedMs = $elapsed
            Segment = $segment
            SegmentMs = $segmentMs
            Category = $category
        }

        if (-not $rawSessions.ContainsKey($gen)) {
            $rawSessions[$gen] = [System.Collections.Generic.List[PSObject]]::new()
        }
        $rawSessions[$gen].Add($rec)
    }

    $validSamples = [System.Collections.Generic.List[PSObject]]::new()
    $failureCount = $malformedCount
    $observedCategory = $null

    foreach ($gen in @($rawSessions.Keys)) {
        $recs = @($rawSessions[$gen])
        $starts = @($recs | Where-Object { $_.Event -eq 'VIDEO_OPEN_START' })
        $firstFrames = @($recs | Where-Object { $_.Event -eq 'FIRST_FRAME' })
        $errors = @($recs | Where-Object { $_.Event -eq 'PLAYBACK_ERROR' })

        if ($errors.Count -gt 0 -or $starts.Count -ne 1 -or $firstFrames.Count -ne 1) {
            $failureCount++
            continue
        }

        # Terminal events: FIRST_FRAME and PLAYBACK_ERROR
        $terminals = @($recs | Where-Object { $_.Event -eq 'FIRST_FRAME' -or $_.Event -eq 'PLAYBACK_ERROR' })
        if ($terminals.Count -ne 1) {
            $failureCount++
            continue
        }

        $ff = $firstFrames[0]
        if ($ff.ElapsedMs -lt 0) {
            $failureCount++
            continue
        }

        $seg1 = @($recs | Where-Object { $_.Segment -eq 'STREAM_RESOLVE_TO_STREAM_INFO_READY' })
        $seg2 = @($recs | Where-Object { $_.Segment -eq 'STREAM_INFO_READY_TO_PLAYER_PREPARE' })
        $seg3 = @($recs | Where-Object { $_.Segment -eq 'PLAYER_PREPARE_TO_FIRST_FRAME' })

        if ($seg1.Count -ne 1 -or $seg2.Count -ne 1 -or $seg3.Count -ne 1) {
            $failureCount++
            continue
        }

        $s1Ms = $seg1[0].SegmentMs
        $s2Ms = $seg2[0].SegmentMs
        $s3Ms = $seg3[0].SegmentMs

        if ($null -eq $s1Ms -or $null -eq $s2Ms -or $null -eq $s3Ms -or
            $s1Ms -lt 0 -or $s2Ms -lt 0 -or $s3Ms -lt 0) {
            $failureCount++
            continue
        }

        $sumSeg = $s1Ms + $s2Ms + $s3Ms
        if ($ff.ElapsedMs -lt $sumSeg) {
            $failureCount++
            continue
        }

        if ($null -ne $ff.Category) {
            if ($null -eq $observedCategory) {
                $observedCategory = $ff.Category
            }
        }

        $validSamples.Add([PSCustomObject]@{
            Generation = $gen
            TotalElapsedMs = [double]$ff.ElapsedMs
            Segment1Ms = [double]$s1Ms
            Segment2Ms = [double]$s2Ms
            Segment3Ms = [double]$s3Ms
            Category = $ff.Category
        })
    }

    return [PSCustomObject]@{
        Metadata = $metadata
        Category = $observedCategory
        Valid = @($validSamples)
        Failures = $failureCount
    }
}

function Invoke-Comparator([string[]]$BaselineLines, [string[]]$CandidateLines, [int]$MinSamples, [double]$ReqImprovement) {
    $baseline = Parse-PerformanceLog $BaselineLines
    $candidate = Parse-PerformanceLog $CandidateLines

    $hasMetadataMatch = ($null -ne $baseline.Metadata -and $null -ne $candidate.Metadata -and $baseline.Metadata -eq $candidate.Metadata)
    $hasCategoryMatch = ($null -ne $baseline.Category -and $null -ne $candidate.Category -and $baseline.Category -eq $candidate.Category)

    $baselineValid = @($baseline.Valid)
    $candidateValid = @($candidate.Valid)

    if ($baselineValid.Count -lt $MinSamples -or $candidateValid.Count -lt $MinSamples) {
        return [PSCustomObject]@{
            Pass = $false
            Reason = "Insufficient samples: Baseline=$($baselineValid.Count), Candidate=$($candidateValid.Count), Minimum=$MinSamples"
            Baseline = $baseline
            Candidate = $candidate
            ImprovementPercent = 0.0
        }
    }

    if (-not $hasMetadataMatch) {
        return [PSCustomObject]@{
            Pass = $false
            Reason = "Procedure metadata mismatch: Baseline='$($baseline.Metadata)' vs Candidate='$($candidate.Metadata)'"
            Baseline = $baseline
            Candidate = $candidate
            ImprovementPercent = 0.0
        }
    }

    if (-not $hasCategoryMatch) {
        return [PSCustomObject]@{
            Pass = $false
            Reason = "Stream category mismatch: Baseline='$($baseline.Category)' vs Candidate='$($candidate.Category)'"
            Baseline = $baseline
            Candidate = $candidate
            ImprovementPercent = 0.0
        }
    }

    $baselineTotals = @($baselineValid | ForEach-Object { [double]$_.TotalElapsedMs })
    $candidateTotals = @($candidateValid | ForEach-Object { [double]$_.TotalElapsedMs })

    $baselineMedian = Get-Median $baselineTotals
    $candidateMedian = Get-Median $candidateTotals

    if ($baselineMedian -le 0) {
        throw "Baseline median must be greater than zero. Got $baselineMedian"
    }

    $improvement = (($baselineMedian - $candidateMedian) / $baselineMedian) * 100.0
    $passes = ($improvement -ge $ReqImprovement)

    return [PSCustomObject]@{
        Pass = $passes
        Reason = if ($passes) { "PASS" } else { "Improvement $improvement% is below required $ReqImprovement%" }
        ImprovementPercent = $improvement
        BaselineMedian = $baselineMedian
        CandidateMedian = $candidateMedian
        Baseline = $baseline
        Candidate = $candidate
    }
}

function Run-SelfTest() {
    Write-Host "Running compare-video-open.ps1 SelfTest..."

    $metadataLine = "# METADATA avd=FlowTubeApi35 video=dQw4w9WgXcQ network=wifi appState=clean cache=reset quality=auto animations=off"

    # Fixture 1: 10 baseline median 1000ms, 10 candidate median 800ms -> 20% improvement -> PASS
    $baseLines = [System.Collections.Generic.List[string]]::new()
    $baseLines.Add($metadataLine)
    for ($i = 1; $i -le 10; $i++) {
        $baseLines.Add("VIDEO_OPEN_START generation=$i elapsedMs=0")
        $baseLines.Add("STREAM_INFO_READY generation=$i elapsedMs=200 segment=STREAM_RESOLVE_TO_STREAM_INFO_READY segmentMs=200")
        $baseLines.Add("PLAYER_PREPARE generation=$i elapsedMs=500 segment=STREAM_INFO_READY_TO_PLAYER_PREPARE segmentMs=300")
        $baseLines.Add("FIRST_FRAME generation=$i elapsedMs=1000 segment=PLAYER_PREPARE_TO_FIRST_FRAME segmentMs=500 category=PROGRESSIVE")
    }

    $candLines = [System.Collections.Generic.List[string]]::new()
    $candLines.Add($metadataLine)
    for ($i = 1; $i -le 10; $i++) {
        $candLines.Add("VIDEO_OPEN_START generation=$i elapsedMs=0")
        $candLines.Add("STREAM_INFO_READY generation=$i elapsedMs=150 segment=STREAM_RESOLVE_TO_STREAM_INFO_READY segmentMs=150")
        $candLines.Add("PLAYER_PREPARE generation=$i elapsedMs=400 segment=STREAM_INFO_READY_TO_PLAYER_PREPARE segmentMs=250")
        $candLines.Add("FIRST_FRAME generation=$i elapsedMs=800 segment=PLAYER_PREPARE_TO_FIRST_FRAME segmentMs=400 category=PROGRESSIVE")
    }

    $res1 = Invoke-Comparator $baseLines $candLines 10 15.0
    if (-not $res1.Pass -or [math]::Abs($res1.ImprovementPercent - 20.0) -gt 0.001) {
        throw "Self-test 1 failed: Expected PASS 20%, got Pass=$($res1.Pass), Imp=$($res1.ImprovementPercent)%"
    }

    # Fixture 2: 9 candidate samples -> must be rejected
    $cand9Lines = [System.Collections.Generic.List[string]]::new()
    $cand9Lines.Add($metadataLine)
    for ($i = 1; $i -le 9; $i++) {
        $cand9Lines.Add("VIDEO_OPEN_START generation=$i elapsedMs=0")
        $cand9Lines.Add("STREAM_INFO_READY generation=$i elapsedMs=150 segment=STREAM_RESOLVE_TO_STREAM_INFO_READY segmentMs=150")
        $cand9Lines.Add("PLAYER_PREPARE generation=$i elapsedMs=400 segment=STREAM_INFO_READY_TO_PLAYER_PREPARE segmentMs=250")
        $cand9Lines.Add("FIRST_FRAME generation=$i elapsedMs=800 segment=PLAYER_PREPARE_TO_FIRST_FRAME segmentMs=400 category=PROGRESSIVE")
    }
    $res2 = Invoke-Comparator $baseLines $cand9Lines 10 15.0
    if ($res2.Pass) {
        throw "Self-test 2 failed: 9 samples should have been rejected."
    }

    # Fixture 3: Missing segment duration increments failure
    $missingSegLines = [System.Collections.Generic.List[string]]::new()
    $missingSegLines.Add($metadataLine)
    $missingSegLines.Add("VIDEO_OPEN_START generation=1 elapsedMs=0")
    $missingSegLines.Add("STREAM_INFO_READY generation=1 elapsedMs=150 segment=STREAM_RESOLVE_TO_STREAM_INFO_READY segmentMs=150")
    $missingSegLines.Add("FIRST_FRAME generation=1 elapsedMs=800 segment=PLAYER_PREPARE_TO_FIRST_FRAME segmentMs=400 category=PROGRESSIVE")
    $parsed3 = Parse-PerformanceLog $missingSegLines
    if ($parsed3.Failures -ne 1) {
        throw "Self-test 3 failed: Expected 1 failure for missing segment, got $($parsed3.Failures)"
    }

    # Fixture 4: Metadata mismatch
    $diffMetaLines = [System.Collections.Generic.List[string]]::new()
    $diffMetaLines.Add("# METADATA avd=OtherAvd video=dQw4w9WgXcQ network=wifi appState=clean cache=reset quality=auto animations=off")
    for ($i = 1; $i -le 10; $i++) {
        $diffMetaLines.Add("VIDEO_OPEN_START generation=$i elapsedMs=0")
        $diffMetaLines.Add("STREAM_INFO_READY generation=$i elapsedMs=150 segment=STREAM_RESOLVE_TO_STREAM_INFO_READY segmentMs=150")
        $diffMetaLines.Add("PLAYER_PREPARE generation=$i elapsedMs=400 segment=STREAM_INFO_READY_TO_PLAYER_PREPARE segmentMs=250")
        $diffMetaLines.Add("FIRST_FRAME generation=$i elapsedMs=800 segment=PLAYER_PREPARE_TO_FIRST_FRAME segmentMs=400 category=PROGRESSIVE")
    }
    $res4 = Invoke-Comparator $baseLines $diffMetaLines 10 15.0
    if ($res4.Pass) {
        throw "Self-test 4 failed: Metadata mismatch should be rejected."
    }

    Write-Host "SELF-TEST PASS"
}

if ($SelfTest) {
    Run-SelfTest
    exit 0
}

if (-not $BaselineLog -or -not (Test-Path -LiteralPath $BaselineLog)) {
    Write-Error "Baseline log file not found: $BaselineLog"
    exit 1
}

if (-not $CandidateLog -or -not (Test-Path -LiteralPath $CandidateLog)) {
    Write-Error "Candidate log file not found: $CandidateLog"
    exit 1
}

$baseLines = Get-Content -LiteralPath $BaselineLog
$candLines = Get-Content -LiteralPath $CandidateLog

$result = Invoke-Comparator $baseLines $candLines $MinimumSamples $RequiredImprovementPercent

Write-Host "=== HPre Video Open Comparison ==="
Write-Host "Baseline Valid Samples : $($result.Baseline.Valid.Count) (Failures: $($result.Baseline.Failures))"
Write-Host "Candidate Valid Samples: $($result.Candidate.Valid.Count) (Failures: $($result.Candidate.Failures))"
Write-Host "Baseline Median Total  : $($result.BaselineMedian) ms"
Write-Host "Candidate Median Total : $($result.CandidateMedian) ms"
Write-Host "Improvement            : $([math]::Round($result.ImprovementPercent, 2))%"
Write-Host "Verdict                : $($result.Reason)"

if ($result.Pass) {
    Write-Host "BENCHMARK PASS"
    exit 0
} else {
    Write-Error "BENCHMARK FAIL: $($result.Reason)"
    exit 2
}
