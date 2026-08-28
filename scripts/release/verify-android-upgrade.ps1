[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaselineApk,
    [Parameter(Mandatory = $true)][string]$CandidateApk,
    [string]$ExpectedApplicationId = 'com.hpre.app',
    [string]$DeviceSerial,
    [switch]$StaticOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$settingsLabel = '"C\u00e0i \u0111\u1eb7t"' | ConvertFrom-Json
$backgroundPlaybackLabel = '"Ph\u00e1t trong n\u1ec1n"' | ConvertFrom-Json
$updatesLabel = '"C\u1eadp nh\u1eadt \u1ee9ng d\u1ee5ng"' | ConvertFrom-Json

function Fail([string]$Message) {
    throw "UPGRADE CHECK FAILED: $Message"
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$FailureMessage = 'Native command failed'
    )
    $output = @(& $FilePath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Fail "$FailureMessage (exit $LASTEXITCODE)"
    }
    return $output
}

function Resolve-AndroidSdk {
    $roots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($root in $roots) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            return (Resolve-Path -LiteralPath $root).Path
        }
    }
    Fail 'ANDROID_SDK_ROOT or ANDROID_HOME must point to an installed Android SDK.'
}

function Resolve-BuildTool {
    param([string]$SdkRoot, [string]$Name)
    $candidates = @(Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
        ForEach-Object { Join-Path $_.FullName $Name } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($candidates.Count -eq 0) { Fail "Android build tool '$Name' was not found." }
    return $candidates[0]
}

function Get-ApkFacts {
    param(
        [string]$ApkPath,
        [string]$ApkSigner,
        [string]$Aapt
    )
    $item = Get-Item -LiteralPath $ApkPath -ErrorAction Stop
    if (-not $item.PSIsContainer -and $item.Length -gt 0) {
        $signature = Invoke-Checked $ApkSigner @('verify', '--verbose', '--print-certs', $item.FullName) 'APK signature verification failed'
        $badging = Invoke-Checked $Aapt @('dump', 'badging', $item.FullName) 'APK metadata extraction failed'
        $signatureText = $signature -join "`n"
        $badgingText = $badging -join "`n"

        $packageMatch = [regex]::Match($badgingText, "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", 'IgnoreCase')
        if (-not $packageMatch.Success) { Fail "Could not parse package metadata from '$($item.Name)'." }
        $certificateMatch = [regex]::Match($signatureText, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-f:]+)', 'IgnoreCase')
        if (-not $certificateMatch.Success) { Fail "Could not parse signer certificate from '$($item.Name)'." }

        return [pscustomobject]@{
            Path = $item.FullName
            Name = $item.Name
            Size = $item.Length
            ApplicationId = $packageMatch.Groups[1].Value
            VersionCode = [long]$packageMatch.Groups[2].Value
            VersionName = $packageMatch.Groups[3].Value
            CertificateSha256 = $certificateMatch.Groups[1].Value.Replace(':', '').ToUpperInvariant()
        }
    }
    Fail "APK is missing or empty: $ApkPath"
}

function Convert-StrictVersion([string]$Value) {
    if ($Value -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
        Fail "Version name is not strict MAJOR.MINOR.PATCH: $Value"
    }
    try { return [version]$Value } catch { Fail "Version name is outside supported numeric range: $Value" }
}

function Invoke-Adb {
    param([string]$Adb, [string]$Serial, [string[]]$Arguments, [string]$FailureMessage)
    return Invoke-Checked $Adb (@('-s', $Serial) + $Arguments) $FailureMessage
}

function Get-OnlineDevice {
    param([string]$Adb, [string]$RequestedSerial)
    $lines = Invoke-Checked $Adb @('devices') 'Could not list ADB devices'
    $online = @($lines | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $matches[1] }
    })
    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        if ($online -notcontains $RequestedSerial) { Fail "Requested ADB device is not online: $RequestedSerial" }
        return $RequestedSerial
    }
    if ($online.Count -ne 1) { Fail "Expected exactly one online ADB device; found $($online.Count). Use -DeviceSerial." }
    return $online[0]
}

function Get-BoundsCenter([string]$Bounds) {
    $match = [regex]::Match($Bounds, '^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$')
    if (-not $match.Success) { Fail "Could not parse UI bounds: $Bounds" }
    return [pscustomobject]@{
        X = [int](($match.Groups[1].Value -as [int]) + ($match.Groups[3].Value -as [int])) / 2
        Y = [int](($match.Groups[2].Value -as [int]) + ($match.Groups[4].Value -as [int])) / 2
    }
}

function Get-UiHierarchy {
    param([string]$Adb, [string]$Serial, [string]$LocalPath, [string]$RemoteName)
    $remote = "/sdcard/$RemoteName"
    Invoke-Adb $Adb $Serial @('shell', 'uiautomator', 'dump', $remote) 'UIAutomator dump failed' | Out-Null
    Invoke-Adb $Adb $Serial @('pull', $remote, $LocalPath) 'Could not pull UI hierarchy' | Out-Null
    Invoke-Adb $Adb $Serial @('shell', 'rm', '-f', $remote) 'Could not remove remote UI hierarchy' | Out-Null
    return [xml](Get-Content -LiteralPath $LocalPath -Raw -Encoding UTF8)
}

function Find-UiNode {
    param([xml]$Hierarchy, [string]$Text, [string]$Description)
    $nodes = @($Hierarchy.SelectNodes('//node'))
    $matches = @($nodes | Where-Object {
        ($Text -and $_.GetAttribute('text') -eq $Text) -or
            ($Description -and $_.GetAttribute('content-desc') -eq $Description)
    })
    if ($matches.Count -ne 1) { Fail "Expected one UI node for '$Text$Description'; found $($matches.Count)." }
    return $matches[0]
}

function Open-Settings {
    param([string]$Adb, [string]$Serial, [string]$TempDirectory)
    Invoke-Adb $Adb $Serial @('shell', 'am', 'force-stop', 'com.hpre.app') 'Could not stop HPre' | Out-Null
    Invoke-Adb $Adb $Serial @('shell', 'am', 'start', '-W', '-n', 'com.hpre.app/.MainActivity') 'Could not launch HPre' | Out-Null
    Start-Sleep -Seconds 2
    $homeDump = Join-Path $TempDirectory 'home.xml'
    $home = Get-UiHierarchy $Adb $Serial $homeDump 'hpre-upgrade-home.xml'
    $settingsNode = Find-UiNode $home '' $script:settingsLabel
    $center = Get-BoundsCenter $settingsNode.GetAttribute('bounds')
    Invoke-Adb $Adb $Serial @('shell', 'input', 'tap', "$($center.X)", "$($center.Y)") 'Could not open Settings' | Out-Null
    Start-Sleep -Seconds 1
}

function Set-BackgroundPlaybackDisabled {
    param([string]$Adb, [string]$Serial, [string]$TempDirectory, [string]$DumpName)
    $dump = Join-Path $TempDirectory $DumpName
    $hierarchy = Get-UiHierarchy $Adb $Serial $dump "hpre-$DumpName"
    $label = Find-UiNode $hierarchy $script:backgroundPlaybackLabel ''
    $labelCenter = Get-BoundsCenter $label.GetAttribute('bounds')
    $switches = @($hierarchy.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('class') -eq 'android.widget.Switch' -or
            $_.GetAttribute('content-desc') -eq $script:backgroundPlaybackLabel
    })
    $near = @($switches | ForEach-Object {
        $center = Get-BoundsCenter $_.GetAttribute('bounds')
        [pscustomobject]@{ Node = $_; Center = $center; Distance = [math]::Abs($center.Y - $labelCenter.Y) }
    } | Sort-Object Distance)
    if ($near.Count -eq 0 -or $near[0].Distance -gt 100) {
        Fail 'Could not identify the background playback switch.'
    }
    $switch = $near[0]
    if ($switch.Node.GetAttribute('checked') -eq 'true') {
        Invoke-Adb $Adb $Serial @('shell', 'input', 'tap', "$($switch.Center.X)", "$($switch.Center.Y)") 'Could not toggle background playback' | Out-Null
        Start-Sleep -Milliseconds 500
        $hierarchy = Get-UiHierarchy $Adb $Serial $dump "hpre-$DumpName"
        $switches = @($hierarchy.SelectNodes('//node') | Where-Object {
            $_.GetAttribute('class') -eq 'android.widget.Switch'
        })
        $switch = @($switches | ForEach-Object {
            $center = Get-BoundsCenter $_.GetAttribute('bounds')
            [pscustomobject]@{ Node = $_; Distance = [math]::Abs($center.Y - $labelCenter.Y) }
        } | Sort-Object Distance)[0]
    }
    if ($switch.Node.GetAttribute('checked') -ne 'false') {
        Fail 'Background playback persistence marker is not disabled.'
    }
}

$sdkRoot = Resolve-AndroidSdk
$apkSignerName = if ($env:OS -eq 'Windows_NT') { 'apksigner.bat' } else { 'apksigner' }
$aaptName = if ($env:OS -eq 'Windows_NT') { 'aapt.exe' } else { 'aapt' }
$adbName = if ($env:OS -eq 'Windows_NT') { 'adb.exe' } else { 'adb' }
$apkSigner = Resolve-BuildTool $sdkRoot $apkSignerName
$aapt = Resolve-BuildTool $sdkRoot $aaptName
$adb = Join-Path $sdkRoot "platform-tools\$adbName"
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { Fail 'ADB was not found in the Android SDK.' }

$baseline = Get-ApkFacts $BaselineApk $apkSigner $aapt
$candidate = Get-ApkFacts $CandidateApk $apkSigner $aapt
$baselineVersion = Convert-StrictVersion $baseline.VersionName
$candidateVersion = Convert-StrictVersion $candidate.VersionName

if ($baseline.ApplicationId -ne $ExpectedApplicationId -or $candidate.ApplicationId -ne $ExpectedApplicationId) {
    Fail 'Baseline and candidate application IDs must match the expected package.'
}
if ($candidateVersion -le $baselineVersion) { Fail 'Candidate versionName must be greater than baseline versionName.' }
if ($candidate.VersionCode -le $baseline.VersionCode) { Fail 'Candidate versionCode must be greater than baseline versionCode.' }
if ($candidate.CertificateSha256 -ne $baseline.CertificateSha256) { Fail 'Baseline and candidate signing certificates do not match.' }
if ($candidate.Name -notmatch '^HPre-.*\.apk$' -or $candidate.Name -match '(?i)(debug|unsigned)') {
    Fail 'Candidate filename must be HPre-*.apk and must not be debug or unsigned.'
}

"STATIC UPGRADE CHECK PASS"
"Application ID: $ExpectedApplicationId"
"Baseline: $($baseline.VersionName) ($($baseline.VersionCode))"
"Candidate: $($candidate.VersionName) ($($candidate.VersionCode))"
"Certificate SHA-256: $($candidate.CertificateSha256)"

if ($StaticOnly) { exit 0 }

$serial = Get-OnlineDevice $adb $DeviceSerial
$tempDirectory = Join-Path $env:TEMP ("hpre-upgrade-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDirectory | Out-Null
try {
    & $adb -s $serial uninstall $ExpectedApplicationId 2>&1 | Out-Null
    $installBaseline = Invoke-Adb $adb $serial @('install', $baseline.Path) 'Could not install baseline APK'
    if (($installBaseline -join "`n") -notmatch 'Success') { Fail 'Baseline installation did not report Success.' }
    $baselinePackage = Invoke-Adb $adb $serial @('shell', 'dumpsys', 'package', $ExpectedApplicationId) 'Could not inspect baseline package'
    if (($baselinePackage -join "`n") -notmatch "versionCode=$($baseline.VersionCode)" -or ($baselinePackage -join "`n") -notmatch "versionName=$([regex]::Escape($baseline.VersionName))") {
        Fail 'Installed baseline version does not match the baseline APK.'
    }

    Open-Settings $adb $serial $tempDirectory
    Set-BackgroundPlaybackDisabled $adb $serial $tempDirectory 'baseline-settings.xml'

    $installCandidate = Invoke-Adb $adb $serial @('install', '-r', $candidate.Path) 'Could not install candidate over baseline'
    if (($installCandidate -join "`n") -notmatch 'Success') { Fail 'Candidate install-over did not report Success.' }
    $candidatePackage = Invoke-Adb $adb $serial @('shell', 'dumpsys', 'package', $ExpectedApplicationId) 'Could not inspect upgraded package'
    if (($candidatePackage -join "`n") -notmatch "versionCode=$($candidate.VersionCode)" -or ($candidatePackage -join "`n") -notmatch "versionName=$([regex]::Escape($candidate.VersionName))") {
        Fail 'Installed candidate version does not match the candidate APK.'
    }

    Open-Settings $adb $serial $tempDirectory
    Set-BackgroundPlaybackDisabled $adb $serial $tempDirectory 'candidate-settings.xml'
    for ($i = 0; $i -lt 4; $i++) {
        Invoke-Adb $adb $serial @('shell', 'input', 'swipe', '500', '1600', '500', '500', '300') 'Could not scroll Settings' | Out-Null
        Start-Sleep -Milliseconds 300
    }
    $smoke = Get-UiHierarchy $adb $serial (Join-Path $tempDirectory 'smoke.xml') 'hpre-upgrade-smoke.xml'
    [void](Find-UiNode $smoke $updatesLabel '')
    "INSTALL-OVER UPGRADE CHECK PASS on $serial"
} finally {
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force -ErrorAction SilentlyContinue
}
