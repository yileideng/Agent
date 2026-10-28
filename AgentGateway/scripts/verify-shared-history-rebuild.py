"""验证共享历史能重建真实 Agent 的上下文。

Gateway 重启或原生会话失效后，Agent 只能靠持久化的共享历史作答。
读取最近一条含有已完成回复的会话，用同一 sessionId 追问，确认模型答得出此前内容。

会产生一次真实模型请求；不注册工具、不修改画布。
"""

import http.client
import json
import pathlib
import sys

HOST, PORT = "127.0.0.1", 8080
ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "conversations"


def latest_session_with_reply():
    best = None
    for path in DATA.glob("*.json"):
        document = json.loads(path.read_text(encoding="utf-8"))
        if not any(e.get("type") == "assistant" and e.get("status") == "completed"
                   for e in document.get("events", [])):
            continue
        if best is None or document["nextSeq"] > best["nextSeq"]:
            best = document
    return best


def chat(agent, prompt, session_id, client_id, end_user):
    envelope = {
        "v": "0.1",
        "id": "probe",
        "type": "chat",
        "payload": {
            "clientId": client_id,
            "endUserId": end_user,
            "agent": agent,
            "prompt": prompt,
            "sessionId": session_id,
        },
    }
    connection = http.client.HTTPConnection(HOST, PORT, timeout=180)
    connection.request(
        "POST",
        "/chat",
        json.dumps(envelope, ensure_ascii=False).encode(),
        {"Content-Type": "application/json", "Accept": "text/event-stream",
         "Accept-Encoding": "identity"},
    )
    response = connection.getresponse()
    assert response.status == 200, response.status
    buffer = b""
    output = None
    seen_session = None
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
            if event["type"] == "session":
                seen_session = event["payload"]["sessionId"]
            elif event["type"] == "final":
                output = event["payload"]["output"]
                seen_session = event["payload"]["sessionId"]
            elif event["type"] == "error":
                raise AssertionError(event["payload"])
    connection.close()
    return output, seen_session


document = latest_session_with_reply()
if document is None:
    print("没有可用的历史会话，先运行 verify-shared-conversation.py", file=sys.stderr)
    sys.exit(2)

history = [e for e in document["events"] if e.get("type") in ("user", "assistant")]
clue = next((e["text"] for e in history
             if e.get("type") == "user" and "青山十七" in (e.get("text") or "")), None)
assert clue, "历史里没有口令线索，无法验证"

output, session_id = chat(
    "cli",
    "这是同一条对话的后续，历史可能来自另一个 Agent。请问本轮之前约定要记住的口令是什么？只回复口令本身。",
    document["sessionId"],
    document["clientId"],
    document["endUserId"],
)

assert session_id == document["sessionId"], ("会话 ID 变化了", session_id)
assert "青山十七" in output, ("模型没有拿到共享历史", output)

print(json.dumps({
    "sessionId": session_id,
    "historyEvents": len(history),
    "answer": output.strip()[:120],
    "passed": True,
}, ensure_ascii=False))
print("PASS: 重启/原生会话失效后，真实 Agent 仍按共享历史作答", flush=True)
