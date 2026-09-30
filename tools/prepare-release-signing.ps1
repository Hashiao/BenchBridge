param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome -and $env:ProgramFiles) {
    $benchStudioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $benchStudioJbr 'bin\keytool.exe')) { $JavaHome = $benchStudioJbr }
}
if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome.' }
$benchRoot = Split-Path -Parent $PSScriptRoot
$benchPrivate = Join-Path $benchRoot '.local'
$benchKey = Join-Path $benchPrivate 'release-signing.p12'
$benchProperties = Join-Path $benchPrivate 'release-signing.properties'
$benchKeytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $benchKeytool)) { throw 'JDK keytool was not found.' }
New-Item -ItemType Directory -Path $benchPrivate -Force | Out-Null
if ((Test-Path -LiteralPath $benchKey) -and (Test-Path -LiteralPath $benchProperties)) {
    Write-Output 'Existing release signing material retained.'
    return
}
if ((Test-Path -LiteralPath $benchKey) -or (Test-Path -LiteralPath $benchProperties)) {
    throw 'Incomplete signing material. Restore the matching key/properties pair; no existing file was overwritten.'
}

$benchRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$benchBytes = New-Object byte[] 48
try { $benchRandom.GetBytes($benchBytes) } finally { $benchRandom.Dispose() }
$benchPassword = [Convert]::ToBase64String($benchBytes)
$benchText = "storeFile=.local/release-signing.p12`nstorePassword=$benchPassword`nkeyAlias=benchbridge-release`nkeyPassword=$benchPassword`n"
# 先保存密码，再创建密钥，避免生成过程被中断后无法恢复。
# Save the password before creating the key so interrupted generation remains recoverable.
[IO.File]::WriteAllText($benchProperties, $benchText, (New-Object Text.UTF8Encoding($false)))
$benchOldPassword = [Environment]::GetEnvironmentVariable('BENCHBRIDGE_RELEASE_KEY_PASSWORD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('BENCHBRIDGE_RELEASE_KEY_PASSWORD', $benchPassword, 'Process')
    # 命令行仅传环境变量名，不传密码明文。
    # Pass only the environment variable name on the command line, never the password.
    $ErrorActionPreference = 'Continue'
    & $benchKeytool -genkeypair -keystore $benchKey -storetype PKCS12 -alias benchbridge-release `
        -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 `
        -dname 'CN=BenchBridge Local Release' -storepass:env BENCHBRIDGE_RELEASE_KEY_PASSWORD `
        -keypass:env BENCHBRIDGE_RELEASE_KEY_PASSWORD -noprompt
    $benchExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($benchExit -ne 0) { throw 'Release signing key generation failed. Retain the properties file for recovery.' }
    Write-Output 'Release signing material created under .local; credentials were not printed.'
} finally {
    [Environment]::SetEnvironmentVariable('BENCHBRIDGE_RELEASE_KEY_PASSWORD', $benchOldPassword, 'Process')
}
