$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$signingDirectory = Join-Path $projectRoot '.tooling\signing'
$keystorePath = Join-Path $signingDirectory 'family-ledger-release.jks'
$propertiesPath = Join-Path $projectRoot 'keystore.properties'

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw 'Release signing files already exist. Refusing to overwrite the long-term upgrade key.'
}

$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
$keytool = if ($keytoolCommand) {
    $keytoolCommand.Source
} else {
    Get-ChildItem (Join-Path $env:ProgramFiles 'Java') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'bin\keytool.exe' } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
}
if (-not $keytool) { throw 'Cannot find keytool.exe in PATH or Program Files\Java.' }
$passwordBytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')

New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
& $keytool -genkeypair -noprompt -v `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -storepass $password `
    -keypass $password `
    -alias family-ledger `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10950 `
    -dname 'CN=Family Ledger, OU=Family, O=Family Ledger, L=Shanghai, ST=Shanghai, C=CN'
if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

$properties = @(
    'storeFile=.tooling/signing/family-ledger-release.jks'
    "storePassword=$password"
    'keyAlias=family-ledger'
    "keyPassword=$password"
) -join [Environment]::NewLine
[IO.File]::WriteAllText(
    $propertiesPath,
    $properties + [Environment]::NewLine,
    (New-Object Text.UTF8Encoding($false))
)

Write-Output 'Release key created successfully.'
Write-Output "Keystore: $keystorePath"
Write-Output "Properties: $propertiesPath"
Write-Output 'Back up these two files separately. The password was not printed.'
