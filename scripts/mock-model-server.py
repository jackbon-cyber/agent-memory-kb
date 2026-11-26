"""
本地桩模型服务 —— 仅用于浏览器端的人工验收。

用途：
    浏览器人工走查时，真实模型凭据往往不可用。此时用本服务顶替百炼网关，
    让「上传 → 检索 → 问答」这条链路在界面上跑通。

它提供两个 OpenAI 兼容端点：
    POST /v1/embeddings   —— 返回确定性哈希向量
    POST /v1/chat/completions —— 返回一段固定的回答

**它不是评测装置**：
    桩嵌入衡量的是词面重叠，不是语义相似度。
    质量门禁的结论必须由 scripts/verify-e2e.ps1 在有真实凭据时给出。

用法：
    python scripts/mock-model-server.py --port 8099
"""

import argparse
import hashlib
import json
import math
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

# 桩嵌入的向量维度，必须与 memorykb.embedding.dimensions 一致。
STUB_DIMENSIONS = 1024

# 请求体里可能出现的文本字段，按顺序尝试取值。
TEXT_FIELDS = ("input", "text", "content")


def deterministic_vector(text):
    """把文本哈希成确定性单位向量。

    与 Java 侧的桩嵌入算法不同即可 —— 这里只要求「同文本同向量、异文本异向量」。
    """
    vector = [0.0] * STUB_DIMENSIONS
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    for index in range(STUB_DIMENSIONS):
        vector[index] = digest[index % len(digest)] / 255.0
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0:
        vector[0] = 1.0
        return vector
    return [value / norm for value in vector]


def collect_texts(payload):
    """从请求体里收集要向量化的文本。"""
    for field in TEXT_FIELDS:
        value = payload.get(field)
        if isinstance(value, str):
            return [value]
        if isinstance(value, list):
            texts = []
            for item in value:
                if isinstance(item, str):
                    texts.append(item)
                elif isinstance(item, dict):
                    # 多模态入参：取其中的 text 字段。
                    texts.append(str(item.get("text", "")))
            if texts:
                return texts
    return []


class Handler(BaseHTTPRequestHandler):
    """桩模型服务的请求处理器。"""

    def log_message(self, format, *args):
        # 走标准错误，避免污染 stdout 的 JSON 输出。
        print("[mock-model] " + format % args)

    def do_POST(self):
        """按路径分发到嵌入或对话端点。"""
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            payload = {}

        if self.path.endswith("/embeddings"):
            self.respond(self.embeddings(payload))
        elif self.path.endswith("/chat/completions"):
            self.respond(self.chat(payload))
        else:
            self.respond({"error": {"message": "unsupported path: " + self.path}}, status=404)

    def embeddings(self, payload):
        """返回确定性向量。"""
        texts = collect_texts(payload)
        data = [
            {"object": "embedding", "index": index, "embedding": deterministic_vector(text)}
            for index, text in enumerate(texts)
        ]
        return {"object": "list", "data": data, "model": payload.get("model", "stub")}

    def chat(self, payload):
        """返回一段固定回答。"""
        question = self.extract_question(payload)
        answer = (
            "[桩模型回答] 这是本地桩模型服务返回的固定回答，用于浏览器人工走查。\n"
            "问题：" + question + "\n"
            "注意：本回答不来自真实大模型，不能用于评估回答质量。"
        )
        return {
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "model": payload.get("model", "stub"),
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": answer},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    def extract_question(self, payload):
        """从对话请求里取出用户提问。"""
        messages = payload.get("messages") or []
        for message in reversed(messages):
            if message.get("role") == "user":
                content = message.get("content")
                if isinstance(content, str):
                    return content
                if isinstance(content, list):
                    parts = []
                    for item in content:
                        if isinstance(item, dict) and "text" in item:
                            parts.append(str(item["text"]))
                    if parts:
                        return " ".join(parts)
        return "(未取到问题)"

    def respond(self, payload, status=200):
        """把 JSON 响应写回客户端。"""
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    """启动桩模型服务。"""
    parser = argparse.ArgumentParser(description="本地桩模型服务（仅供浏览器人工走查）")
    parser.add_argument("--port", type=int, default=8099, help="监听端口，默认 8099")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认 127.0.0.1")
    args = parser.parse_args()

    server = HTTPServer((args.host, args.port), Handler)
    print("[mock-model] 已启动：http://%s:%d/v1" % (args.host, args.port))
    print("[mock-model] 提供的端点：/v1/embeddings、/v1/chat/completions")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[mock-model] 已停止")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
