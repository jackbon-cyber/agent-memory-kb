<#
.SYNOPSIS
    安装 ChromaDB 及其运行依赖。

.DESCRIPTION
    幂等脚本：可重复执行。
    1. 安装 chromadb==1.5.9 —— Chroma 服务端与 CLI 的 Python 实现。
    2. 安装 msvc_runtime —— Windows 下 chromadb 依赖的 Rust bindings 运行时，
       缺失时导入 chromadb 会直接失败，因此这一步是必需项，不可省略。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/setup-chroma.ps1
#>

$ErrorActionPreference = 'Stop'

$ChromaDbVersion = '1.5.9'

Write-Host "==> 安装 chromadb==$ChromaDbVersion"
python -m pip install "chromadb==$ChromaDbVersion"

# msvc_runtime 缺失会导致 chromadb 的 Rust bindings 加载失败，属于必需依赖。
Write-Host "==> 安装 msvc_runtime（chromadb Rust bindings 运行时，必需）"
python -m pip install msvc_runtime

Write-Host "==> 校验 chromadb 可导入"
python -c "import chromadb; print('chromadb', chromadb.__version__)"

Write-Host "==> 安装完成。Chroma CLI 位置："
$chromaCommand = Get-Command chroma -ErrorAction SilentlyContinue
if ($null -ne $chromaCommand) {
    Write-Host "    $($chromaCommand.Source)"
} else {
    Write-Host "    未在 PATH 中找到 chroma，请使用默认安装路径 D:\Programs\Python\Python313\Scripts\chroma.exe"
}

Write-Host "==> 下一步：powershell -ExecutionPolicy Bypass -File scripts/chroma.ps1 start"
