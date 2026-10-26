<# Offline contracts for walk-receipt.ps1: publish takes only a full, passing walk of the bundle it ships, with every required step's evidence. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')
. (Join-Path $PSScriptRoot 'walk-fixture.ps1')

function Assert-WalkContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('hushmessenger-walk-contract-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($work)
try {
    $bundle = Join-Path $work 'patches-0.27.0.mpp'
    [IO.File]::WriteAllText($bundle, 'the signed bundle')
    $bundleSha256 = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    $case = 0
    # A fresh walk folder of that bundle. -Change bends top-level fields, -Bend gets the receipt and folder.
    function Write-Walk([hashtable]$Change = @{}, [scriptblock]$Bend, [string[]]$Applied = $WalkFixturePatches) {
        $script:case++
        $folder = Join-Path $work "walk-$($script:case)"
        $walk = New-WalkFixture -Folder $folder -BundleSha256 $bundleSha256 -AppliedPatches $Applied
        foreach ($key in $Change.Keys) { $walk[$key] = $Change[$key] }
        if ($Bend) { & $Bend $walk $folder }
        $path = Join-Path $folder 'walk-receipt.json'
        [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $walk -Depth 6))
        return $path
    }
    function Step($Walk, [string]$Id) { return @($Walk.steps | Where-Object { $_.id -ceq $Id })[0] }

    # The required steps follow the patches: the sheets only when their patch was applied.
    $all = @(Get-WalkRequiredSteps -AppliedPatches $WalkFixturePatches)
    Assert-WalkContract (($all -join ' ') -ceq 'chat settings-controls settings-app message-log auto-reply threads active-now montage compose settings stickerstore marketplace') `
        "The required steps with both sheet patches read: $($all -join ' ')"
    $without = @(Get-WalkRequiredSteps -AppliedPatches @('Hide Meta AI'))
    Assert-WalkContract ($without -cnotcontains 'message-log' -and $without -cnotcontains 'auto-reply' -and $without.Count -eq 10) `
        "Without the log and reply patches the sheets should not be required: $($without -join ' ')"

    # The screen signature leaves out words and positions, and nothing else.
    $one = Get-WalkFixtureXml 'threads'
    Assert-WalkContract ((Get-WalkScreenSignature $one) -ceq (Get-WalkScreenSignature ($one.Replace('content-desc="Chats"', 'content-desc="Chats, 3 new"').Replace('[0,100]', '[0,140]')))) `
        'A changed label or position should leave the screen signature alone.'
    Assert-WalkContract ((Get-WalkScreenSignature $one) -cne (Get-WalkScreenSignature (Get-WalkFixtureXml 'montage'))) 'Two different screens should sign differently.'
    Assert-WalkContract ((Get-WalkScreenSignature $one) -cne (Get-WalkScreenSignature ($one.Replace('</node></hierarchy>', '<node class="x" package="p" /></node></hierarchy>')))) `
        'An extra node should change the signature.'

    # Either release phone or an emulator walks for a release, with or without the sheet patches.
    foreach ($walker in @(@{}, @{ device = 'SM-S938B' }, @{ serial = 'emulator-5554'; device = 'sdk_gphone64_x86_64'; emulator = $true })) {
        $good = Test-WalkReceipt -Path (Write-Walk $walker) -BundlePath $bundle
        Assert-WalkContract ($good.Valid -and $good.Receipt.screens -eq 12) "A full PASS walk of the bundle was refused: $($good.Reason)"
    }
    $good = Test-WalkReceipt -Path (Write-Walk -Applied @('Hide Meta AI')) -BundlePath $bundle
    Assert-WalkContract ($good.Valid -and $good.Receipt.screens -eq 10) "A full walk of an APK without the sheet patches was refused: $($good.Reason)"

    $restore = '<node index="1" text="Restore your Facebook chats" resource-id="" class="android.widget.TextView" package="com.facebook.orca" content-desc="" enabled="true" selected="false" bounds="[0,300][1080,400]" />'
    $login = '<node index="1" text="Log in" resource-id="" class="android.widget.Button" package="com.facebook.orca" content-desc="" enabled="true" selected="false" bounds="[0,300][1080,400]" />'
    $restoreDesc = '<node index="1" text="" resource-id="" class="android.view.View" package="com.facebook.orca" content-desc="Restore your chats" enabled="true" selected="false" bounds="[0,300][1080,400]" />'
    $loginDesc = '<node index="1" text="" resource-id="" class="android.view.View" package="com.facebook.orca" content-desc="Log in" enabled="true" selected="false" bounds="[0,300][1080,400]" />'
    $restoreButton = '<node index="0" text="Restore now" resource-id="" class="android.widget.Button" package="com.facebook.orca" content-desc="Restore now" enabled="true" selected="false" bounds="[45,2023][1035,2158]" />'
    foreach ($badWalk in @(
            # The old count-only receipt.
            @(@{ schema = 1 }, $null, "*isn't schema 2*older receipt only counted screens*"),
            @(@{ schema = '2' }, $null, "*isn't schema 2*"),
            @(@{ result = 'FAIL' }, $null, '*the walk ended FAIL, not PASS*'),
            @(@{ result = 'PARTIAL'; full = $false }, $null, '*the walk ended PARTIAL, not PASS*'),
            @(@{ result = $null }, $null, '*ended with no result*'),
            @(@{ full = $false }, $null, '*was not the whole app*'),
            @(@{ crashes = 1 }, $null, '*recorded a Messenger crash*'),
            # PASS beside a problem contradicts itself.
            @(@{ problems = @('montage would not open') }, $null, '*recorded problems: montage would not open*'),
            @(@{ problems = 'one string' }, $null, "*problems aren't a list*"),
            @(@{ device = $null }, $null, "*doesn't say which device walked it*"),
            @(@{ device = 'Pixel 9' }, $null, "*Pixel 9 isn't one of the release walk phones*"),
            @(@{ device = 'Pixel 9'; emulator = $true }, $null, "*Pixel 9 isn't one of the release walk phones*"),
            @(@{ bundleSha256 = ('5' * 64) }, $null, '*patched with bundle*and this one is*'),
            @(@{ patchedApkSha256 = '' }, $null, "*doesn't name the APK patched from this bundle*"),
            @(@{ patchedApkSha256 = ('4' * 64) + "`n"; installedApkSha256 = ('4' * 64) + "`n" }, $null, "*doesn't name the APK patched from this bundle*"),
            @(@{ installedApkSha256 = ('6' * 64) }, $null, "*the walk ran APK '6666*', not '4444*', the one patched from this bundle*"),
            @(@{ installedApkSha256 = '/system/bin/sh: sha256sum: not found' }, $null, '*the walk ran APK*the one patched from this bundle*'),
            @(@{ appliedPatches = @() }, $null, "*doesn't list the patches*"),
            # A receipt that drops a step from its own required list.
            @(@{}, { param($w) $w.requiredSteps = @($w.requiredSteps | Where-Object { $_ -cne 'auto-reply' }) }, "*required steps*aren't the ones this APK needs*"),
            @(@{ screens = 40 }, $null, '*counts 40 screens but lists 12 steps*'),
            @(@{ steps = 'none' }, $null, "*doesn't list its steps*"),
            # An absent step.
            @(@{}, { param($w) $w.steps = @($w.steps | Where-Object { $_.id -cne 'montage' }); $w.screens = 11 }, '*the walk left out montage*'),
            @(@{}, { param($w) $w.steps = @($w.steps) + (Step $w 'chat'); $w.screens = 13 }, '*lists chat twice*'),
            @(@{}, { param($w) (Step $w 'chat').id = 'quicksnap' }, "*a step it doesn't know (quicksnap)*"),
            @(@{}, { param($w) (Step $w 'chat').id = '' }, '*a step with no id*'),
            @(@{}, { param($w) (Step $w 'compose').opened = $false }, "*compose didn't open*"),
            @(@{}, { param($w) (Step $w 'compose').problem = 'compose wouldn''t start' }, "*compose didn't open: compose wouldn't start*"),
            # A problem that isn't text can't be read as no problem.
            @(@{}, { param($w) (Step $w 'compose').problem = @('compose wouldn''t start') }, "*compose's problem isn't a line of text*"),
            @(@{}, { param($w) (Step $w 'compose').problem = $true }, "*compose's problem isn't a line of text*"),
            @(@{}, { param($w) (Step $w 'compose').problem = $null }, "*compose's problem isn't a line of text*"),
            # The required steps as one joined string, in a list or bare, aren't the list.
            @(@{}, { param($w) $w.requiredSteps = @($w.requiredSteps -join ' ') }, "*required steps*aren't the ones this APK needs*"),
            @(@{}, { param($w) $w.requiredSteps = $w.requiredSteps -join ' ' }, "*required steps*aren't the ones this APK needs*"),
            # Evidence that's missing, moved or changed.
            @(@{}, { param($w, $f) Remove-Item -LiteralPath (Join-Path $f 'steps/threads.png') }, "*threads's screenshot isn't beside the receipt*"),
            @(@{}, { param($w, $f) [IO.File]::AppendAllText((Join-Path $f 'steps/threads.xml'), ' ') }, "*threads's uiDump changed after the walk*"),
            @(@{}, { param($w) (Step $w 'threads').uiDump = '../threads.xml' }, "*threads doesn't name its uiDump*"),
            @(@{}, { param($w) (Step $w 'threads').screenshotSha256 = '' }, "*threads doesn't name its screenshot*"),
            @(@{}, { param($w, $f) (Step $w 'threads').screenshotSha256 = Write-WalkFixtureFile (Join-Path $f 'steps/threads.png') ([Text.Encoding]::ASCII.GetBytes('not a png')) },
                "*threads's screenshot isn't a PNG*"),
            # A screen in the way of the one asked for.
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'threads' (Get-WalkFixtureXml 'threads' $restore) }, '*threads showed the "Restore your chats" prompt instead*'),
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'chat' (Get-WalkFixtureXml 'chat' $login) }, '*chat showed the login screen instead*'),
            # The same prompts with their words only in content-desc, as Compose buttons draw them.
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'montage' (Get-WalkFixtureXml 'montage' $restoreDesc) }, '*montage showed the "Restore your chats" prompt instead*'),
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'compose' (Get-WalkFixtureXml 'compose' $loginDesc) }, '*compose showed the login screen instead*'),
            # 584.1's restore prompt as the S22 dumped it: only its button and Close are in the tree.
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'settings' (Get-WalkFixtureXml 'settings' $restoreButton) }, '*settings showed the "Restore your chats" prompt instead*'),
            @(@{}, { param($w) (Step $w 'marketplace').activity = 'com.facebook.orca/com.facebook.messaging.neue.nux.NeueNuxActivity' },
                "*marketplace showed one of Messenger's first-run screens instead*"),
            @(@{}, { param($w) (Step $w 'compose').activity = 'com.facebook.orca/com.facebook.messaging.accountlogin.AccountLoginActivity' }, '*compose showed the login screen instead*'),
            # The wrong screen, or the right activity without its page.
            @(@{}, { param($w) (Step $w 'settings-app').activity = 'com.facebook.orca/.auth.StartScreenActivity' }, "*settings-app reached com.facebook.orca/.auth.StartScreenActivity, not its own*"),
            @(@{}, { param($w) (Step $w 'threads').activity = 'com.facebook.orca/app.hushmessenger.extension.SettingsActivity' }, '*threads reached*SettingsActivity, not its own*'),
            @(@{}, { param($w) (Step $w 'threads').activity = 'com.android.launcher3/.Launcher' }, '*threads reached com.android.launcher3/.Launcher, not its own*'),
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'settings-controls' (Get-WalkFixtureXml 'settings-app') }, "*settings-controls's screen doesn't show what it should*"),
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'auto-reply' '' }, '*auto-reply left no UI dump*'),
            # The same screen twice, as when a link falls back to the inbox.
            @(@{}, { param($w, $f) Set-WalkFixtureEvidence $w $f 'stickerstore' ((Get-WalkFixtureXml 'threads').Replace('Chats', 'Chats, 2 new')) }, '*stickerstore showed the same screen as threads*'))) {
        $path = Write-Walk $badWalk[0] $badWalk[1]
        $bad = Test-WalkReceipt -Path $path -BundlePath $bundle
        Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like $badWalk[2]) "Expected the walk refusal [$($badWalk[2])], got [$($bad.Reason)]."
    }
    # A sheet step for a patch the APK doesn't have.
    $path = Write-Walk -Applied @('Hide Meta AI') -Bend {
        param($w, $f)
        $full = New-WalkFixture -Folder $f -BundleSha256 $bundleSha256
        $w.steps = @($w.steps) + (Step $full 'auto-reply'); $w.screens = 11
    }
    $bad = Test-WalkReceipt -Path $path -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*lists auto-reply, which this APK doesn't have*") "A sheet for a missing patch read as: $($bad.Reason)"

    $notJson = Join-Path $work 'not-json.json'
    [IO.File]::WriteAllText($notJson, 'not json')
    $bad = Test-WalkReceipt -Path $notJson -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*doesn't read as JSON*") "A receipt that isn't JSON read as: $($bad.Reason)"
    $bad = Test-WalkReceipt -Path (Join-Path $work 'none.json') -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*there's no walk receipt at*") "A missing receipt read as: $($bad.Reason)"

    # A walk of one bundle never backs another, even a rebuild of the same commit.
    $path = Write-Walk
    [IO.File]::WriteAllText($bundle, 'the signed bundle, rebuilt')
    $bad = Test-WalkReceipt -Path $path -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like '*patched with bundle*and this one is*') "A walk backed another bundle: $($bad.Reason)"
    $bad = Test-WalkReceipt -Path $path -BundlePath (Join-Path $work 'gone.mpp')
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*there's no bundle at*") "A walk backed a bundle that isn't there: $($bad.Reason)"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

# Publish asks for the walk once the built assets check out, before the notes, the tag or GitHub.
$releaseText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release/release.ps1'))
$publishAt = $releaseText.IndexOf("'publish' {")
$assetsAt = $releaseText.IndexOf('SHA256SUMS.txt or its signature changed after the build stage', $publishAt)
$walkAt = $releaseText.IndexOf('$walked = Test-WalkReceipt -Path $Walk', $publishAt)
$refuseAt = $releaseText.IndexOf('if (-not $walked.Valid) {', $publishAt)
foreach ($later in @('Get-ChangelogSection', 'Invoke-Git tag', 'refs/tags/$tag', 'gh release create')) {
    $laterAt = $releaseText.IndexOf($later, $publishAt)
    Assert-WalkContract ($publishAt -gt 0 -and $assetsAt -gt $publishAt -and $walkAt -gt $assetsAt -and $refuseAt -gt $walkAt -and $laterAt -gt $refuseAt) `
        "release.ps1 -Stage publish reaches $later before it holds the bundle to its walk."
}
Assert-WalkContract ($releaseText -match '(?m)^\s*\[string\]\$Walk,') 'release.ps1 has no -Walk.'
Write-Host '[scripts] walk receipt contracts passed'
