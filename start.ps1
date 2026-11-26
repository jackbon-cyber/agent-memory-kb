<#
.SYNOPSIS
    一键启动 AgentScope 记忆知识库（ChromaDB + 后端 jar）。

.DESCRIPTION
    启动顺序：
    1. 校验 JDK（21+）与 Python/ChromaDB 可用性；
    2. 拉起 ChromaDB（已运行则幂等跳过）；
    3. 以 jar 方式启动后端，等待 /api/health 就绪后打印访问地址。

    后端进程以后台方式启动，PID 记录在 data/app.pid，
    日志落在 data/app.log 与 data/app.err.log。

.PARAMETER ApiKey
    模型服务凭据。缺省时依次尝试：环境变量 DASHSCOPE_API_KEY、
    scripts\.local-credentials.ps1。**不会**把凭据写入任何文件。

.PARAMETER Port
    后端监听端口，与 application.yml 的 server.port 默认值一致。

.PARAMETER ChromaPort
    ChromaDB 监听端口，与 application.yml 的 memorykb.chroma.base-url 默认端口一致。

.PARAMETER SkipChroma
    跳过 ChromaDB 启动（假定外部已有 ChromaDB 服务）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File start.ps1 -ApiKey "sk-xxxx"
    powershell -ExecutionPolicy Bypass -File start.ps1
#>

[CmdletBinding()]
param(
    [string]$ApiKey,
    [int]$Port = 8088,
    [int]$ChromaPort = 8765,
    [switch]$SkipChroma
)

$ErrorActionPreference = 'Stop'

# 解析工程根目录。
#
# <b>本脚本在发布包里位于根目录，在源码树里位于 scripts\release\</b>，
# 两处的父目录层级不同 —— 写死 "父目录的父目录" 在发布包里会指向
# 解压目录的上级（找不到 jar），在源码树里又会指向 scripts\。
# 因此按"哪个候选目录里有 jar"来判定根目录，与脚本自身所在层级解耦。
function Resolve-AppRoot {
    param([string]$ScriptDirectory)

    $candidates = @(
        $ScriptDirectory
        (Split-Path -Parent $ScriptDirectory)
        (Split-Path -Parent (Split-Path -Parent $ScriptDirectory))
    )

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        if (Test-Path (Join-Path $candidate 'agent-memory-kb.jar')) {
            return $candidate
        }
    }

    return $null
}

$AppRoot = Resolve-AppRoot -ScriptDirectory $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($AppRoot)) {
    throw "未找到 agent-memory-kb.jar。请确认解压完整，或从包含该 jar 的目录运行本脚本。"
}

$JarPath = Join-Path $AppRoot 'agent-memory-kb.jar'
$DataDir = Join-Path $AppRoot 'data'
$LogPath = Join-Path $DataDir 'app.log'
$ErrLogPath = Join-Path $DataDir 'app.err.log'
$PidPath = Join-Path $DataDir 'app.pid'

# ---------------------------------------------------------------- 前置校验 ----

if (-not (Test-Path $JarPath)) {
    throw "未找到 $JarPath。该目录下应有 agent-memory-kb.jar。"
}

# 解析 java 可执行文件：优先 PATH 上的命令。
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $javaCommand) {
    throw "未找到 java。请安装 JDK 21 并确保 java 在 PATH 中。"
}

# 校验 JDK 大版本：21 以下无法编译运行本工程（用了 record 与 pattern matching）。
#
# <b>java -version 把版本号写到 stderr</b>，这是 JVM 的既定行为。
# 在 $ErrorActionPreference='Stop' 下，原生命令的 stderr 会被 PowerShell
# 升级成终止性错误 —— 于是"版本检查"本身成了启动失败的原因。
# 因此这里临时把错误升级关掉，把 stderr 合并进来再解析。
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $javaVersionOutput = & $javaCommand.Source -version 2>&1 | Out-String
} finally {
    $ErrorActionPreference = $previousErrorAction
}

$javaMajor = 0
if ($javaVersionOutput -match 'version "(\d+)') {
    $javaMajor = [int]$matches[1]
}
if ($javaMajor -lt 21) {
    throw "需要 JDK 21 或更高版本，当前为 $javaMajor。$javaVersionOutput"
}
Write-Host "==> JDK 版本 $javaMajor，符合要求"

# ---------------------------------------------------------------- 凭据解析 ----

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $ApiKey = $env:DASHSCOPE_API_KEY
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $credentialFile = Join-Path $AppRoot 'scripts\.local-credentials.ps1'
    if (Test-Path $credentialFile) {
        . $credentialFile
        $ApiKey = $env:DASHSCOPE_API_KEY
    }
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Warning "未提供模型服务凭据：嵌入与对话功能不可用（上传与检索会明确报错，其余接口正常）。"
    Write-Warning "  提供方式：-ApiKey 参数 / DASHSCOPE_API_KEY 环境变量 / scripts\.local-credentials.ps1"
}

# ---------------------------------------------------------------- 启动 Chroma ----

if (-not $SkipChroma) {
    $chromaScript = Join-Path $AppRoot 'scripts\chroma.ps1'
    if (Test-Path $chromaScript) {
        Write-Host "==> 启动 ChromaDB"
        & $chromaScript -Action start -Port $ChromaPort
    } else {
        Write-Warning "未找到 scripts\chroma.ps1，跳过 ChromaDB 启动。"
    }
}

# -------------------------------------------------------------- 启动后端 jar ----

# 端口已被占用时直接报错退出：静默"启动成功"会让用户以为跑起来了，
# 实际请求打到了别的服务上。
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -ne $existing) {
    throw "端口 $Port 已被占用（PID $($existing.OwningProcess)）。请先执行 stop.ps1，或用 -Port 指定其它端口。"
}

if (-not (Test-Path $DataDir)) {
    New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
}

Write-Host "==> 启动后端：$JarPath"
Write-Host "    日志：$LogPath"

$javaArguments = @(
    '-Dfile.encoding=UTF-8'
    '-jar'
    $JarPath
    "--server.port=$Port"
    "--memorykb.chroma.base-url=http://localhost:$ChromaPort"
)

$process = Start-Process -FilePath $javaCommand.Source -ArgumentList $javaArguments `
    -RedirectStandardOutput $LogPath -RedirectStandardError $ErrLogPath -PassThru -NoNewWindow

Set-Content -Path $PidPath -Value $process.Id -Encoding ascii

# 等待健康检查就绪。服务真正起来才算启动成功 —— 只等进程存在是不够的，
# 那只能说明 JVM 起来了，不代表 Spring 上下文已经就绪。
$deadline = (Get-Date).AddSeconds(90)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if ($process.HasExited) {
        Write-Host ""
        Write-Host "[FAIL] 后端进程已退出（退出码 $($process.ExitCode)）。最后 30 行日志："
        if (Test-Path $LogPath) { Get-Content $LogPath -Tail 30 }
        if (Test-Path $ErrLogPath) { Get-Content $ErrLogPath -Tail 30 }
        exit 1
    }
    try {
        $response = Invoke-WebRequest "http://localhost:$Port/api/health" -TimeoutSec 3 -UseBasicParsing
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # 尚未就绪，继续等待。
    }
}

if (-not $ready) {
    Write-Host ""
    Write-Host "[FAIL] 后端在 90 秒内未就绪。最后 30 行日志："
    if (Test-Path $LogPath) { Get-Content $LogPath -Tail 30 }
    exit 1
}

Write-Host ""
Write-Host "=========================================================="
Write-Host " 启动完成"
Write-Host "=========================================================="
Write-Host "  控制台   : http://localhost:$Port/"
Write-Host "  健康检查 : http://localhost:$Port/api/health"
Write-Host "  ChromaDB : http://localhost:$ChromaPort"
Write-Host "  进程 PID : $($process.Id)"
Write-Host ""
Write-Host "  停止服务 : powershell -ExecutionPolicy Bypass -File stop.ps1"
Write-Host "=========================================================="
