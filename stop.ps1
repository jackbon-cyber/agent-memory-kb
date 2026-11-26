<#
.SYNOPSIS
    停止 AgentScope 记忆知识库（后端 jar + ChromaDB）。

.DESCRIPTION
    停止后端 jar 进程与 ChromaDB 进程。

    <b>归属校验</b>：只终止经身份校验确认属于本部署的进程 ——
    后端校验命令行含本 jar 的绝对路径；Chroma 复用 scripts\chroma.ps1 的校验逻辑。
    PID 被系统回收复用是现实风险，误杀无关进程比"没停干净"严重得多。

.PARAMETER KeepChroma
    只停后端，保留 ChromaDB 运行。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File stop.ps1
#>

[CmdletBinding()]
param(
    [switch]$KeepChroma
)

$ErrorActionPreference = 'Stop'

# 解析工程根目录：与 start.ps1 用同一套探测逻辑 ——
# 同一个 jar 能被找到，两个脚本就必须指向同一个根目录。
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
$PidPath = Join-Path $AppRoot 'data\app.pid'

# ---------------------------------------------------------------- 停止后端 ----

function Get-VerifiedAppProcessId {
    param([string]$PidFile, [string]$ExpectedJarPath)

    if (-not (Test-Path $PidFile)) {
        return $null
    }

    $pidContent = (Get-Content -Path $PidFile -Raw -ErrorAction SilentlyContinue).Trim()
    if ([string]::IsNullOrWhiteSpace($pidContent)) {
        return $null
    }

    $parsedProcessId = 0
    if (-not [int]::TryParse($pidContent, [ref]$parsedProcessId)) {
        Write-Warning "PID 文件内容不是合法进程号（'$pidContent'），已忽略。"
        return $null
    }

    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$parsedProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return $null
    }

    # 归属校验：命令行必须引用本部署的 jar。
    # 少了这一条，PID 被系统复用后会误杀一个毫不相干的进程。
    $commandLine = $process.CommandLine
    if ([string]::IsNullOrWhiteSpace($commandLine)) {
        Write-Warning "PID $parsedProcessId 的命令行不可读，拒绝终止。"
        return $null
    }

    $expectedJar = $ExpectedJarPath.ToLowerInvariant().Replace('/', '\')
    if (-not $commandLine.ToLowerInvariant().Contains($expectedJar)) {
        Write-Warning "PID $parsedProcessId 不是本部署的后端进程（PID 可能已被系统复用），拒绝终止。"
        Write-Warning "    实际命令行：$commandLine"
        return $null
    }

    return $parsedProcessId
}

$appProcessId = Get-VerifiedAppProcessId -PidFile $PidPath -ExpectedJarPath $JarPath

if ($null -eq $appProcessId) {
    Write-Host "后端未在运行（无有效的 PID 文件或进程已退出）。"
} else {
    Write-Host "==> 停止后端进程 PID $appProcessId"
    Stop-Process -Id $appProcessId -Force -ErrorAction SilentlyContinue

    # 确认端口已释放：未释放时后续 start 会因端口占用而失败。
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        $listener = Get-NetTCPConnection -LocalPort 8088 -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -eq $listener) {
            break
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "后端已停止。"
}

if (Test-Path $PidPath) {
    Remove-Item -Path $PidPath -Force
}

# ---------------------------------------------------------------- 停止 Chroma ----

if ($KeepChroma) {
    Write-Host "已按 -KeepChroma 保留 ChromaDB 运行。"
} else {
    $chromaScript = Join-Path $AppRoot 'scripts\chroma.ps1'
    if (Test-Path $chromaScript) {
        & $chromaScript -Action stop
    } else {
        Write-Warning "未找到 scripts\chroma.ps1，跳过 ChromaDB 停止。"
    }
}
