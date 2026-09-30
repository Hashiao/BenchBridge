param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [int]$ProxyPort = 0,
    [switch]$NoProxy,
    [switch]$Offline,
    [string[]]$Tasks = @(':app:assembleDebug', ':app:lintDebug', ':app:lintRelease', ':app:assembleRelease')
)

$ErrorActionPreference = 'Stop'
$benchProjectRoot = Split-Path -Parent $PSScriptRoot
$benchLocalRoot = Join-Path $benchProjectRoot '.local'
# 优先使用显式参数和环境变量，其次读取 Android Studio 的本机 SDK 配置。
# Prefer explicit arguments and environment variables, then Android Studio's local SDK configuration.
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
$benchSdkProperties = Join-Path $benchProjectRoot 'local.properties'
if (-not $SdkPath -and (Test-Path -LiteralPath $benchSdkProperties)) {
    $benchSdkEntry = Get-Content -LiteralPath $benchSdkProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($benchSdkEntry) { $SdkPath = $benchSdkEntry.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
if (-not $SdkPath) { throw 'Set ANDROID_HOME or pass -SdkPath.' }
if (-not $JavaHome -and $env:ProgramFiles) {
    $benchStudioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $benchStudioJbr 'bin\java.exe')) { $JavaHome = $benchStudioJbr }
}
if (-not $JavaHome) { throw 'Set JAVA_HOME to JDK 25 or pass -JavaHome.' }
$benchJava = Join-Path $JavaHome 'bin\java.exe'
$benchKeytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $benchJava)) { throw "JDK not found: $benchJava" }
if (-not (Test-Path -LiteralPath (Join-Path $SdkPath 'platform-tools\adb.exe'))) {
    throw "Android SDK not found: $SdkPath"
}

New-Item -ItemType Directory -Path $benchLocalRoot -Force | Out-Null
$benchPreviousEnvironment = @{}
foreach ($benchName in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME')) {
    $benchPreviousEnvironment[$benchName] = [Environment]::GetEnvironmentVariable($benchName, 'Process')
}

Push-Location -LiteralPath $benchProjectRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_USER_HOME = Join-Path $benchLocalRoot 'android-user-home'
    $env:GRADLE_USER_HOME = Join-Path $benchLocalRoot 'gradle-user-home'

    $benchDebugKey = Join-Path $benchLocalRoot 'debug.keystore'
    if (-not (Test-Path -LiteralPath $benchDebugKey)) {
        # 使用 Android 标准调试凭据；Release 使用独立签名。
        # Use Android's standard debug credentials; Release uses separate signing material.
        & $benchKeytool -genkeypair -keystore $benchDebugKey -storepass android `
            -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' `
            -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the workspace debug keystore.' }
    }

    $benchArguments = @('--no-daemon', '--console=plain')
    if (-not $NoProxy -and $ProxyPort -gt 0) {
        $benchArguments += @(
            '-Dhttp.proxyHost=127.0.0.1', "-Dhttp.proxyPort=$ProxyPort",
            '-Dhttps.proxyHost=127.0.0.1', "-Dhttps.proxyPort=$ProxyPort",
            '-Dhttp.nonProxyHosts=localhost|127.*|[::1]'
        )
    }
    if ($Offline) { $benchArguments += '--offline' }
    $benchArguments += $Tasks
    Write-Output "Project: $benchProjectRoot"
    Write-Output "JDK: $JavaHome"
    Write-Output "Gradle cache: $env:GRADLE_USER_HOME"

    # Windows PowerShell 会将部分 JVM 标准错误输出视为终止错误。
    # Windows PowerShell may treat JVM stderr warnings as terminating errors.
    $ErrorActionPreference = 'Continue'
    & (Join-Path $benchProjectRoot 'gradlew.bat') @benchArguments |
        Tee-Object -FilePath (Join-Path $benchLocalRoot 'build.log')
    $benchExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($benchExitCode -ne 0) { throw "Gradle failed with exit code $benchExitCode. See .local\build.log." }
} finally {
    Pop-Location
    foreach ($benchName in $benchPreviousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($benchName, $benchPreviousEnvironment[$benchName], 'Process')
    }
}
