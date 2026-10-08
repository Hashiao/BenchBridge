param([string]$ClangPath = '', [string]$MsvcVersion = '14.44.35207')
$ErrorActionPreference='Stop'
$benchRoot=Split-Path -Parent $PSScriptRoot
$benchOutput=Join-Path $benchRoot '.local/apple-core'
New-Item -ItemType Directory -Path $benchOutput -Force | Out-Null
if(-not $ClangPath) {
    $benchSdkLine=Get-Content (Join-Path $benchRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    $benchSdk=$benchSdkLine.Substring(8).Replace('\:',':').Replace('\\','\')
    $ClangPath=Join-Path $benchSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
}
$benchVsWhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$benchVs=& $benchVsWhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
$benchVC=Join-Path $benchVs "VC/Tools/MSVC/$MsvcVersion"
if(-not (Test-Path (Join-Path $benchVC 'include'))){throw 'Install a Clang-compatible MSVC toolset or pass -MsvcVersion.'}
$benchKits=Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10'
$benchSDK=(Get-ChildItem (Join-Path $benchKits 'Include') -Directory | Sort-Object Name -Descending | Select-Object -First 1).Name
$benchOldInclude=$env:INCLUDE;$benchOldLib=$env:LIB
try {
    $env:INCLUDE="$benchVC\include;$benchKits\Include\$benchSDK\ucrt;$benchKits\Include\$benchSDK\shared;$benchKits\Include\$benchSDK\um"
    $env:LIB="$benchVC\lib\x64;$benchKits\Lib\$benchSDK\ucrt\x64;$benchKits\Lib\$benchSDK\um\x64"
    $benchObjects=@()
    foreach($benchSource in @('BenchCore','SharedKernels','SharedCompute','CoreTests')) {
        $benchObject=Join-Path $benchOutput "$benchSource.obj"
        & $ClangPath --target=x86_64-pc-windows-msvc -std=c++20 -O2 -fno-lto -Wall -Wextra -Werror -c (Join-Path $benchRoot "ios/Native/$benchSource.cpp") -o $benchObject
        if($LASTEXITCODE -ne 0){throw "Native compilation failed: $benchSource"}
        $benchObjects+=$benchObject
    }
    $benchExe=Join-Path $benchOutput 'core-tests.exe'
    & (Join-Path $benchVC 'bin/Hostx64/x64/link.exe') /nologo @benchObjects libcmt.lib libvcruntime.lib libucrt.lib oldnames.lib kernel32.lib "/out:$benchExe"
    if($LASTEXITCODE -ne 0){throw 'Native link failed'}
    & $benchExe (Join-Path $benchOutput 'test-files')
    if($LASTEXITCODE -ne 0){throw 'Native validation failed'}
} finally { $env:INCLUDE=$benchOldInclude;$env:LIB=$benchOldLib }
