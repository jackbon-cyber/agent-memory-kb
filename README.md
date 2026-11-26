# agent-memory-kb

基于 **AgentScope 2.0 (Java)** 与 **ChromaDB** 的四层记忆 + 知识库平台。

- **记忆层**：短期 / 长期 / 工作 / 情景四层记忆，作用域隔离由实现内部强制附加。
- **知识库**：基于 ChromaDB 的向量检索，支持按元数据过滤（AND / OR 逻辑）。
- **前端**：Vue 3 + Vite + TypeScript，与仓库既有栈一致。

---

## 1. 前置依赖

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21 | `java -version` 需为 21 |
| Maven | 3.9.x | 需支持 `mvn spring-boot:run` |
| Node.js | 24 | 仅前端构建需要 |
| Python | 3.13 | ChromaDB 服务端运行环境 |
| chromadb | 1.5.9 | 由 `scripts/setup-chroma.ps1` 安装 |

> 本地工具链参考路径：JDK `d:\tools\jdk21\jdk-21`、Maven `d:\tools\maven\apache-maven-3.9.16`、
> Python `D:\Programs\Python\Python313`。
>
> 若默认 Maven 本地仓库为空，命令需追加 `-Dmaven.repo.local=d:\tools\m2repo`。

---

## 2. 环境变量

凭据**只从环境变量读取**，源码与配置文件中不存放真实 api key。

| 变量名 | 用途 | 默认值 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 模型服务凭据（对话 + 嵌入） | 空（不设置则嵌入/对话不可用） |
| `CHROMA_BASE_URL` | Chroma 服务基址 | `http://localhost:8765` |
| `LLM_BASE_URL` | 模型服务 OpenAI 兼容模式基址 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |

PowerShell 示例：

```powershell
$env:DASHSCOPE_API_KEY = "<your-key>"
$env:CHROMA_BASE_URL  = "http://localhost:8765"
```

---

## 3. 启动顺序

### 3.1 安装 ChromaDB（一次性，可重复执行）

```powershell
cd agent-memory-kb
powershell -ExecutionPolicy Bypass -File scripts/setup-chroma.ps1
```

该脚本安装 `chromadb==1.5.9` 与 `msvc_runtime`（Rust bindings 运行时，缺失时 `import chromadb` 直接失败），
安装完成后会执行 `python -c "import chromadb"` 校验。

### 3.2 启动 Chroma 服务

```powershell
powershell -ExecutionPolicy Bypass -File scripts/chroma.ps1 start
```

脚本会先检测目标端口是否已有 Chroma 在监听：**已在运行则直接报告并跳过拉起**（幂等）。
启动成功后可校验：

```powershell
Invoke-WebRequest http://localhost:8765/api/v2/version
```

其它子命令：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/chroma.ps1 status
powershell -ExecutionPolicy Bypass -File scripts/chroma.ps1 stop
```

### 3.3 启动后端

```powershell
mvn spring-boot:run
```

服务监听 `http://localhost:8088`。

### 3.4 启动前端

```powershell
cd frontend
npm install
npm run dev
```

---

## 4. 关键约定

- **Chroma v2 REST 基址**：`/api/v2/tenants/{tenant}/databases/{database}/collections/{collection_id}`，
  tenant 固定 `default_tenant`，database 固定 `default_database`。**不得使用 `/api/v1/*`**（v1 已 410 Gone）。
- **集合命名规则**：长度 3–512，字符集 `[a-zA-Z0-9._-]`，首尾须为字母数字。
- **默认集合**：知识库 `kb_main`，长期记忆 `mem_longterm`。
- **嵌入模型**：`qwen3.7-text-embedding`，维度 `1024`，批量大小 `32`。
- **对话模型**：`deepseek-v4.1-flash-1017`（使用裸模型名，不带厂商私有前缀，带前缀的专有网关会返回"产品未激活"）。
- **切片默认参数**：单片上限 `600` 字符，重叠 `90` 字符，分隔符优先级 段落 → 句子 → 字符。
- **查询向量缓存 TTL**：`60` 秒。

以上默认值同时存在于 `application.yml` 与 Java 配置类字段初始化中，由
`ConfigurationDefaultsTest` 钉住一致性。

---

## 5. 构建与测试

```powershell
mvn test
mvn -Dtest=ConfigurationDefaultsTest test
```

本地仓库非默认位置时：

```powershell
mvn "-Dmaven.repo.local=d:\tools\m2repo" test
```

---

## 6. 目录结构

```
agent-memory-kb/
├── pom.xml                     # Maven 工程（Spring Boot 3.3.5 + AgentScope BOM）
├── data/chroma/                # Chroma 持久化目录（已 gitignore）
├── eval/                       # 评测集与语料
├── scripts/                    # Chroma 安装 / 启停 / 质量门禁脚本
├── src/main/java/com/company/bj/agent/memorykb/
│   ├── config/                 # ChromaProperties / EmbeddingProperties / MemoryProperties
│   ├── chroma/                 # Chroma REST 客户端与存储适配
│   ├── knowledge/              # 解析 / 切片 / 嵌入 / 检索
│   ├── memory/                 # 四层记忆
│   ├── agent/                  # Agent 装配
│   └── web/                    # REST 接口
├── src/main/resources/application.yml
└── frontend/                   # Vue 3 + Vite + TypeScript
```
