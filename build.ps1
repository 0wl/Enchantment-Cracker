<#
    Offline build for the Enchantment Cracker Forge 1.16.5 mod.

    A normal Forge mod is built with ForgeGradle, which downloads Minecraft, the MCP
    mappings and Forge itself, then reobfuscates the compiled classes into SRG names.
    This script does the same job with no network at all: it compiles straight against
    the SRG-named jars that a local Forge 1.16.5 installation already contains, so the
    output is already in the naming a production mod jar needs.

    Usage:   powershell -ExecutionPolicy Bypass -File build.ps1
    Output:  ..\output\enchcracker-<version>-forge-1.16.5.jar
#>

param(
    [string]$ForgeLibraries = "",
    [string]$Jdk = "",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = "1.2.4"
$mcVersion = "1.16.5-20210115.111550"
$forgeVersion = "1.16.5-36.2.42"

function Fail($message) {
    Write-Host "BUILD FAILED: $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- locate a JDK

function Find-Jdk {
    $candidates = @()
    if ($Jdk) { $candidates += $Jdk }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    # A full JDK 8 unpacked into the shared build cache (see PROJECT-NOTES.md). Java 8 is
    # what Minecraft 1.16.5 runs on, and unlike the trimmed runtime below it can read jars.
    $cache = Join-Path $env:LOCALAPPDATA "enchcracker-build\tools\jdk8x"
    if (Test-Path $cache) {
        $candidates += (Get-ChildItem $cache -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }
    $candidates += @(
        "C:\Program Files\Visual Paradigm 17.2\jre"
    )
    foreach ($root in "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft") {
        if (Test-Path $root) {
            $candidates += (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\javac.exe"))) {
            return $candidate
        }
    }
    return $null
}

$jdkHome = Find-Jdk
if (-not $jdkHome) { Fail "No JDK with javac.exe found. Pass -Jdk <path> or set JAVA_HOME." }
$javac = Join-Path $jdkHome "bin\javac.exe"
Write-Host "JDK:     $jdkHome"

# ---------------------------------------------------------- locate Forge 1.16.5

function Find-ForgeLibraries {
    $candidates = @()
    if ($ForgeLibraries) { $candidates += $ForgeLibraries }
    $candidates += @(
        "$env:USERPROFILE\curseforge\minecraft\Install\libraries",
        "$env:APPDATA\.minecraft\libraries",
        "$env:USERPROFILE\Documents\curseforge\minecraft\Install\libraries"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "net\minecraftforge\forge\$forgeVersion"))) {
            return $candidate
        }
    }
    return $null
}

$lib = Find-ForgeLibraries
if (-not $lib) {
    Fail "Could not find a Forge $forgeVersion installation. Install Forge 1.16.5 (any launcher) or pass -ForgeLibraries <libraries folder>."
}
Write-Host "Forge:   $lib"

$forgeDir = Join-Path $lib "net\minecraftforge\forge\$forgeVersion"
$mcDir = Join-Path $lib "net\minecraft\client\$mcVersion"

$coreJars = @(
    (Join-Path $forgeDir "forge-$forgeVersion-client.jar"),
    (Join-Path $forgeDir "forge-$forgeVersion-universal.jar"),
    (Join-Path $mcDir "client-$mcVersion-srg.jar"),
    (Join-Path $mcDir "client-$mcVersion-extra.jar")
)
foreach ($jar in $coreJars) {
    if (-not (Test-Path $jar)) { Fail "Missing $jar" }
}

# Everything else Minecraft and Forge need at compile time.
$allJars = @($coreJars)
$allJars += (Get-ChildItem $lib -Filter "*.jar" -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "1\.12|1\.20|1\.21|neoforged|sources|javadoc" } |
        ForEach-Object { $_.FullName })
$allJars = $allJars | Select-Object -Unique

$work = Join-Path $projectDir "build"
$classesDir = Join-Path $work "classes"
# The exploded classpath is ~230 MB of Minecraft and Forge classes. It is a cache, not
# project output, so it lives outside the project folder and is shared between builds.
$cpDir = Join-Path $env:LOCALAPPDATA "enchcracker-build\cp-$forgeVersion"

if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
New-Item -ItemType Directory -Force $classesDir | Out-Null

# --------------------------------------------- classpath (jars, or exploded)

# Some trimmed JDK builds ship without the jdk.zipfs module, and then javac cannot read
# JARs on the classpath at all. Detect that once and fall back to exploded directories.
if (Test-Path (Join-Path $jdkHome "jre\lib\rt.jar")) {
    $zipfs = $true   # Java 8: zip support is built in
} else {
    $ErrorActionPreference = "Continue"
    $zipfs = (& (Join-Path $jdkHome "bin\java.exe") --list-modules 2>$null) -match "jdk.zipfs"
    $ErrorActionPreference = "Stop"
}
$classpath = ""

if ($zipfs) {
    $classpath = ($allJars -join ";")
    Write-Host "Classpath: $($allJars.Count) jars"
} else {
    Write-Host "Classpath: this JDK has no zipfs module, exploding jars..."
    if (-not (Test-Path $cpDir)) {
        New-Item -ItemType Directory -Force $cpDir | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $seen = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($jar in $allJars) {
            try { $zip = [System.IO.Compression.ZipFile]::OpenRead($jar) } catch { continue }
            foreach ($entry in $zip.Entries) {
                $name = $entry.FullName
                if (-not $name.EndsWith(".class")) { continue }
                if ($name.StartsWith("META-INF/")) { continue }
                if (-not $seen.Add($name)) { continue }
                $target = Join-Path $cpDir ($name -replace '/', '\')
                $dir = Split-Path -Parent $target
                if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
            $zip.Dispose()
        }
        Write-Host "  extracted $($seen.Count) classes"
    }
    $classpath = $cpDir
}

# ---------------------------------------------------------------- compile

$sources = Get-ChildItem (Join-Path $projectDir "src\main\java") -Filter *.java -Recurse |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
if ($sources.Count -eq 0) { Fail "No sources found." }
$sourceList = Join-Path $work "sources.txt"
[System.IO.File]::WriteAllLines($sourceList, $sources)

# Hundreds of jars do not fit on a Windows command line, so the classpath goes in an
# argument file too.
$cpArgs = Join-Path $work "classpath.txt"
[System.IO.File]::WriteAllLines($cpArgs, @("-classpath", ('"' + $classpath.Replace('\', '/') + '"')))

Write-Host "Compiling $($sources.Count) files (Java 8 bytecode)..."
# javac writes its "deprecated API" note to stderr even with -nowarn; under the script's
# Stop preference PowerShell would turn that harmless note into a terminating error. Drop
# to Continue for the call and judge success by the exit code alone.
$ErrorActionPreference = "Continue"
& $javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 `
        "@$cpArgs" -d $classesDir "@$sourceList" 2>&1 | ForEach-Object { Write-Host $_ }
$javacExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($javacExit -ne 0) { Fail "javac exited with $javacExit" }

# ---------------------------------------------------------------- package

if (-not $OutputDir) { $OutputDir = Join-Path (Split-Path -Parent $projectDir) "output" }
if (-not (Test-Path $OutputDir)) { New-Item -ItemType Directory -Force $OutputDir | Out-Null }
$jarPath = Join-Path $OutputDir "enchcracker-$version-forge-1.16.5.jar"

$stage = Join-Path $work "jar"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null

Copy-Item (Join-Path $classesDir "*") $stage -Recurse -Force
Copy-Item (Join-Path $projectDir "src\main\resources\*") $stage -Recurse -Force
$licence = Join-Path $projectDir "LICENSE.txt"
if (Test-Path $licence) { Copy-Item $licence $stage -Force }

$manifestDir = Join-Path $stage "META-INF"
if (-not (Test-Path $manifestDir)) { New-Item -ItemType Directory -Force $manifestDir | Out-Null }
$manifest = @(
    "Manifest-Version: 1.0",
    "Specification-Title: enchcracker",
    "Specification-Vendor: Earthcomputer, Hexicube",
    "Specification-Version: 1",
    "Implementation-Title: Enchantment Cracker",
    "Implementation-Version: $version",
    "Implementation-Vendor: Earthcomputer, Hexicube",
    ""
)
[System.IO.File]::WriteAllLines((Join-Path $manifestDir "MANIFEST.MF"), $manifest)

if (Test-Path $jarPath) { Remove-Item -Force $jarPath }
Add-Type -AssemblyName System.IO.Compression          # ZipArchive, ZipArchiveMode
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Entries are written by hand rather than with CreateFromDirectory, because on .NET
# Framework that writes Windows path separators into the archive. A jar with
# "META-INF\mods.toml" in it is a jar Forge will never find the mod in.
$zipStream = [System.IO.File]::Open($jarPath, [System.IO.FileMode]::Create)
$archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $stageFull = (Resolve-Path $stage).Path
    foreach ($file in Get-ChildItem $stage -Recurse -File) {
        $relative = $file.FullName.Substring($stageFull.Length).TrimStart('\', '/').Replace('\', '/')
        $entry = $archive.CreateEntry($relative, [System.IO.Compression.CompressionLevel]::Optimal)
        $entryStream = $entry.Open()
        try {
            $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
            $entryStream.Write($bytes, 0, $bytes.Length)
        } finally {
            $entryStream.Dispose()
        }
    }
} finally {
    $archive.Dispose()
    $zipStream.Dispose()
}

$size = [Math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host ""
Write-Host "Built $jarPath ($size KB)" -ForegroundColor Green
Write-Host "Drop it into your Forge 1.16.5 'mods' folder."
