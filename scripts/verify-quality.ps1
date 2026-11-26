<#
.SYNOPSIS
    检索质量门禁验收脚本：跑一次端到端评测，打印召回率 / P95 / 门禁判定，并按判定返回退出码。

.DESCRIPTION
    退出码约定：
      0 —— 召回率与 P95 均达标（Recall@10 >= 0.60 且 P95 <= 100ms）
      1 —— 门禁未达标
      2 —— 脚本自身执行失败（Chroma 起不来 / Maven 执行失败 / 测试未运行 /
           有凭据但真实嵌入段报告缺失或无法解析）
      3 —— 自检失败（仅 -SelfTest 模式）

    执行链路：
      1. 确认本地 Chroma 服务在跑（不在则用 scripts/chroma.ps1 start 拉起）；
      2. 用 Maven 跑 QualityEvaluationIT —— 该 IT 把评测语料摄取进本地 Chroma，
         跑完整评测并打印召回率 / P95 / 门禁判定；
      3. 从 Maven 输出中**按段落标记**解析召回率与 P95，按门禁常量判定，返回退出码。

    关于 REST 接口：
      本工程的 REST 层（Task 9）尚未提供质量评测接口，因此当前脚本驱动的是
      **集成测试**形态的评测。Task 9 落地后，本脚本将增加一条 REST 路径
      （调用后端的评测接口取同一份报告），届时脚本的判定逻辑与门禁常量保持不变。

    关于凭据与「桩段 / 真实段」：
      IT 会打印两段可能出现的报告，各自带一行段落标记：
        == STUB EMBEDDING REPORT ==   确定性桩嵌入，衡量词面重叠，**不是**语义召回
        == REAL EMBEDDING REPORT ==   真实嵌入模型，才是验收依据
      解析规则（不可协商）：
        * 输出里存在真实段标记时，**只**采信真实段那份报告；
        * 环境变量 DASHSCOPE_API_KEY 存在但真实段标记缺失或真实段无法解析时，
          脚本**失败退出（2）**，绝不回退到桩段的数字；
        * 只有在无凭据（因而真实段本就不会跑）时，才采信桩段，并在输出里
          醒目标注该数字不是语义召回的验收结论。
      任何情况下脚本都不会把凭据写入文件或打印。

    自检：
      -SelfTest 开关不连 Chroma、不跑 Maven，只用构造的「桩段 + 真实段」拼接输出
      驱动同一套解析函数，断言取到的是真实段的数值。脚本因此**可被验证**，
      而不是只能靠人眼读输出。
#>

[CmdletBinding()]
param(
    # 本地 Maven 仓库路径。必须带引号传给 mvn，否则 PowerShell 会把
    # -Dmaven.repo.local 拆坏（报 "No plugin found for prefix '.repo.local=d'"）。
    [string]$MavenRepo = 'd:\tools\m2repo',

    # Chroma 服务基址，与 application.yml 的默认值一致。
    [string]$ChromaBaseUrl = 'http://localhost:8765',

    # Chroma 监听端口，用于确认服务是否在跑。
    [int]$ChromaPort = 8765,

    # 只跑脚本自身的解析逻辑自检：喂入构造的「桩段 + 真实段」拼接输出，
    # 断言脚本采信的是真实段的数值。不连 Chroma、不跑 Maven。
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# 解析工程根目录（脚本位于 <root>/scripts/ 下），保证在任意工作目录下调用行为一致。
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# 质量门禁常量：与 QualityGate 保持一致。
$MinRecall = 0.60
$MaxP95Ms = 100.0

# 评测集成测试的类名。
$IntegrationTestClass = 'QualityEvaluationIT'

# IT 打印的段落标记行，必须与 QualityEvaluationIT.REAL_REPORT_MARKER / STUB_REPORT_MARKER 一致。
$RealReportMarker = '== REAL EMBEDDING REPORT =='
$StubReportMarker = '== STUB EMBEDDING REPORT =='

function Test-ChromaReachable {
    param([string]$BaseUrl)

    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl/api/v2/version" -TimeoutSec 5
        return $null -ne $response
    } catch {
        return $false
    }
}

function Start-ChromaIfNeeded {
    param([string]$BaseUrl, [int]$ListenPort)

    if (Test-ChromaReachable -BaseUrl $BaseUrl) {
        Write-Host "==> Chroma 服务已在 $BaseUrl 运行。"
        return
    }

    Write-Host "==> Chroma 服务未就绪，尝试通过 scripts/chroma.ps1 start 启动。"
    $chromaScript = Join-Path $PSScriptRoot 'chroma.ps1'
    & powershell -ExecutionPolicy Bypass -File $chromaScript start -Port $ListenPort
    if (-not (Test-ChromaReachable -BaseUrl $BaseUrl)) {
        throw "Chroma 服务在 $BaseUrl 不可达，无法执行质量评测。"
    }
}

function Invoke-QualityEvaluation {
    param([string]$RepoPath)

    # 评测集成测试需要 CHROMA_IT=true 才会运行（不设时会被 JUnit 跳过，
    # 那会让脚本"通过"却什么都没验证 —— 必须显式打开）。
    $env:CHROMA_IT = 'true'

    $arguments = @('-q', "-Dmaven.repo.local=$RepoPath")
    # 分两步追加：写成 `@('-Dtest=' + $x, 'test')` 时，PowerShell 会把
    # 逗号当作数组构造的一部分，导致两个元素被合并成一个（实测表现为
    # Maven 收到 "-Dtest=QualityEvaluationIT test" 而报 "No goals have been specified"）。
    $arguments += "-Dtest=$IntegrationTestClass"
    $arguments += 'test'

    Push-Location $ProjectRoot
    try {
        # 先把输出整体捕获到变量，再回显到控制台。
        $captured = & mvn @arguments 2>&1
        $exitCode = $LASTEXITCODE
        $captured | ForEach-Object { Write-Host $_ }
        return [pscustomobject]@{
            ExitCode = $exitCode
            Output   = ($captured | Out-String)
        }
    } finally {
        Pop-Location
    }
}

function Get-MetricValue {
    param(
        [string]$Text,
        [string]$Pattern
    )

    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) {
        return $null
    }
    return [double]$match.Groups[1].Value
}

<#
.SYNOPSIS
    从集成测试输出中选出**应当采信**的那一段报告。

.DESCRIPTION
    段落标记由 QualityEvaluationIT 打印：
      == REAL EMBEDDING REPORT ==   真实嵌入段，验收依据
      == STUB EMBEDDING REPORT ==   桩嵌入段，只衡量词面重叠

    选取规则：
      * 存在真实段标记 —— 取真实段标记**之后**的内容（到下一个标记或文本末尾）；
      * 不存在真实段标记 —— 取桩段标记之后的内容；若连桩段标记也没有，
        退回整段文本（兼容旧格式），并在结果里把来源标记为 Unknown。

    返回对象的 Source 取值：Real / Stub / Unknown。
#>
function Select-QualitySection {
    param([string]$Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return [pscustomobject]@{ Source = 'Unknown'; Body = '' }
    }

    $realIndex = $Text.IndexOf($RealReportMarker)
    if ($realIndex -ge 0) {
        $start = $realIndex + $RealReportMarker.Length
        # 真实段一直延伸到下一个标记为止 —— 桩段若排在真实段之后（执行顺序未固定），
        # 不能把桩段的数字混进来。
        $stubIndex = $Text.IndexOf($StubReportMarker, $start)
        if ($stubIndex -lt 0) {
            $stubIndex = $Text.Length
        }
        return [pscustomobject]@{
            Source = 'Real'
            Body   = $Text.Substring($start, $stubIndex - $start)
        }
    }

    $stubIndex = $Text.IndexOf($StubReportMarker)
    if ($stubIndex -ge 0) {
        $start = $stubIndex + $StubReportMarker.Length
        return [pscustomobject]@{
            Source = 'Stub'
            Body   = $Text.Substring($start)
        }
    }

    return [pscustomobject]@{ Source = 'Unknown'; Body = $Text }
}

<#
.SYNOPSIS
    脚本解析逻辑的自检：喂入构造的「桩段 + 真实段」拼接输出，断言采信的是真实段。

.DESCRIPTION
    这是对脚本自身的验证，不是对系统的验证。它保证：
      1. 拼接输出里即使桩段排在前面，取到的也必须是真实段的数字；
      2. 只有桩段时，取到的是桩段的数字；
      3. 真实段缺失且有凭据时，脚本判定为失败而不是回退。
#>
function Invoke-SelfTest {
    $failures = New-Object System.Collections.Generic.List[string]

    # 用例 1：桩段在前、真实段在后（JUnit 顺序未固定，两种排列都可能出现）。
    $stubThenReal = @"
$StubReportMarker
========== 检索质量评测报告 ==========
查询数        : 46
命中/应命中   : 52 / 58
Recall@10     : 0.8966 (门禁线 0.60 -> 通过)
中位延迟(ms)  : 15.97
P95 延迟(ms)  : 31.86 (门禁线 100 -> 通过)
==========================================
$RealReportMarker
========== 检索质量评测报告 ==========
查询数        : 46
命中/应命中   : 59 / 58
Recall@10     : 0.9810 (门禁线 0.60 -> 通过)
中位延迟(ms)  : 42.10
P95 延迟(ms)  : 77.20 (门禁线 100 -> 通过)
==========================================
"@

    $section = Select-QualitySection -Text $stubThenReal
    if ($section.Source -ne 'Real') {
        $failures.Add("用例1：期望来源为 Real，实际为 $($section.Source)")
    }
    $recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
    $p95 = Get-MetricValue -Text $section.Body -Pattern 'P95 延迟\(ms\)\s*:\s*([0-9.]+)'
    if ($null -eq $recall -or [math]::Abs($recall - 0.9810) -gt 0.0001) {
        $failures.Add("用例1：期望取到真实段 Recall@10 = 0.9810，实际为 $recall")
    }
    if ($null -eq $p95 -or [math]::Abs($p95 - 77.20) -gt 0.0001) {
        $failures.Add("用例1：期望取到真实段 P95 = 77.20，实际为 $p95")
    }

    # 用例 2：真实段在前、桩段在后 —— 真实段的数字仍必须完整取到。
    $realThenStub = @"
$RealReportMarker
Recall@10     : 0.9810 (门禁线 0.60 -> 通过)
P95 延迟(ms)  : 77.20 (门禁线 100 -> 通过)
$StubReportMarker
Recall@10     : 0.8966 (门禁线 0.60 -> 通过)
P95 延迟(ms)  : 31.86 (门禁线 100 -> 通过)
"@
    $section = Select-QualitySection -Text $realThenStub
    $recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
    if ($section.Source -ne 'Real') {
        $failures.Add("用例2：期望来源为 Real，实际为 $($section.Source)")
    }
    if ($null -eq $recall -or [math]::Abs($recall - 0.9810) -gt 0.0001) {
        $failures.Add("用例2：期望取到真实段 Recall@10 = 0.9810，实际为 $recall")
    }

    # 用例 3：只有桩段时，取到的是桩段的数字，来源标记为 Stub。
    $stubOnly = @"
$StubReportMarker
Recall@10     : 0.8966 (门禁线 0.60 -> 通过)
P95 延迟(ms)  : 31.86 (门禁线 100 -> 通过)
"@
    $section = Select-QualitySection -Text $stubOnly
    if ($section.Source -ne 'Stub') {
        $failures.Add("用例3：期望来源为 Stub，实际为 $($section.Source)")
    }
    $recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
    if ($null -eq $recall -or [math]::Abs($recall - 0.8966) -gt 0.0001) {
        $failures.Add("用例3：期望取到桩段 Recall@10 = 0.8966，实际为 $recall")
    }

    # 用例 4：真实段标记存在但数值不可解析 —— 必须解析失败（由调用方决定退出码 2），
    #         不能悄悄退回桩段的数字。
    $brokenReal = @"
$StubReportMarker
Recall@10     : 0.8966 (门禁线 0.60 -> 通过)
$RealReportMarker
报告被截断，没有 Recall@10 字段
"@
    $section = Select-QualitySection -Text $brokenReal
    if ($section.Source -ne 'Real') {
        $failures.Add("用例4：期望来源为 Real，实际为 $($section.Source)")
    }
    $recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
    if ($null -ne $recall) {
        $failures.Add("用例4：期望真实段解析失败（得到 null），实际为 $recall")
    }

    if ($failures.Count -gt 0) {
        Write-Host "[FAIL] 脚本解析逻辑自检未通过："
        foreach ($failure in $failures) {
            Write-Host "       - $failure"
        }
        return $false
    }

    Write-Host "[PASS] 脚本解析逻辑自检通过（4 个用例）："
    Write-Host "       - 拼接输出中桩段在前时，取到的是真实段数值"
    Write-Host "       - 真实段在前时，取到的仍是真实段数值"
    Write-Host "       - 只有桩段时，取到的是桩段数值且来源标记为 Stub"
    Write-Host "       - 真实段数值不可解析时解析失败，不回退到桩段"
    return $true
}

# ---------------------------------------------------------------- 自检模式 ----

if ($SelfTest) {
    Write-Host "=========================================================="
    Write-Host " 质量门禁脚本解析逻辑自检"
    Write-Host "=========================================================="
    if (Invoke-SelfTest) {
        exit 0
    }
    exit 3
}

# ---------------------------------------------------------------- 主流程 ----

Write-Host "=========================================================="
Write-Host " 检索质量门禁验收"
Write-Host "=========================================================="

Start-ChromaIfNeeded -BaseUrl $ChromaBaseUrl -ListenPort $ChromaPort

$result = Invoke-QualityEvaluation -RepoPath $MavenRepo

if ($result.ExitCode -ne 0) {
    Write-Host ""
    Write-Host "[FAIL] 评测集成测试执行失败（Maven 退出码 $($result.ExitCode)）。"
    Write-Host "       详见上方输出。"
    exit 2
}

# 凭据是否存在决定"采信真实段"是否为硬要求。
$hasCredential = -not [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)

# 从 IT 的打印中解析指标。先按段落标记选出应当采信的那一段，
# 再从**该段内部**解析召回率与 P95 —— 绝不跨段取值。
$section = Select-QualitySection -Text $result.Output

# 有凭据时真实段必须存在且可解析：缺失即失败，不回退到桩段。
if ($hasCredential -and $section.Source -ne 'Real') {
    Write-Host ""
    Write-Host "[FAIL] 检测到 DASHSCOPE_API_KEY，但评测输出中找不到真实嵌入段报告"
    Write-Host "       （缺少标记行 '$RealReportMarker'）。"
    Write-Host "       本次运行不接受桩嵌入段的数字作为验收结论 —— 脚本失败退出。"
    exit 2
}

# 从该段内部解析指标。格式由 QualityEvaluationIT.printReport 固定。
$recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
$p95 = Get-MetricValue -Text $section.Body -Pattern 'P95 延迟\(ms\)\s*:\s*([0-9.]+)'

if ($null -eq $recall -or $null -eq $p95) {
    Write-Host ""
    Write-Host "[FAIL] 未能从评测输出中解析出召回率 / P95，无法判定门禁。"
    Write-Host "       采信的段落来源: $($section.Source)"
    if ($hasCredential) {
        Write-Host "       （本次有 DASHSCOPE_API_KEY，真实嵌入段必须存在且可解析 —— 脚本失败退出。）"
    }
    Write-Host "       （若集成测试被跳过，说明 CHROMA_IT 未生效或 Chroma 不可达。）"
    exit 2
}

Write-Host ""
Write-Host "----------------------------------------------------------"
Write-Host ("Recall@10 : {0:F4}   (门禁线 {1:F2})" -f $recall, $MinRecall)
Write-Host ("P95 延迟  : {0:F2} ms (门禁线 {1:F0} ms)" -f $p95, $MaxP95Ms)
Write-Host "----------------------------------------------------------"

# 明确标注本次采信的是哪一段的报告 —— 结论的来源必须一眼可见。
if ($section.Source -eq 'Real') {
    Write-Host "[来源] 本次采信的是**真实嵌入段**报告（标记 '$RealReportMarker'）。"
    Write-Host "       该召回率是真实语义召回的验收依据。"
} else {
    Write-Host "[来源] 本次采信的是**桩嵌入段**报告（标记 '$StubReportMarker'）。"
    Write-Host ""
    Write-Host "[注意] 上述召回率来自**确定性桩嵌入**，衡量的是词面重叠而非语义相似度，"
    Write-Host "       **不是**真实语义召回的验收结论，不能用作验收依据。"
    if (-not $hasCredential) {
        Write-Host "       原因：本机未检测到 DASHSCOPE_API_KEY 环境变量，真实嵌入段未运行。"
        Write-Host "       真实召回率请在有凭据的环境重跑本脚本。"
    } else {
        Write-Host "       原因：本次未采信到真实嵌入段报告。"
    }
}

$recallOk = $recall -ge $MinRecall
$p95Ok = $p95 -le $MaxP95Ms

if ($recallOk -and $p95Ok) {
    Write-Host ""
    Write-Host "[PASS] 质量门禁通过：召回率与 P95 均达标。"
    if ($section.Source -ne 'Real') {
        Write-Host "       注意：本次通过的是**桩嵌入段**，仅表示评测装置工作正常，"
        Write-Host "             不构成真实语义召回的验收结论。"
    }
    exit 0
}

Write-Host ""
if (-not $recallOk) {
    Write-Host ("[FAIL] 召回率 {0:F4} 低于门禁线 {1:F2}。" -f $recall, $MinRecall)
    Write-Host "       调参杠杆依次为：切片长度/重叠 -> topK -> 相似度下限 -> 嵌入模型。"
}
if (-not $p95Ok) {
    Write-Host ("[FAIL] P95 延迟 {0:F2}ms 超过门禁线 {1:F0}ms。" -f $p95, $MaxP95Ms)
    Write-Host "       延迟主要来自嵌入调用，考虑启用查询向量缓存（TTL 60s）。"
}
exit 1
