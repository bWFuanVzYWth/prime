param(
    [switch]$VerifyOnly,
    [switch]$CheckLatest
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content -LiteralPath (Join-Path $repoRoot 'third_party/streamline/sdk-lock.json') -Raw |
    ConvertFrom-Json
if ($lock.schema -ne 1) { throw 'Unsupported SDK lock schema' }

function Assert-Hash([string]$Path, [string]$Expected) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing SDK file: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $Expected) { throw "SDK SHA-256 mismatch: $Path" }
}

function Resolve-RepositoryFile([string]$Relative) {
    $path = [IO.Path]::GetFullPath((Join-Path $repoRoot $Relative))
    if (!$path.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "SDK lock path escapes the repository: $Relative"
    }
    return $path
}

if ($CheckLatest) {
    foreach ($name in @('streamline', 'dlss')) {
        $source = $lock.sources.$name
        $repository = $source.repository.Substring('https://github.com/'.Length)
        $release = Invoke-RestMethod "https://api.github.com/repos/$repository/releases/latest"
        Write-Output "$name latest=$($release.tag_name), pinned=$($source.tag)"
        if ($release.tag_name -ne $source.tag) {
            throw 'A newer SDK is available. Review its API/runtime changes and regenerate the lock before updating.'
        }
    }
}

if (!$VerifyOnly) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $cache = Join-Path $repoRoot 'artifacts/streamline-sdk'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    foreach ($property in $lock.sources.PSObject.Properties) {
        $name = $property.Name
        $source = $property.Value
        $archivePath = Join-Path $cache $source.archive
        if (!(Test-Path -LiteralPath $archivePath)) {
            Write-Output "Downloading $($source.url)"
            Invoke-WebRequest -Uri $source.url -OutFile $archivePath
        }
        Assert-Hash $archivePath $source.sha256
        $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            foreach ($file in $lock.files | Where-Object source -eq $name) {
                $entry = $archive.GetEntry($file.entry)
                if (!$entry) { throw "Missing SDK archive entry: $($file.entry)" }
                $destination = Resolve-RepositoryFile $file.path
                New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
                Assert-Hash $destination $file.sha256
            }
            if ($name -eq $lock.dlss_runtime_cross_check.source) {
                $entry = $archive.GetEntry($lock.dlss_runtime_cross_check.entry)
                if (!$entry) { throw 'DLSS SDK does not contain the locked RR runtime' }
                $stream = $entry.Open()
                $hasher = [Security.Cryptography.SHA256]::Create()
                try {
                    $actual = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '')
                    if ($actual -ne $lock.dlss_runtime_cross_check.sha256) {
                        throw 'DLSS SDK RR runtime differs from the locked Streamline RR runtime'
                    }
                } finally {
                    $hasher.Dispose()
                    $stream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    }
}

foreach ($file in $lock.files) {
    Assert-Hash (Resolve-RepositoryFile $file.path) $file.sha256
}
foreach ($file in $lock.files | Where-Object path -like '*.dll') {
    $path = Resolve-RepositoryFile $file.path
    $signature = Get-AuthenticodeSignature -LiteralPath $path
    if ($signature.Status -ne 'Valid' -or
        $signature.SignerCertificate.Subject -notmatch '(^|, )CN=NVIDIA Corporation(,|$)') {
        throw "SDK runtime is not validly signed by NVIDIA: $path"
    }
    Write-Output "$($file.path): $((Get-Item -LiteralPath $path).VersionInfo.FileVersion) / NVIDIA signature valid"
}
Write-Output "Verified $($lock.files.Count) SDK files against the checked-in SHA-256 lock."
