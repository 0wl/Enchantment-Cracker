<#
    One-shot verification for the Enchantment Cracker mod. Run after any change:

        powershell -ExecutionPolicy Bypass -File tests\verify.ps1

    It does everything that can be checked without launching Minecraft:

      1. Build         - compiles every source file against the real Forge 1.16.5 SRG jars,
                         so any wrong obfuscated name is a compile error.
      2. Feature tests - runs FeatureTests in a plain JVM (core classes only, no game): the
                         velocity cracker maths, the anvil planner's pre-enchanted-item path,
                         and the widened drop ceiling.
      3. Link check    - loads and links every class in the built jar against the real Forge
                         runtime (LoadTest), catching anything that would be a
                         NoSuchMethodError / NoClassDefFoundError when the game loads the mod.

    In-game behaviour (GUI, key binds, multiplayer) is listed in VERIFICATION.md for a manual
    pass, and exercised by the self-test mod under tests\selftest.
#>

param(
    [string]$Jdk = "",
    [string]$ForgeLibraries = ""
)

$ErrorActionPreference = "Stop"
$testsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $testsDir
$version = "1.2.3"

function Section($text) { Write-Host ""; Write-Host "=== $text ===" -ForegroundColor Cyan }
function Fail($text) { Write-Host "VERIFY FAILED: $text" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- locate a JDK (needs javac)
function Find-Jdk {
    $candidates = @()
    if ($Jdk) { $candidates += $Jdk }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($root in "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Microsoft") {
        if (Test-Path $root) {
            # Prefer 8/11/17 over the newest, which may have dropped -source 8.
            $candidates += (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                    Sort-Object { if ($_.Name -match '-(\d+)') { [int]$Matches[1] } else { 999 } } |
                    ForEach-Object { $_.FullName })
        }
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "bin\javac.exe"))) { return $c }
    }
    return $null
}

$jdkHome = Find-Jdk
if (-not $jdkHome) { Fail "No JDK with javac.exe found. Pass -Jdk <path>." }
$javac = Join-Path $jdkHome "bin\javac.exe"
$java = Join-Path $jdkHome "bin\java.exe"
Write-Host "JDK: $jdkHome"

# ---------------------------------------------------------------- locate Forge libraries
function Find-Libs {
    $candidates = @()
    if ($ForgeLibraries) { $candidates += $ForgeLibraries }
    $candidates += @(
        "$env:APPDATA\.minecraft\libraries",
        "$env:USERPROFILE\curseforge\minecraft\Install\libraries"
    )
    foreach ($c in $candidates) {
        $mc = Join-Path $c "net\minecraft\client\1.16.5-20210115.111550\client-1.16.5-20210115.111550-srg.jar"
        $fg = Join-Path $c "net\minecraftforge\forge\1.16.5-36.2.42"
        if ((Test-Path $mc) -and (Test-Path $fg)) { return $c }
    }
    return $null
}

$libs = Find-Libs
if (-not $libs) { Fail "No Forge 1.16.5 libraries found (need both Forge 36.2.42 and the Minecraft client SRG jar). Pass -ForgeLibraries <libraries>." }
Write-Host "Libraries: $libs"

$results = [ordered]@{}

# ---------------------------------------------------------------- 1. build
Section "1/3  Build against Forge SRG jars"
& (Join-Path $projectDir "build.ps1") -Jdk $jdkHome -ForgeLibraries $libs | Write-Host
$jar = Join-Path (Split-Path -Parent $projectDir) "output\enchcracker-$version-forge-1.16.5.jar"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) { $results["Build"] = $false; Fail "build.ps1 did not produce $jar" }
$results["Build"] = $true

$classes = Join-Path $projectDir "build\classes"
$out = Join-Path $testsDir "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

# ---------------------------------------------------------------- 2. feature tests
Section "2/3  Feature tests (pure logic, no Minecraft)"
& $javac -cp $classes -d $out (Join-Path $testsDir "FeatureTests.java")
if ($LASTEXITCODE -ne 0) { Fail "FeatureTests did not compile" }
$ErrorActionPreference = "Continue"
& $java -cp "$classes;$out" FeatureTests | Write-Host
$featureOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = "Stop"
$results["Feature tests"] = $featureOk

# ---------------------------------------------------------------- 3. link check
Section "3/3  Link check against the real Forge runtime"
Push-Location $testsDir
python (Join-Path $testsDir "boot\mkcp_local.py") | Write-Host
Pop-Location
$cpJar = Join-Path $testsDir "cp.jar"
if (-not (Test-Path $cpJar)) { Fail "cp.jar was not built (is python on PATH?)" }
& $javac -d $out (Join-Path $testsDir "LoadTest.java")
if ($LASTEXITCODE -ne 0) { Fail "LoadTest did not compile" }
$ErrorActionPreference = "Continue"
& $java -cp $out LoadTest $jar $cpJar | Write-Host
$linkOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = "Stop"
$results["Link check"] = $linkOk

# ---------------------------------------------------------------- summary
Section "Summary"
$allOk = $true
foreach ($k in $results.Keys) {
    $ok = $results[$k]
    if (-not $ok) { $allOk = $false }
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    $colour = if ($ok) { "Green" } else { "Red" }
    Write-Host ("  {0,-14} {1}" -f $k, $tag) -ForegroundColor $colour
}
Write-Host ""
Write-Host "Built: $jar"
Write-Host "In-game checks that need a running client: see tests\VERIFICATION.md"
if (-not $allOk) { exit 1 }
Write-Host "ALL AUTOMATED VERIFICATION PASSED" -ForegroundColor Green
