param(
    [ValidateSet('26.2', '26.3')]
    [string[]]$MinecraftVersion = @('26.2', '26.3'),
    [string]$SnapshotName,
    [switch]$Offline,
    [string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$taskRepositoryRoot = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$taskIsSnapshot = $PSBoundParameters.ContainsKey('SnapshotName')
if ($taskIsSnapshot -and ($SnapshotName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' -or
        $SnapshotName.EndsWith('.') -or
        $SnapshotName -ieq 'current' -or $SnapshotName -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)')) {
    throw 'SnapshotName must be a new directory label (1-64 ASCII letters, digits, dot, underscore or hyphen); current and Windows device names are reserved.'
}
if (-not (Test-Path -LiteralPath (Join-Path $taskRepositoryRoot 'gradlew.bat') -PathType Leaf)) {
    throw "ProjectDirectory is not a Prime repository: $taskRepositoryRoot"
}
$taskOutputLabel = if ($taskIsSnapshot) { $SnapshotName } else { 'current' }
$taskOutputRoot = [IO.Path]::GetFullPath((Join-Path $taskRepositoryRoot "artifacts/nsight/$taskOutputLabel"))
if ($taskIsSnapshot -and (Test-Path -LiteralPath $taskOutputRoot)) {
    throw "Snapshot output already exists; choose a new label: $taskOutputRoot"
}
[void][IO.Directory]::CreateDirectory($taskOutputRoot)
$taskUtf8 = [Text.UTF8Encoding]::new($false)

function Write-TaskText([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, $taskUtf8)
}

function Read-TaskTool([string]$Executable, [string[]]$Arguments) {
    # Version-only probes cannot launch Minecraft or initialize its renderer.
    try {
        $taskVersionLines = & $Executable @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) { return "unavailable (exit $LASTEXITCODE)" }
        return ($taskVersionLines | ForEach-Object { "$_" }) -join "`n"
    } catch {
        return "unavailable: $($_.Exception.Message)"
    }
}

Push-Location $taskRepositoryRoot
try {
    $taskVersions = @($MinecraftVersion | Select-Object -Unique)
    $taskGradleArguments = @('--no-parallel', '-I', (Join-Path $PSScriptRoot 'nsight-launch.init.gradle'))
    if ($Offline) { $taskGradleArguments += '--offline' }
    $taskGradleArguments += $taskVersions | ForEach-Object { ":mc-${_}:exportNsightLaunch" }
    $taskGradleArguments += @(
        "-PnsightVersions=$($taskVersions -join ',')",
        "-PnsightOutput=$taskOutputRoot",
        "-PnsightSnapshot=$($taskIsSnapshot.ToString().ToLowerInvariant())",
        "-PnativeLibrary=$(Join-Path $taskRepositoryRoot 'target/release/prime_engine.dll')",
        '-PprimeptEnabled=true', '-PprimeptRenderer=path_trace', '-PprimeptGeometryCache=true',
        '-PprimeptValidation=false', '-PprimeptProfile=false', '-PprimeptProfileLeaves=false',
        '-PprimeptCaptureAudit=false'
    )
    & .\gradlew.bat @taskGradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Nsight preparation failed (exit $LASTEXITCODE); partial output is retained for diagnosis." }

    $taskRevision = & git rev-parse --verify HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the target repository revision.' }
    $taskBranch = (& git branch --show-current) -join ''
    $taskStatus = (& git status --short --untracked-files=all) -join "`n"
    $taskDiff = (& git --no-pager diff --binary HEAD) -join "`n"
    Write-TaskText (Join-Path $taskOutputRoot 'worktree-status.txt') ($taskStatus + "`n")
    Write-TaskText (Join-Path $taskOutputRoot 'worktree.patch') ($taskDiff + "`n")

    $taskLaunches = @($taskVersions | ForEach-Object {
        Get-Content -LiteralPath (Join-Path $taskOutputRoot "mc-$_.json") -Raw | ConvertFrom-Json
    })
    $taskNative = $taskLaunches[0].nativeLibrary
    $taskCompiler = if ($env:SLANGC) { $env:SLANGC }
        elseif ($env:VULKAN_SDK) { Join-Path $env:VULKAN_SDK 'Bin/slangc.exe' }
        else { 'slangc' }
    $taskJavaVersions = @($taskLaunches.executable | Select-Object -Unique | ForEach-Object {
        [ordered]@{ executable = $_; version = Read-TaskTool $_ @('--version') }
    })
    $taskBuild = [ordered]@{
        schemaVersion = 1
        preparedAtUtc = [DateTime]::UtcNow.ToString('o')
        repository = $taskRepositoryRoot
        outputDirectory = $taskOutputRoot
        snapshot = $taskIsSnapshot
        revision = "$taskRevision"
        branch = $taskBranch
        dirty = [bool]$taskStatus
        gitStatus = $taskStatus
        gitDiffFile = Join-Path $taskOutputRoot 'worktree.patch'
        gitDiffSha256 = (Get-FileHash -LiteralPath (Join-Path $taskOutputRoot 'worktree.patch') -Algorithm SHA256).Hash
        gitDiffIncludesUntrackedContents = $false
        nativeLibrary = $taskNative
        nativeSha256 = (Get-FileHash -LiteralPath $taskNative -Algorithm SHA256).Hash
        nativePdb = $taskLaunches[0].nativePdb
        compiler = [ordered]@{
            slangExecutable = $taskCompiler
            slangVersion = Read-TaskTool $taskCompiler @('-version')
            rustc = Read-TaskTool 'rustc' @('-vV')
            cargo = Read-TaskTool 'cargo' @('--version')
            shaderBuildSource = Join-Path $taskOutputRoot 'shader-build.rs'
        }
        javaVersions = $taskJavaVersions
        gradleArguments = $taskGradleArguments
        launchFiles = @($taskVersions | ForEach-Object { "mc-$_.json" })
        sourceMappings = if ($taskIsSnapshot) { @([ordered]@{
            original = Join-Path $taskRepositoryRoot 'crates/prime-vulkan/shaders'
            snapshot = Join-Path $taskOutputRoot 'workspace/crates/prime-vulkan/shaders'
        }) } else { @() }
        snapshotBoundaries = 'Workspace runtime classpath, native DLL/PDB, launch/config files and shader sources are copied for snapshots. Java, Gradle cache dependencies, Minecraft assets, saves, resource packs and run directory remain shared. Argfiles use absolute paths and are not relocatable. worktree.patch excludes untracked file contents; gitStatus and actual binary hashes identify that boundary.'
    }
    Copy-Item -LiteralPath (Join-Path $taskRepositoryRoot 'crates/prime-vulkan/build.rs') -Destination (Join-Path $taskOutputRoot 'shader-build.rs')
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $taskOutputRoot 'prepare-tool.ps1')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'nsight-launch.init.gradle') -Destination (Join-Path $taskOutputRoot 'launch-tool.init.gradle')
    Write-TaskText (Join-Path $taskOutputRoot 'build.json') ($taskBuild | ConvertTo-Json -Depth 20)
    Write-Output "Nsight launch files are ready: $taskOutputRoot"
    Write-Output 'Only runClient prerequisites were executed; no Minecraft process was started.'
} finally {
    Pop-Location
}
