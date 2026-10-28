"""同一会话切换 Agent 的真实联调。

流程：QwenCode 记住口令 -> Codex 回答口令 -> QwenCode 再说一次口令。
Codex 从未见过第一轮，能回答说明网关把共享历史交给了它；三轮必须共用同一 sessionId。

会产生三次真实模型请求，不注册任何客户端工具、不修改画布。
"""

import http.client
import json
import pathlib
import time
import uuid

HOST, PORT = "127.0.0.1", 8080
CLIENT_ID = "shared-" + uuid.uuid4().hex[:10]
USER_ID = "shared-user"


def chat(agent, prompt, session=None):
    payload = {
        "clientId": CLIENT_ID,
        "endUserId": USER_ID,
        "agent": agent,
        "prompt": prompt,
    }
    if session:
        payload["sessionId"] = session
    envelope = {"v": "0.1", "id": uuid.uuid4().hex, "type": "chat", "payload": payload}

    connection = http.client.HTTPConnection(HOST, PORT, timeout=180)
    started = time.monotonic()
    connection.request(
        "POST",
        "/chat",
        json.dumps(envelope, ensure_ascii=False).encode(),
        {
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "Accept-Encoding": "identity",
        },
    )
    response = connection.getresponse()
    assert response.status == 200, (agent, response.status)

    buffer = b""
    types = []
    session_id = None
    output = None
    while True:
        data = response.read1(65536)
        if not data:
            break
        buffer += data
        while b"\n\n" in buffer:
            frame, buffer = buffer.split(b"\n\n", 1)
            lines = [l[5:].lstrip() for l in frame.splitlines() if l.startswith(b"data:")]
            if not lines:
                continue
            event = json.loads(b"\n".join(lines))
            types.append(event["type"])
            if event["type"] == "session":
                session_id = event["payload"]["sessionId"]
            elif event["type"] == "final":
                output = event["payload"]["output"]
                session_id = event["payload"]["sessionId"]
            elif event["type"] == "error":
                raise AssertionError((agent, event["payload"]))
    connection.close()

    assert types and types[0] == "session", ("session metadata must arrive first", types[:3])
    assert output, ("empty answer", agent)
    return {
        "agent": agent,
        "sessionId": session_id,
        "output": output,
        "eventTypes": types[:4],
        "deltaCount": types.count("delta"),
        "elapsedMs": round((time.monotonic() - started) * 1000),
    }


report = {}
first = chat("cli", "请记住本轮口令：青山十七。只回复“已记住”，不要使用任何工具。")
second = chat(
    "codex",
    "这是同一条对话的后续。上一轮让你记住的口令是什么？只回复口令本身，不要调用工具。",
    first["sessionId"],
)
third = chat(
    "cli",
    "再把当前对话里记住的口令说一次。只回复口令本身，不要调用工具。",
    second["sessionId"],
)

assert first["sessionId"] == second["sessionId"] == third["sessionId"], "切换 Agent 后会话 ID 必须一致"
assert "青山十七" in second["output"], ("Codex 未继承共享历史", second["output"])
assert "青山十七" in third["output"], ("切回 QwenCode 后丢失上下文", third["output"])

report["turns"] = [first, second, third]
report["sessionId"] = first["sessionId"]
report["passed"] = True

out = pathlib.Path(__file__).resolve().parents[1] / "target" / "shared-conversation-report.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

print(json.dumps({k: v for k, v in report.items() if k != "turns"}, ensure_ascii=False))
print("PASS: 同一会话跨 QwenCode -> Codex -> QwenCode 连续对话", flush=True)
