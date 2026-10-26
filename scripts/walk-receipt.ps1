<#
.SYNOPSIS
    The receipt a whole-app walk leaves, and what makes one good enough to publish a bundle on.

.DESCRIPTION
    Dot-sourced by release/release.ps1, whose publish stage refuses to publish a bundle without a
    receipt that backs it, and by walk-app.ps1, which writes it. Nothing goes public until an APK
    patched from the exact bundle being released has been walked end to end on a release phone or
    an emulator and came back PASS: every required step reached its own screen, with no Messenger
    crash and nothing in the way.

    The walk writes walk-receipt.json with New-WalkReceipt. It names the SHA-256 of the .mpp the
    walked APK was patched with, and publish holds that to the signed bundle it's about to upload,
    so a walk of any other build can't stand in for this one. It also names the APK patched from
    that bundle and the one the device ran, and the two must match, so a walk of whatever was
    already installed can't stand in either.

    Each step names the screen it reached and keeps a screenshot and a UI dump of it beside the
    receipt (steps/<id>.png and steps/<id>.xml), hashed in the receipt. The check reads those files
    again rather than trusting what the receipt says about them: the dump must show the step's own
    screen (its marker), no login or "Restore your chats" prompt, and a screen no other step showed.
    Markers match the English UI.
#>

# The phones release walks run on, by ro.product.model. An emulator passes by its serial.
$WalkReceiptPhones = @('SM-S908U1', 'SM-S938B')
$WalkReceiptSchema = 2

$WalkSettingsComponent = '/app\.hushmessenger\.extension\.Settings(Activity|Launcher)\b'
# Messenger's own screens: anything in Messenger's package that isn't a Hush settings window.
$WalkMessengerComponent = '^com\.facebook\.orca/(?!app\.hushmessenger\.)'
$WalkMessengerNodes = '<node\b[^>]*\bpackage="com\.facebook\.orca"'

# Every screen a release walk must reach, in walk order. A sheet step is required only when its
# patch was applied, and it's opened by tapping its button on the Controls page.
$WalkSteps = @(
    [pscustomobject]@{ Id = 'chat'; Kind = 'chat'; Link = ''; Activity = $WalkMessengerComponent; Marker = $WalkMessengerNodes }
    [pscustomobject]@{ Id = 'settings-controls'; Kind = 'settings'; Page = 'controls'; Activity = $WalkSettingsComponent
        Marker = '<node\b[^>]*\btext="Controls"[^>]*\bselected="true"' }
    [pscustomobject]@{ Id = 'settings-app'; Kind = 'settings'; Page = 'app'; Activity = $WalkSettingsComponent
        Marker = '<node\b[^>]*\btext="App"[^>]*\bselected="true"' }
    [pscustomobject]@{ Id = 'message-log'; Kind = 'sheet'; Patch = 'Keep a message log'; Button = 'View log'; Close = 'Close'
        Activity = $WalkSettingsComponent; Marker = '<node\b[^>]*\btext="Message log"' }
    [pscustomobject]@{ Id = 'auto-reply'; Kind = 'sheet'; Patch = 'Auto reply'; Button = 'Edit replies'; Close = 'Cancel'
        Activity = $WalkSettingsComponent; Marker = '<node\b[^>]*\btext="Auto replies"' }
    foreach ($link in @('threads', 'active_now', 'montage', 'compose', 'settings', 'stickerstore', 'marketplace')) {
        [pscustomobject]@{ Id = $link.Replace('_', '-'); Kind = 'link'; Link = "fb-messenger://$link"
            Activity = $WalkMessengerComponent; Marker = $WalkMessengerNodes }
    }
)

# A screen standing in front of the one a step asked for. Either one in a dump fails the step.
$WalkBlockingScreens = @(
    [pscustomobject]@{ Name = 'the "Restore your chats" prompt'; Pattern = '(?i)<node\b[^>]*\btext="[^"]*\brestore your\b[^"]*\bchats\b' }
    [pscustomobject]@{ Name = 'the login screen'; Pattern = '(?i)<node\b[^>]*\btext="(Log in|Log into Messenger|Mobile number or email|Continue as [^"]*)"' }
)
$WalkBlockingActivity = '(?i)/[\w.$]*(Login|Nux)[\w$]*Activity\b'

function Get-WalkStepDefinition([string]$Id) {
    return @($WalkSteps | Where-Object { $_.Id -ceq $Id }) | Select-Object -First 1
}

function Get-WalkRequiredSteps {
    <#
    .SYNOPSIS
        The step ids a walk of an APK with these patches applied must pass, in walk order.
    #>
    param([string[]]$AppliedPatches = @())
    return [string[]]@($WalkSteps | Where-Object { $_.Kind -cne 'sheet' -or $AppliedPatches -ccontains $_.Patch } | ForEach-Object { $_.Id })
}

function Get-WalkScreenSignature {
    <#
    .SYNOPSIS
        What a UI dump looks like with its words, times and positions left out: each node's depth,
        class, id and package. Two steps with the same signature showed the same screen.
    #>
    param([Parameter(Mandatory = $true)][string]$Xml)
    $depth = 0
    $shape = [Text.StringBuilder]::new()
    foreach ($tag in [regex]::Matches($Xml, '<(/?)node\b([^>]*?)(/?)>')) {
        if ($tag.Groups[1].Value) { $depth--; continue }
        $attributes = $tag.Groups[2].Value
        $parts = foreach ($name in 'class', 'resource-id', 'package') {
            $found = [regex]::Match($attributes, "\b$name=""([^""]*)""")
            if ($found.Success) { $found.Groups[1].Value } else { '' }
        }
        [void]$shape.Append("$depth|$($parts -join '|')`n")
        if (-not $tag.Groups[3].Value) { $depth++ }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($shape.ToString())
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Test-WalkStepScreen {
    <#
    .SYNOPSIS
        Why the screen a step reached isn't the one it asked for, or $null when it is. Reads the
        step's UI dump; the walk asks this while it walks and publish asks it again of the files.
    #>
    param([Parameter(Mandatory = $true)]$Definition, [AllowEmptyString()][string]$Activity, [AllowEmptyString()][string]$Xml)
    if ([string]::IsNullOrEmpty($Xml) -or $Xml -notmatch '<hierarchy\b') { return "$($Definition.Id) left no UI dump" }
    foreach ($blocking in $WalkBlockingScreens) {
        if ($Xml -match $blocking.Pattern) { return "$($Definition.Id) showed $($blocking.Name) instead" }
    }
    if ($Activity -match $WalkBlockingActivity) { return "$($Definition.Id) showed the login screen instead ($Activity)" }
    if ($Activity -notmatch $Definition.Activity) { return "$($Definition.Id) reached $(if ($Activity) { $Activity } else { 'no screen' }), not its own" }
    if ($Xml -notmatch $Definition.Marker) { return "$($Definition.Id)'s screen doesn't show what it should" }
    return $null
}

function New-WalkReceipt {
    <#
    .SYNOPSIS
        The receipt for one walk, as an ordered dictionary ready for ConvertTo-Json.
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('PASS', 'PARTIAL', 'FAIL')][string]$Result,
        [Parameter(Mandatory = $true)][bool]$Full,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Device,
        [AllowEmptyString()][string]$AppVersion,
        [Parameter(Mandatory = $true)][string]$BundleSha256,
        # The APK patched from that bundle, and the one the device ran. '' when unknown.
        [AllowEmptyString()][string]$PatchedApkSha256,
        [AllowEmptyString()][string]$InstalledApkSha256,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Started,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Finished,
        # The patches Desktop applied to the walked APK, which decide the required steps.
        [string[]]$AppliedPatches = @(),
        # One record per step walked: id, activity, opened, problem, screenshot/uiDump paths and hashes.
        [object[]]$Steps = @(),
        [int]$Crashes,
        [string[]]$Problems = @()
    )
    return [ordered]@{
        schema = $WalkReceiptSchema; result = $Result; full = $Full
        serial = $Serial; device = $Device; emulator = $Serial.StartsWith('emulator-', [StringComparison]::Ordinal)
        appVersion = $AppVersion; bundleSha256 = $BundleSha256.ToLowerInvariant()
        patchedApkSha256 = $PatchedApkSha256; installedApkSha256 = $InstalledApkSha256
        started = $Started.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ'); finished = $Finished.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
        appliedPatches = @($AppliedPatches); requiredSteps = @(Get-WalkRequiredSteps -AppliedPatches $AppliedPatches)
        steps = @($Steps); screens = @($Steps | Where-Object { $_.opened -eq $true }).Count
        crashes = $Crashes; problems = @($Problems)
    }
}

function Test-WalkReceipt {
    <#
    .SYNOPSIS
        Whether the receipt at -Path is a full, passing walk of the bundle at -BundlePath, on a
        release phone or an emulator, with every required step's evidence beside it. Answers
        @{ Valid; Reason; Receipt }.
    #>
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$BundlePath)
    function Fail([string]$Reason) { return [pscustomobject]@{ Valid = $false; Reason = $Reason; Receipt = $null } }
    function IsCount($Value) { return $Value -is [int] -or $Value -is [long] }
    function IsSha256($Value) { return $Value -is [string] -and $Value -cmatch '^[0-9a-f]{64}\z' }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return Fail "there's no walk receipt at $Path" }
    try { $receipt = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -NoEnumerate }
    catch { return Fail "the walk receipt doesn't read as JSON: $($_.Exception.Message)" }
    if ($null -eq $receipt -or $receipt -isnot [pscustomobject] -or -not (IsCount $receipt.schema) -or $receipt.schema -ne $WalkReceiptSchema) {
        return Fail "the walk receipt isn't schema $WalkReceiptSchema (an older receipt only counted screens, so walk again)"
    }
    if ($receipt.result -isnot [string] -or $receipt.result -cne 'PASS') {
        return Fail "the walk ended $(if ($receipt.result -is [string] -and $receipt.result) { $receipt.result } else { 'with no result' }), not PASS"
    }
    if ($receipt.full -isnot [bool] -or -not $receipt.full) { return Fail 'the walk skipped part of the app, so it was not the whole app' }
    if (-not (IsCount $receipt.crashes) -or $receipt.crashes -ne 0) { return Fail 'the walk recorded a Messenger crash' }
    if ($receipt.problems -isnot [array] -or @($receipt.problems | Where-Object { $_ -isnot [string] }).Count) {
        return Fail "the walk receipt's problems aren't a list"
    }
    if ($receipt.problems.Count) { return Fail "the walk recorded problems: $($receipt.problems -join '; ')" }
    if ($receipt.device -isnot [string] -or $receipt.serial -isnot [string] -or $receipt.emulator -isnot [bool]) {
        return Fail "the walk receipt doesn't say which device walked it"
    }
    $isEmulator = $receipt.serial.StartsWith('emulator-', [StringComparison]::Ordinal)
    if ($receipt.emulator -ne $isEmulator -or (-not $isEmulator -and $WalkReceiptPhones -cnotcontains $receipt.device)) {
        return Fail "$($receipt.device) isn't one of the release walk phones ($($WalkReceiptPhones -join ', ')) or an emulator"
    }
    if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) { return Fail "there's no bundle at $BundlePath to hold the walk to" }
    $bundleSha256 = (Get-FileHash -LiteralPath $BundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($receipt.bundleSha256 -isnot [string] -or $receipt.bundleSha256 -cne $bundleSha256) {
        return Fail "the walked APK was patched with bundle '$($receipt.bundleSha256)', and this one is '$bundleSha256'"
    }
    if (-not (IsSha256 $receipt.patchedApkSha256)) {
        return Fail "the walk receipt doesn't name the APK patched from this bundle, so nothing shows the walked app came from it"
    }
    if ($receipt.installedApkSha256 -isnot [string] -or $receipt.installedApkSha256 -cne $receipt.patchedApkSha256) {
        return Fail "the walk ran APK '$($receipt.installedApkSha256)', not '$($receipt.patchedApkSha256)', the one patched from this bundle"
    }

    if ($receipt.appliedPatches -isnot [array] -or $receipt.appliedPatches.Count -eq 0 -or
        @($receipt.appliedPatches | Where-Object { $_ -isnot [string] -or -not $_ }).Count) {
        return Fail "the walk receipt doesn't list the patches the walked APK has"
    }
    $required = @(Get-WalkRequiredSteps -AppliedPatches ([string[]]$receipt.appliedPatches))
    if ($receipt.requiredSteps -isnot [array] -or (@($receipt.requiredSteps) -join ' ') -cne ($required -join ' ')) {
        return Fail "the walk receipt's required steps ($(@($receipt.requiredSteps) -join ', ')) aren't the ones this APK needs ($($required -join ', '))"
    }
    if ($receipt.steps -isnot [array]) { return Fail "the walk receipt doesn't list its steps" }
    if (-not (IsCount $receipt.screens) -or $receipt.screens -ne $receipt.steps.Count) {
        return Fail "the walk receipt counts $($receipt.screens) screens but lists $($receipt.steps.Count) steps"
    }
    $folder = Split-Path -Parent ([IO.Path]::GetFullPath($Path))
    $seen = @{}
    $shapes = @{}
    foreach ($step in $receipt.steps) {
        if ($step -isnot [pscustomobject] -or $step.id -isnot [string] -or -not $step.id) { return Fail 'the walk receipt lists a step with no id' }
        $definition = Get-WalkStepDefinition $step.id
        if ($null -eq $definition) { return Fail "the walk receipt lists a step it doesn't know ($($step.id))" }
        if ($seen.ContainsKey($step.id)) { return Fail "the walk receipt lists $($step.id) twice" }
        $seen[$step.id] = $true
        if ($required -cnotcontains $step.id) { return Fail "the walk receipt lists $($step.id), which this APK doesn't have" }
        if ($step.opened -isnot [bool] -or -not $step.opened -or ($step.problem -is [string] -and $step.problem)) {
            return Fail "$($step.id) didn't open$(if ($step.problem -is [string] -and $step.problem) { ": $($step.problem)" })"
        }
        $evidence = @{}
        foreach ($kind in @(@('screenshot', '.png'), @('uiDump', '.xml'))) {
            $relative = $step.($kind[0])
            $hash = $step.("$($kind[0])Sha256")
            if ($relative -cne "steps/$($step.id)$($kind[1])" -or -not (IsSha256 $hash)) {
                return Fail "$($step.id) doesn't name its $($kind[0]) and its SHA-256"
            }
            $file = Join-Path $folder $relative
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return Fail "$($step.id)'s $($kind[0]) isn't beside the receipt ($relative)" }
            if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hash) {
                return Fail "$($step.id)'s $($kind[0]) changed after the walk ($relative)"
            }
            $evidence[$kind[0]] = $file
        }
        $png = [IO.File]::ReadAllBytes($evidence.screenshot)
        if ($png.Length -lt 8 -or [Convert]::ToHexString($png, 0, 8) -cne '89504E470D0A1A0A') { return Fail "$($step.id)'s screenshot isn't a PNG" }
        $xml = [IO.File]::ReadAllText($evidence.uiDump)
        $wrong = Test-WalkStepScreen -Definition $definition -Activity ([string]$step.activity) -Xml $xml
        if ($wrong) { return Fail $wrong }
        $shape = Get-WalkScreenSignature -Xml $xml
        if ($shapes.ContainsKey($shape)) { return Fail "$($step.id) showed the same screen as $($shapes[$shape])" }
        $shapes[$shape] = $step.id
    }
    foreach ($id in $required) { if (-not $seen.ContainsKey($id)) { return Fail "the walk left out $id" } }
    return [pscustomobject]@{ Valid = $true; Reason = $null; Receipt = $receipt }
}
