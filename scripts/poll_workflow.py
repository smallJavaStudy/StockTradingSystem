"""轮询工作流实例直至结束，并保存 synthesis 节点输出
用法：python poll_workflow.py <processInstanceId> <输出md路径>
"""
import sys
import io
import time
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

pid = sys.argv[1]
out_path = sys.argv[2]
BASE = "http://localhost:8080/api/v1/workflow-execution"

last = None
for i in range(120):
    d = requests.get(f"{BASE}/{pid}", timeout=10).json()
    st = d.get("status")
    nodes = {n.get("nodeId"): n.get("status") for n in d.get("nodes", [])}
    snap = (st, tuple(sorted(nodes.items())))
    if snap != last:
        done = sum(1 for v in nodes.values() if v == "COMPLETED")
        running = [k for k, v in nodes.items() if v == "RUNNING"]
        print(time.strftime("%H:%M:%S"), st, f"完成{done}/11", "运行中:", running, flush=True)
        last = snap
    if st in ("COMPLETED", "FAILED", "CANCELLED"):
        syn = next((n for n in d.get("nodes", []) if n.get("nodeId") == "synthesis"), {})
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(syn.get("output") or "（无输出）")
        print("最终状态:", st, "| synthesis 输出", len(syn.get("output") or ""), "字符 →", out_path)
        sys.exit(0 if st == "COMPLETED" else 1)
    time.sleep(15)
print("超时未结束")
sys.exit(2)
