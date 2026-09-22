# 鸿蒙 HAP 本地签名 + 打包(不经 DevEco GUI)
#
# 用途:把 assembleHap 产出的 entry-default-unsigned.hap 用 SDK 自带的签名材料
# (OpenHarmony.p12 测试密钥 + Debug profile 模板)签成一个可安装的 HAP。
#
# ⚠ 重要限制(务必先读):
#   SDK 里的 `UnsgnedDebugProfileTemplate.json` 是 **OpenHarmony 社区** 的调试模板,
#   它的 `debug-info.device-ids` 里只列了两台白名单设备。华为**零售机**的
#   HarmonyOS 验证链要求 profile 由 **华为签发**(带你的调试证书与设备 UDID),
#   因此本脚本签出来的 HAP:
#     · 能装:OpenHarmony 开发板、部分开发者模式设备、模拟器(若信任社区根证书)
#     · 装不上:华为零售机 —— 那种情况必须用 DevEco 的"自动签名"(见文件末尾说明)
#
# 用法:
#   pwsh -File scripts/sign-hap.ps1
#   pwsh -File scripts/sign-hap.ps1 -ProfileType release
#
# 输出:harmony/dist/  下的签名 HAP 与中间产物(keystore / cert / p7b)

param(
    [string]$DevEcoHome = 'D:\DevEco Studio',
    [string]$ProjectRoot = '',
    [string]$BundleName = 'com.example.devxiangsu',
    [ValidateSet('debug', 'release')]
    [string]$ProfileType = 'debug'
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

function Write-Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "    [ok] $text" -ForegroundColor Green }
function Write-WarnLine($text) { Write-Host "    [warn] $text" -ForegroundColor Yellow }
function Write-ErrLine($text) { Write-Host "    [error] $text" -ForegroundColor Red }

# 写文件,强制 **UTF-8 无 BOM**。
# PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 BOM,而签名/密钥工具读到 BOM
# 会报 "Illegal header: -----END CERTIFICATE-----" 这类莫名错误。
function Write-NoBom {
    param([string]$Path, [string]$Text)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

# java / keytool 会往 stderr 打正常日志,而 $ErrorActionPreference='Stop' 会把
# "原生命令写了 stderr" 升级成终止错误(之前 hvigor 就栽在这个坑上)。
# 统一用这个包装函数调用它们:内部临时把错误偏好降为 Continue。
function Invoke-Tool {
    param([string]$Exe, [string[]]$ToolArgs)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @ToolArgs 2>&1 | ForEach-Object { Write-Host "    $_" }
    } finally {
        $ErrorActionPreference = $saved
    }
}

if (-not $ProjectRoot) {
    # 脚本位于 <项目>/scripts/,其父目录就是鸿蒙工程根
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $ProjectRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path
}

$java = Join-Path $DevEcoHome 'jbr\bin\java.exe'
$lib = Join-Path $DevEcoHome 'sdk\default\openharmony\toolchains\lib'
$signTool = Join-Path $lib 'hap-sign-tool.jar'
$sdkKeystore = Join-Path $lib 'OpenHarmony.p12'
$template = Join-Path $lib ("Unsgned{0}ProfileTemplate.json" -f ($ProfileType.Substring(0,1).ToUpper() + $ProfileType.Substring(1)))
$profileCert = Join-Path $lib ("OpenHarmonyProfile{0}.pem" -f ($ProfileType.Substring(0,1).ToUpper() + $ProfileType.Substring(1)))

$dist = Join-Path $ProjectRoot 'harmony\dist'
$unsigned = Join-Path $ProjectRoot 'entry\build\default\outputs\default\entry-default-unsigned.hap'

# ---- 0) 前置检查 ----
Write-Step '检查依赖'
foreach ($p in @($java, $signTool, $sdkKeystore, $template, $profileCert)) {
    if (-not (Test-Path $p)) { Write-ErrLine "缺少文件:$p"; exit 1 }
}
if (-not (Test-Path $unsigned)) { Write-ErrLine "没有未签名产物:$unsigned(先跑 scripts\build-hap.bat)"; exit 1 }
Write-Ok "java      : $java"
Write-Ok "signTool  : $signTool"
Write-Ok "template  : $(Split-Path $template -Leaf)"
New-Item -ItemType Directory -Path $dist -Force | Out-Null
Write-Ok "输出目录  : $dist"

# SDK 里的 p12 不要动,复制一份到 dist 里用
$workP12 = Join-Path $dist 'xiangsu-sign.p12'
Copy-Item $sdkKeystore $workP12 -Force

# 社区测试密钥的固定密码(公开值,见 SDK 文档)
$keyPwd = '123456'
$ksPwd = '123456'

# SDK 密钥库里的 `openharmony application release` / `profile release` 都是**自签叶证书**
# (自己签发给自己),签名器会报 "verify certificate chain failed"。
# 唯一可用的路:用密钥库里的 `openharmony application ca`(中间 CA,带私钥)
# 给**我们自己生成的应用密钥**签发证书链。
$appAlias = 'xiangsu-app-key'
$profileKeyAlias = $appAlias

$appCert = Join-Path $dist 'app-cert-chain.cer'
$profileKeyCert = Join-Path $dist 'profile-release.pem'
$profileJson = Join-Path $dist 'xiangsu-profile.json'
$signedProfile = Join-Path $dist 'xiangsu-profile.p7b'
$signedHap = Join-Path $dist ("xiangsu-{0}-signed.hap" -f $ProfileType)

# ---- 1) 先把中间 CA / 根 CA 证书导出来(生成证书链要用)----
Write-Step '导出中间 CA 与根 CA 证书'
$keytool = Join-Path $DevEcoHome 'jbr\bin\keytool.exe'
if (-not (Test-Path $keytool)) { Write-ErrLine "缺少 keytool:$keytool"; exit 1 }
$caCert = Join-Path $dist 'ca.cer'
$rootCert = Join-Path $dist 'root.cer'
Invoke-Tool -Exe $keytool -ToolArgs @('-exportcert', '-rfc', '-alias',
    'openharmony application ca', '-keystore', $workP12, '-storetype', 'PKCS12',
    '-storepass', $ksPwd, '-file', $caCert)
Invoke-Tool -Exe $keytool -ToolArgs @('-exportcert', '-rfc', '-alias',
    'openharmony application root ca', '-keystore', $workP12, '-storetype', 'PKCS12',
    '-storepass', $ksPwd, '-file', $rootCert)
Write-Ok 'CA / 根证书已导出'

# ---- 2) 生成我们自己的应用密钥,并让中间 CA 给它签发**证书链** ----
Write-Step '生成应用密钥并由 CA 签发证书链'
Invoke-Tool -Exe $java -ToolArgs @('-jar', $signTool, 'generate-keypair',
    '-keyAlias', $appAlias, '-keyPwd', $keyPwd, '-keyAlg', 'ECC', '-keySize', 'NIST-P-256',
    '-keystoreFile', $workP12, '-keystorePwd', $ksPwd)
Invoke-Tool -Exe $java -ToolArgs @('-jar', $signTool, 'generate-app-cert',
    '-keyAlias', $appAlias, '-keyPwd', $keyPwd,
    '-issuer', 'C=CN,O=OpenHarmony,OU=OpenHarmony Team,CN=OpenHarmony Application CA',
    '-issuerKeyAlias', 'openharmony application ca', '-issuerKeyPwd', $ksPwd,
    '-subject', 'C=CN,O=OpenHarmony,OU=OpenHarmony Team,CN=OpenHarmony Application Release',
    '-keystoreFile', $workP12, '-keystorePwd', $ksPwd,
    '-outForm', 'certChain', '-outFile', $appCert,
    '-subCaCertFile', $caCert, '-rootCaCertFile', $rootCert,
    '-signAlg', 'SHA256withECDSA', '-validity', '3650')
if (-not (Test-Path $appCert)) { Write-ErrLine '签发应用证书链失败'; exit 1 }
Write-Ok "应用证书链:$(Split-Path $appCert -Leaf)"

# ---- 3) profile 签名用的证书链:直接用刚由 CA 签发的应用证书链 ----
Write-Step '准备 profile 签名证书链'
$profileKeyChain = $appCert
Write-Ok "profile 签名证书链:$(Split-Path $profileKeyChain -Leaf)"

# ---- 3) 改 Profile:换成我们自己的包名,并延长有效期 ----
# 用 Python 生成:PowerShell 的 ConvertTo-Json 会把 PEM 里的 \n 二次转义成 \\n,
# 签名器读到字面反斜杠 n 就报 "Illegal base64 character 20"。
Write-Step '生成 Profile(改 bundle-name + 有效期 + 内嵌证书)'
# 逐级向上找仓库里的 tools/make_harmony_profile.py
# (脚本可能位于 仓库/harmony/scripts,也可能被同步到 DEVxiangs/scripts)
$profileTool = ''
$dir = $PSScriptRoot
for ($i = 0; $i -lt 5 -and $dir; $i++) {
    $cand = Join-Path $dir 'tools\make_harmony_profile.py'
    if (Test-Path $cand) { $profileTool = $cand; break }
    $parent = Split-Path -Parent $dir
    if ($parent -eq $dir) { break }
    $dir = $parent
}
if (-not $profileTool) {
    Write-ErrLine '找不到 make_harmony_profile.py(在仓库 tools/ 下)'
    exit 1
}
Write-Ok "profile 生成器:$(Split-Path $profileTool -Leaf)"
Invoke-Tool -Exe 'python' -ToolArgs @($profileTool, '--template', $template,
    '--bundle', $BundleName, '--out', $profileJson, '--type', $ProfileType)
if (-not (Test-Path $profileJson)) { Write-ErrLine 'Profile 生成失败'; exit 1 }
Write-Ok "包名: $BundleName"
Write-WarnLine "device-ids 白名单仍是模板里的两台设备(华为零售机需用 DevEco 自动签名)"

# ---- 4) 用 profile 签名证书签 Profile ----
Write-Step '签名 Profile → p7b'
Invoke-Tool -Exe $java -ToolArgs @('-jar', $signTool, 'sign-profile',
    '-mode', 'localSign',
    '-keyAlias', $profileKeyAlias, '-keyPwd', $keyPwd,
    '-profileCertFile', $profileKeyChain,
    '-inFile', $profileJson, '-signAlg', 'SHA256withECDSA',
    '-outFile', $signedProfile, '-keystoreFile', $workP12, '-keystorePwd', $ksPwd)
if (-not (Test-Path $signedProfile)) {
    Write-ErrLine 'Profile 签名失败'
    exit 1
}
Write-Ok "Profile:$(Split-Path $signedProfile -Leaf)"

# ---- 5) 签名 HAP ----
Write-Step '签名 HAP'
Invoke-Tool -Exe $java -ToolArgs @('-jar', $signTool, 'sign-app',
    '-mode', 'localSign', '-keyAlias', $appAlias, '-keyPwd', $keyPwd,
    '-appCertFile', $appCert, '-profileFile', $signedProfile,
    '-inFile', $unsigned, '-signAlg', 'SHA256withECDSA',
    '-outFile', $signedHap, '-keystoreFile', $workP12, '-keystorePwd', $ksPwd)

if (Test-Path $signedHap) {
    $size = [math]::Round((Get-Item $signedHap).Length / 1MB, 2)
    Write-Ok "签名 HAP:$signedHap($size MB)"
} else {
    Write-ErrLine '签名失败:没有生成 HAP'
    exit 1
}

# ---- 6) 校验 ----
Write-Step '校验签名'
Invoke-Tool -Exe $java -ToolArgs @('-jar', $signTool, 'verify-app',
    '-inFile', $signedHap,
    '-outCertChain', (Join-Path $dist 'verify-cert.cer'),
    '-outProfile', (Join-Path $dist 'verify-profile.p7b'))
if (Test-Path (Join-Path $dist 'verify-cert.cer')) {
    Write-Ok '校验完成(证书链已导出)'
} else {
    Write-WarnLine '校验没导出证书链,签名本身已完成,可继续'
}

# ---- 7) 把产物收拢到仓库里,方便下载/分发 ----
$distRepo = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'harmony\dist'
if ($distRepo -and -not (Test-Path $distRepo)) { New-Item -ItemType Directory -Path $distRepo -Force | Out-Null }
$finalName = "像素填空-鸿蒙-v$ProfileType-已签名.hap"
$finalPath = Join-Path $distRepo $finalName
Copy-Item $signedHap $finalPath -Force
Copy-Item $profileJson (Join-Path $distRepo 'profile.json') -Force -ErrorAction SilentlyContinue
Write-Ok "已复制到:$finalPath"

Write-Host ''
Write-Host '---------------- 安装说明(重要)----------------' -ForegroundColor Cyan
Write-Host "产物:$finalPath"
Write-Host ''
Write-Host '【方式 A】模拟器 / 开发板 / 已开开发者模式的设备(可能可用)'
Write-Host "   hdc install -r `"$finalPath`""
Write-Host ''
Write-Host '【方式 B】华为零售机(推荐,唯一稳妥的方式)'
Write-Host '   本 HAP 的 profile 来自 OpenHarmony 社区模板,它带 device-ids 白名单:'
Write-Host '   零售机校验不过会报 9568289(signature verify failed)/ 9568320 等。'
Write-Host '   零售机请走 DevEco 自动签名(只需做一次):'
Write-Host '     1) 手机开「开发者模式 + USB 调试」,用数据线连电脑'
Write-Host '     2) DevEco Studio → File → Project Structure → Signing Configs'
Write-Host '        → 勾选 Automatically generate signature → 登录华为账号 → OK'
Write-Host '     3) 点工具栏 ▶ Run(或 Build → Build Hap(s)),DevEco 会自动装到手机上'
Write-Host '   自动签名会把**你这台手机的 UDID** 和华为签发的证书写进 profile,'
Write-Host '   这是零售机安装第三方 HAP 的唯一合法路径(命令行工具做不到)。'
Write-Host '------------------------------------------------' -ForegroundColor Cyan
Write-Host '------------------------------------------' -ForegroundColor Cyan
