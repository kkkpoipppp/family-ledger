$bytes = New-Object byte[] 20
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$syncKey = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $hash = [BitConverter]::ToString(
        $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($syncKey))
    ).Replace('-', '').ToLowerInvariant()
} finally {
    $sha.Dispose()
}

Write-Output "FAMILY_SYNC_KEY (save privately; enter on both phones):"
Write-Output $syncKey
Write-Output ""
Write-Output "LEDGER_SYNC_KEY_SHA256 (enter in CloudBase environment variable):"
Write-Output $hash
