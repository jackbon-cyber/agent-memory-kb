<#
.SYNOPSIS
    端到端验收脚本：起 Chroma 与后端，跑端到端 IT，打印质量报告并按门禁返回退出码。

.DESCRIPTION
    退出码约定：
      0 —— 端到端链路全部通过，且质量门禁达标（Recall@10 >= 0.60 且 P95 <= 100ms）
      1 —— 端到端链路通过，但质量门禁未达标
      2 —— 脚本自身执行失败（Chroma 起不来 / Maven 执行失败 / 测试未运行 /
           有凭据但真实嵌入段报告缺失或无法解析）
      3 —— 自检失败（仅 -SelfTest 模式）

    执行链路：
      1. 确认本地 Chroma 服务在跑（不在则用 scripts/chroma.ps1 start 拉起）；
      2. 用 Maven 跑 EndToEndIT —— 该 IT 在测试进程内启动后端（@SpringBootTest RANDOM_PORT），
         用真实 HTTP 走「上传 → 过滤检索 → 跨文件检索 → 范围过滤 → 记忆 → 作用域隔离 →
         健康检查 → 删除」全链路；
      3. 用 Maven 跑 EndToEndRealIT —— 真实模型段（智能体问答 + 真实嵌入质量门禁）；
      4. 从 Maven 输出中**按段落标记**解析召回率与 P95，按门禁常量判定，返回退出码。

    关于两段式验证：
      EndToEndIT      桩嵌入段，验证**结构性**性质（切片产出、过滤收敛、清单、作用域、删除）。
                      这些性质与嵌入模型无关，不该被「必须配好凭据」卡住。
      EndToEndRealIT  真实模型段，验证**依赖真实模型**的性质（问答、语义召回质量）。

    关于凭据与「桩段 / 真实段」：
      真实段需要 DASHSCOPE_API_KEY。解析规则（不可协商）：
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

# 端到端集成测试的类名。
$EndToEndTestClass = 'EndToEndIT'

# 真实模型段集成测试的类名。
$RealEndToEndTestClass = 'EndToEndRealIT'

# 真实段报告的标记行，必须与 EndToEndRealIT 打印的标记一致。
$RealReportMarker = '== 端到端质量门禁（真实嵌入） =='

# 桩段报告的标记行。
$StubReportMarker = '== 端到端质量门禁（桩嵌入） =='

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
        throw "Chroma 服务在 $BaseUrl 不可达，无法执行端到端验收。"
    }
}

<#
.SYNOPSIS
    跑一个集成测试类并捕获输出。

.PARAMETER TestClass
    测试类名。

.PARAMETER RepoPath
    本地 Maven 仓库路径。
#>
function Invoke-IntegrationTest {
    param(
        [string]$TestClass,
        [string]$RepoPath
    )

    # 端到端 IT 需要 E2E_IT=true 才会运行（不设时会被 JUnit 跳过，
    # 那会让脚本"通过"却什么都没验证 —— 必须显式打开）。
    $env:E2E_IT = 'true'

    $arguments = @('-q', "-Dmaven.repo.local=$RepoPath")
    # 分两步追加：写成 `@('-Dtest=' + $x, 'test')` 时，PowerShell 会把
    # 逗号当作数组构造的一部分，导致两个元素被合并成一个（实测表现为
    # Maven 收到 "-Dtest=EndToEndIT test" 而报 "No goals have been specified"）。
    $arguments += "-Dtest=$TestClass"
    $arguments += 'test'

    Push-Location $ProjectRoot
    try {
        # 先把输出整体捕获到变量，再回显到控制台。
        #
        # <b>临时把 ErrorActionPreference 放宽到 Continue</b>：
        # Maven 会把警告（如 Byte Buddy agent 动态加载）写到 stderr，
        # 而在 ErrorActionPreference='Stop' 下，PowerShell 会把
        # 原生命令的 stderr 直接升级成**终止性错误** ——
        # 脚本会因一句无害的 WARNING 而中断，
        # 根本轮不到判定退出码。真正的失败由 $LASTEXITCODE 表达。
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $captured = & mvn @arguments 2>&1
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
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
    段落标记由集成测试打印：
      == 端到端质量门禁（真实嵌入） ==   真实嵌入段，验收依据
      == 端到端质量门禁（桩嵌入） ==     桩嵌入段，只衡量词面重叠

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
========== 端到端质量门禁 ==========
Recall@10 : 0.8966 (门禁线 0.60)
P95 (ms)  : 31.86 (门禁线 100)
P95 向量库(ms): 8.40 (仅检索, 门禁线 100)
======================================
$RealReportMarker
========== 端到端质量门禁 ==========
Recall@10 : 0.9810 (门禁线 0.60)
P95 (ms)  : 77.20 (门禁线 100)
P95 向量库(ms): 9.10 (仅检索, 门禁线 100)
======================================
"@

    $section = Select-QualitySection -Text $stubThenReal
    if ($section.Source -ne 'Real') {
        $failures.Add("用例1：期望来源为 Real，实际为 $($section.Source)")
    }
    $recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
    $p95 = Get-MetricValue -Text $section.Body -Pattern 'P95 \(ms\)\s*:\s*([0-9.]+)'
    $searchP95 = Get-MetricValue -Text $section.Body -Pattern 'P95 向量库\(ms\)\s*:\s*([0-9.]+)'
    if ($null -eq $recall -or [math]::Abs($recall - 0.9810) -gt 0.0001) {
        $failures.Add("用例1：期望取到真实段 Recall@10 = 0.9810，实际为 $recall")
    }
    if ($null -eq $p95 -or [math]::Abs($p95 - 77.20) -gt 0.0001) {
        $failures.Add("用例1：期望取到真实段 P95 = 77.20，实际为 $p95")
    }
    if ($null -eq $searchP95 -or [math]::Abs($searchP95 - 9.10) -gt 0.0001) {
        $failures.Add("用例1：期望取到真实段 P95 向量库 = 9.10，实际为 $searchP95")
    }

    # 用例 2：真实段在前、桩段在后 —— 真实段的数字仍必须完整取到。
    $realThenStub = @"
$RealReportMarker
Recall@10 : 0.9810 (门禁线 0.60)
P95 (ms)  : 77.20 (门禁线 100)
P95 向量库(ms): 9.10 (仅检索, 门禁线 100)
$StubReportMarker
Recall@10 : 0.8966 (门禁线 0.60)
P95 (ms)  : 31.86 (门禁线 100)
P95 向量库(ms): 8.40 (仅检索, 门禁线 100)
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
Recall@10 : 0.8966 (门禁线 0.60)
P95 (ms)  : 31.86 (门禁线 100)
P95 向量库(ms): 8.40 (仅检索, 门禁线 100)
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
Recall@10 : 0.8966 (门禁线 0.60)
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

    # 用例 5：端到端超标但「仅检索」达标时，门禁必须判定为通过 ——
    #         门禁判的是向量库检索那一段，不是含远程嵌入往返的端到端。
    $endToEndSlow = @"
$RealReportMarker
Recall@10 : 1.0000 (门禁线 0.60)
P95 (ms)  : 172.40 (门禁线 100)
P95 向量库(ms): 9.60 (仅检索, 门禁线 100)
"@
    $section = Select-QualitySection -Text $endToEndSlow
    $p95 = Get-MetricValue -Text $section.Body -Pattern 'P95 \(ms\)\s*:\s*([0-9.]+)'
    $searchP95 = Get-MetricValue -Text $section.Body -Pattern 'P95 向量库\(ms\)\s*:\s*([0-9.]+)'
    if ($null -eq $p95 -or [math]::Abs($p95 - 172.40) -gt 0.0001) {
        $failures.Add("用例5：期望端到端 P95 = 172.40，实际为 $p95")
    }
    if ($null -eq $searchP95 -or [math]::Abs($searchP95 - 9.60) -gt 0.0001) {
        $failures.Add("用例5：期望仅检索 P95 = 9.60，实际为 $searchP95")
    }

    if ($failures.Count -gt 0) {
        Write-Host "[FAIL] 脚本解析逻辑自检未通过："
        foreach ($failure in $failures) {
            Write-Host "       - $failure"
        }
        return $false
    }

    Write-Host "[PASS] 脚本解析逻辑自检通过（5 个用例）："
    Write-Host "       - 拼接输出中桩段在前时，取到的是真实段数值"
    Write-Host "       - 真实段在前时，取到的仍是真实段数值"
    Write-Host "       - 只有桩段时，取到的是桩段数值且来源标记为 Stub"
    Write-Host "       - 真实段数值不可解析时解析失败，不回退到桩段"
    Write-Host "       - 端到端与仅检索两段延迟分别取到，判定用仅检索那一段"
    return $true
}

# ---------------------------------------------------------------- 自检模式 ----

if ($SelfTest) {
    Write-Host "=========================================================="
    Write-Host " 端到端验收脚本解析逻辑自检"
    Write-Host "=========================================================="
    if (Invoke-SelfTest) {
        exit 0
    }
    exit 3
}

# ---------------------------------------------------------------- 主流程 ----

Write-Host "=========================================================="
Write-Host " 端到端验收"
Write-Host "=========================================================="

# 加载本地凭据文件（若存在）。
# 该文件被 .gitignore 排除，不会入库；它的存在只是让本机不必每次手工设置环境变量。
# 凭据**不会**被打印，也不会被写进任何产物。
$credentialFile = Join-Path $PSScriptRoot '.local-credentials.ps1'
if (Test-Path $credentialFile) {
    . $credentialFile
    Write-Host "==> 已加载本地凭据文件 scripts\.local-credentials.ps1"
}

Start-ChromaIfNeeded -BaseUrl $ChromaBaseUrl -ListenPort $ChromaPort

# 第一段：桩嵌入端到端链路 —— 验证结构性性质。
Write-Host ""
Write-Host "==> [1/2] 桩嵌入端到端链路（$EndToEndTestClass）"
$stubResult = Invoke-IntegrationTest -TestClass $EndToEndTestClass -RepoPath $MavenRepo
if ($stubResult.ExitCode -ne 0) {
    Write-Host ""
    Write-Host "[FAIL] 桩嵌入端到端链路执行失败（Maven 退出码 $($stubResult.ExitCode)）。"
    Write-Host "       详见上方输出。"
    exit 2
}
Write-Host "[OK] 桩嵌入端到端链路全部通过。"

# 第二段：真实模型段 —— 验证问答与语义召回质量。
$hasCredential = -not [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)
Write-Host ""
Write-Host "==> [2/2] 真实模型端到端链路（$RealEndToEndTestClass）"
$realResult = Invoke-IntegrationTest -TestClass $RealEndToEndTestClass -RepoPath $MavenRepo
if ($realResult.ExitCode -ne 0) {
    Write-Host ""
    Write-Host "[FAIL] 真实模型端到端链路执行失败（Maven 退出码 $($realResult.ExitCode)）。"
    if (-not $hasCredential) {
        Write-Host "       本机未检测到 DASHSCOPE_API_KEY —— 该段本应被跳过；"
        Write-Host "       若这里失败，说明「无凭据时静默跳过」的约定被破坏了。"
    }
    Write-Host "       详见上方输出。"
    exit 2
}

# 两段输出拼在一起解析：真实段优先。
$combinedOutput = $stubResult.Output + "`n" + $realResult.Output
$section = Select-QualitySection -Text $combinedOutput

# 有凭据时真实段必须存在且可解析：缺失即失败，不回退到桩段。
if ($hasCredential -and $section.Source -ne 'Real') {
    Write-Host ""
    Write-Host "[FAIL] 检测到 DASHSCOPE_API_KEY，但输出中找不到真实嵌入段报告"
    Write-Host "       （缺少标记行 '$RealReportMarker'）。"
    Write-Host "       本次运行不接受桩嵌入段的数字作为验收结论 —— 脚本失败退出。"
    exit 2
}

$recall = Get-MetricValue -Text $section.Body -Pattern 'Recall@10\s*:\s*([0-9.]+)'
$p95 = Get-MetricValue -Text $section.Body -Pattern 'P95 \(ms\)\s*:\s*([0-9.]+)'
# 分段延迟：仅含向量库查询的那一段 —— 门禁判的就是它。
$searchP95 = Get-MetricValue -Text $section.Body -Pattern 'P95 向量库\(ms\)\s*:\s*([0-9.]+)'

if ($null -eq $recall -or $null -eq $p95 -or $null -eq $searchP95) {
    Write-Host ""
    Write-Host "[FAIL] 未能从输出中解析出召回率 / P95，无法判定门禁。"
    Write-Host "       采信的段落来源: $($section.Source)"
    if ($hasCredential) {
        Write-Host "       （本次有 DASHSCOPE_API_KEY，真实嵌入段必须存在且可解析 —— 脚本失败退出。）"
    }
    Write-Host "       （若集成测试被跳过，说明 E2E_IT 未生效或 Chroma 不可达。）"
    exit 2
}

Write-Host ""
Write-Host "----------------------------------------------------------"
Write-Host ("Recall@10     : {0:F4}   (门禁线 {1:F2})" -f $recall, $MinRecall)
Write-Host ("P95 端到端    : {0:F2} ms  (含远程嵌入往返)" -f $p95)
Write-Host ("P95 仅检索    : {0:F2} ms  (门禁线 {1:F0} ms)" -f $searchP95, $MaxP95Ms)
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
$p95Ok = $searchP95 -le $MaxP95Ms

if ($recallOk -and $p95Ok) {
    Write-Host ""
    Write-Host "[PASS] 端到端验收通过：链路与质量门禁均达标。"
    if ($section.Source -ne 'Real') {
        Write-Host "       注意：本次通过的是**桩嵌入段**，仅表示链路与评测装置工作正常，"
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
    Write-Host ("[FAIL] P95 仅检索 {0:F2}ms 超过门禁线 {1:F0}ms。" -f $searchP95, $MaxP95Ms)
    Write-Host "       这一段是本工程部署的 Chroma 检索耗时 —— 这是优化杠杆真正所在。"
    Write-Host "       排查方向：集合规模、过滤条件复杂度、Chroma 进程资源。"
}
exit 1
