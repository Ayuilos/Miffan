#!/usr/bin/env python3
"""A fake OpenAI-compatible model for Miffan's streaming benchmark. Python 3 standard library only.

Serves
  GET  /v1/models
  POST /v1/chat/completions   (stream=true: ~1500 tokens of Markdown at ~40 tokens/s; stream=false: a short reply)

on 127.0.0.1:18799. The perf fixture (docs/development/PERF_FIXTURE.md) configures the app with this address.
The emulator reaches the Mac's loopback through its global HTTP proxy; a phone needs `adb reverse tcp:18799 tcp:18799`.

Every streamed answer is the same text, so runs are comparable:
  python3 scripts/perf/fake_llm.py [--port 18799] [--tokens 1500] [--rate 40] [--reasoning 0]
"""
import argparse
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_ID = "fake-llm"

BLOCKS = [
    "# 下面是我的整理\n\n先说结论：**可以做，而且不复杂**。我把思路、步骤和注意事项分开写，方便你对照着看。\n\n",
    "## 一、思路\n\n核心就是三件事：先把范围*收窄*，再把重复的部分合并，最后留出检查的时间。这样做的好处是每一步都能单独验证，出了问题也容易定位。\n\n",
    "1. 先列出所有要处理的条目，不要急着分类\n2. 按「影响大、改动小」的顺序排优先级\n3. 每完成一项就打勾，别攒到最后\n4. 预留 `20%` 的时间处理意外\n\n",
    "## 二、对比\n\n| 方案 | 耗时 | 风险 | 适合场景 |\n| --- | --- | --- | --- |\n| 直接改 | 1 小时 | 中 | 改动很小 |\n| 先加测试再改 | 3 小时 | 低 | 逻辑复杂 |\n| 重写 | 2 天 | 高 | 历史包袱太重 |\n\n",
    "```kotlin\nfun plan(items: List<String>): List<String> =\n    items.distinct()\n        .sortedBy { it.length }\n        .mapIndexed { i, s -> \"${i + 1}. $s\" }\n\nfun main() {\n    println(plan(listOf(\"写草稿\", \"改草稿\", \"写草稿\", \"校对\")).joinToString(\"\\n\"))\n}\n```\n\n",
    "Here is the short English version: start small, validate each step, and keep the change set easy to review. If something feels slow, measure it before you optimise it — the bottleneck is usually not where you expect.\n\n",
    "## 三、估算\n\n总耗时可以粗略写成\n\n$$\nT = \\sum_{i=1}^{n} t_i \\cdot (1 + r_i)\n$$\n\n其中 $t_i$ 是第 $i$ 项的基础耗时，$r_i$ 是返工的概率。把 $r_i$ 压到 $0.1$ 以下，总时间就会明显下降。\n\n",
    "> 小提醒：估算只是估算。遇到和预期差得很远的情况，先停下来看看是不是前提错了，而不是硬着头皮继续。\n\n",
    "```python\ndef expected(times, risks):\n    return sum(t * (1 + r) for t, r in zip(times, risks))\n\nprint(expected([30, 45, 20], [0.1, 0.3, 0.05]))  # 108.0\n```\n\n",
    "## 四、检查清单\n\n- [x] 范围确认\n- [x] 材料齐全\n- [ ] 同步给相关的人\n- [ ] 留出最后的检查时间\n\n如果你愿意，我可以把第三、第四项也帮你排进日程，并在前一天提醒你。要这样吗？\n\n",
]

TOKEN = re.compile(r"[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]|[A-Za-z0-9_]+|\s+|[^\sA-Za-z0-9_\u4e00-\u9fff]")


def answer_pieces(count):
    """About `count` pieces (roughly one token each) of Markdown, cycling through BLOCKS."""
    pieces = []
    i = 0
    while len(pieces) < count:
        pieces.extend(TOKEN.findall(BLOCKS[i % len(BLOCKS)]))
        i += 1
    return pieces[:count]


REASONING = ("用户想要一个整理好的方案。先判断他要的是结论还是过程：上下文里没有时间压力，所以可以先给结论，"
             "再分步骤说明。结构上用标题分块，穿插一张对比表和一段代码，最后用清单收尾。")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FakeLLM/1.0"
    tokens = 1500
    rate = 40.0
    reasoning = 0

    def log_message(self, fmt, *args):
        sys.stderr.write("[fake-llm] " + (fmt % args) + "\n")

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._json(200, {"object": "list", "data": [{"id": MODEL_ID, "object": "model", "created": 0, "owned_by": "perf"}]})
        else:
            self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            request = json.loads(raw)
        except ValueError:
            request = {}
        if not self.path.rstrip("/").endswith("/chat/completions"):
            return self._json(404, {"error": {"message": "not found"}})
        if request.get("stream"):
            self._stream(request)
        else:
            self._json(200, {
                "id": "chatcmpl-fake", "object": "chat.completion", "created": int(time.time()), "model": MODEL_ID,
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "好的。"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 50, "completion_tokens": 3, "total_tokens": 53},
            })

    def _chunk(self, delta, finish=None, usage=None):
        payload = {"id": "chatcmpl-fake", "object": "chat.completion.chunk", "created": int(time.time()), "model": MODEL_ID,
                   "choices": [{"index": 0, "delta": delta, "finish_reason": finish}] if usage is None else []}
        if usage is not None:
            payload["usage"] = usage
        data = ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()
        # HTTP/1.1 chunked transfer encoding, one chunk per SSE event.
        self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
        self.wfile.flush()

    def _stream(self, request):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        interval = 1.0 / self.rate
        started = time.monotonic()
        sent = 0
        try:
            self._chunk({"role": "assistant", "content": ""})
            if self.reasoning:
                for piece in TOKEN.findall(REASONING)[: self.reasoning]:
                    self._chunk({"reasoning_content": piece})
                    sent += 1
                    time.sleep(max(0.0, started + sent * interval - time.monotonic()))
            for piece in answer_pieces(self.tokens):
                self._chunk({"content": piece})
                sent += 1
                time.sleep(max(0.0, started + sent * interval - time.monotonic()))
            self._chunk({}, finish="stop")
            if (request.get("stream_options") or {}).get("include_usage"):
                self._chunk({}, usage={"prompt_tokens": 200, "completion_tokens": sent, "total_tokens": 200 + sent})
            data = b"data: [DONE]\n\n"
            self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # the app stopped the reply


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18799)
    parser.add_argument("--tokens", type=int, default=1500, help="tokens in every streamed answer")
    parser.add_argument("--rate", type=float, default=40.0, help="tokens per second")
    parser.add_argument("--reasoning", type=int, default=0, help="stream this many reasoning tokens first")
    args = parser.parse_args()
    Handler.tokens, Handler.rate, Handler.reasoning = args.tokens, args.rate, args.reasoning
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    sys.stderr.write(f"[fake-llm] http://{args.host}:{args.port}/v1  tokens={args.tokens} rate={args.rate}/s\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
