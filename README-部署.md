# 部署说明

AgentScope 四层记忆 + ChromaDB 知识库平台的发布包。

---

## 1. 发布包内容

```
agent-memory-kb-0.0.1-SNAPSHOT/
├── agent-memory-kb.jar        # 可执行 jar（已含前端界面，单 jar 即可运行）
├── start.ps1                  # 一键启动（ChromaDB + 后端）
├── stop.ps1                   # 一键停止
├── scripts/                   # ChromaDB 安装与启停、端到端验收脚本
├── eval/                      # 评测语料（可直接上传体验）
├── src/                       # 后端源码（可二次开发）
├── frontend/                  # 前端源码（需自行 npm ci）
├── pom.xml                    # Maven 工程定义
├── README.md                  # 开发视角的说明
└── data/                      # 运行期数据（Chroma 持久化 / 记忆文件 / 日志）
```

**不需要**单独部署前端：`frontend` 的构建产物已打进 jar，
`java -jar` 起来后直接访问根路径就是控制台。

---

## 2. 前置依赖

| 组件 | 版本 | 用途 | 必需 |
|---|---|---|---|
| JDK | 21+ | 运行后端 | ✅ |
| Python | 3.11+ | 运行 ChromaDB | ✅ |
| chromadb | 1.5.9 | 向量库服务 | ✅（脚本可自动安装） |
| Node.js | 22+ | **仅**二次构建前端时需要 | ❌ |

> Node.js 不用装 —— jar 里的界面已经构建好了。
> 只有改了前端源码才需要 `npm ci && npm run build`。

---

## 3. 部署步骤

### 3.1 解压

```powershell
Expand-Archive -Path agent-memory-kb-0.0.1-SNAPSHOT.zip -DestinationPath D:\deploy
cd D:\deploy\agent-memory-kb-0.0.1-SNAPSHOT
```

### 3.2 安装 ChromaDB（一次性）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-chroma.ps1
```

该脚本安装 `chromadb==1.5.9` 与 `msvc_runtime`（Rust bindings 运行时，
缺失时 `import chromadb` 直接失败），装完会执行 `python -c "import chromadb"` 校验。

### 3.3 启动

```powershell
powershell -ExecutionPolicy Bypass -File start.ps1 -ApiKey "sk-你的密钥"
```

脚本会依次：校验 JDK 版本 → 拉起 ChromaDB → 以 jar 方式启动后端 →
**轮询 `/api/health` 直到就绪** → 打印访问地址。

也可以用环境变量：

```powershell
$env:DASHSCOPE_API_KEY = "sk-你的密钥"
powershell -ExecutionPolicy Bypass -File start.ps1
```

> **不提供密钥也能启动**，健康检查会显示 `model: DOWN`，
> 上传与检索会明确报错提示「向量模型服务暂时不可用」，
> 其余接口（文档清单、四层记忆查看、健康检查）正常工作。

### 3.4 访问

| 地址 | 内容 |
|---|---|
| http://localhost:8088/ | 知识库控制台 |
| http://localhost:8088/api/health | 健康检查 |

### 3.5 停止

```powershell
powershell -ExecutionPolicy Bypass -File stop.ps1
```

停止逻辑带**进程归属校验**：只终止命令行确实引用本部署 jar 的进程，
PID 被系统回收复用时会拒绝误杀。ChromaDB 的停止复用了
`scripts/chroma.ps1` 的同一套校验实现。

---

## 4. 配置项

全部通过环境变量覆盖，无需改配置文件：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 空 | 模型服务凭据（对话 + 嵌入） |
| `CHROMA_BASE_URL` | `http://localhost:8765` | ChromaDB 基址 |
| `LLM_BASE_URL` | 百炼兼容模式网关 | 模型服务基址 |

`start.ps1` 已按 `-Port` / `-ChromaPort` 同步改写后端启动参数，
一般不需要手工设置环境变量。

---

## 5. 验证部署

启动后可在控制台手工验证，或跑端到端验收脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-e2e.ps1
```

该脚本会自行拉起 ChromaDB、跑桩嵌入段与真实模型段、
校验质量门禁并打印结论。

> 脚本会读取 `scripts\.local-credentials.ps1`（发布包中不含该文件），
> 也可直接用 `DASHSCOPE_API_KEY` 环境变量。

---

## 6. 目录与数据

| 路径 | 内容 | 可否删除 |
|---|---|---|
| `data/chroma/` | ChromaDB 持久化（向量、文档、长期记忆） | ❌ 删除即清空知识库 |
| `data/memory/` | 工作记忆与情景记忆的 JSON 文件 | ⚠️ 删除即清空这两层记忆 |
| `data/app.log` / `app.err.log` | 后端运行日志 | ✅ 可删 |
| `data/app.pid` | 后端进程号 | ✅ 可删（仅 stop.ps1 使用） |

**备份**只需备份 `data/` 目录 —— jar 与源码都可以重新构建。

---

## 7. 常见问题

### 端口被占用

```powershell
powershell -ExecutionPolicy Bypass -File start.ps1 -Port 8089 -ChromaPort 8766
```

或先用 `stop.ps1` 停掉旧实例。

### 启动脚本报「JDK 版本不足」

需要 JDK 21+。检查 `java -version`，
若装了多个 JDK，用 `JAVA_HOME` 指向 21+ 的版本。

### ChromaDB 起不来

先单独诊断：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\chroma.ps1 status
Get-Content data\chroma\chroma.stderr.log -Tail 50
```

常见原因是 `msvc_runtime` 没装 —— 重跑 `scripts\setup-chroma.ps1`。

### 控制台打不开 / 404

确认访问的是 `http://localhost:8088/` 而不是 `/index.html`，
且 `agent-memory-kb.jar` 未损坏（重新解压即可）。

### 检索没有结果

先看健康检查的 `model` 字段：

```powershell
Invoke-RestMethod http://localhost:8088/api/health
```

`model: DOWN` 说明缺 `DASHSCOPE_API_KEY` —— 上传与检索都需要嵌入模型。

---

## 8. 已知限制

- **不做鉴权**：作用域由请求头 `X-Tenant-Id` / `X-Owner-Key` 声明，
  调用方可自行声明任意租户。上生产前必须在网关层接入认证。
- **检索延迟**：端到端 P95 含远程嵌入模型往返（实测 125–190 ms），
  该段由外部模型服务决定，本工程无法优化；
  向量库检索本身为 9–16 ms。
- **文档清单靠全量扫描聚合**：文档数很大时 `GET /api/documents` 会变慢。
