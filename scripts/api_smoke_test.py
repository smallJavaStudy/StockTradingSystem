# -*- coding: utf-8 -*-
"""
API Smoke Test - verify backend endpoints are reachable
"""
import sys
import io
import urllib.request
import json

# Fix Windows console encoding
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

BASE = "http://localhost:8080"
passed = 0
failed = 0

def api_post(path, body):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return None, str(e)

def api_get(path):
    url = f"{BASE}{path}"
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return None, str(e)

def check(name, status, body):
    global passed, failed
    if status and 200 <= status < 300:
        print(f"  [PASS] {name}: HTTP {status}")
        if isinstance(body, dict):
            print(f"      keys: {list(body.keys())[:10]}")
            for k in list(body.keys())[:3]:
                v = body[k]
                if isinstance(v, list):
                    print(f"      {k}: [{len(v)} items]")
                elif isinstance(v, str) and len(str(v)) > 80:
                    print(f"      {k}: {str(v)[:80]}...")
                else:
                    print(f"      {k}: {v}")
        passed += 1
    else:
        print(f"  [FAIL] {name}: HTTP {status} | {str(body)[:200]}")
        failed += 1

print("=" * 60)
print("API Smoke Test")
print("=" * 60)

# Test 1: resolve
print("\n1. POST /api/v1/analysis-agent/resolve")
print("   body: {'query': 'yangjie keji'}")
status, body = api_post("/api/v1/analysis-agent/resolve", {"query": "扬杰科技"})
check("resolve stock", status, body)

# Test 2: analyze
print("\n2. POST /api/v1/analysis-agent/analyze")
print("   body: {'stockCode':'300373','directions':['TECHNICAL_ANALYSIS'],'modelChoice':'deepseek-v4'}")
status, body = api_post("/api/v1/analysis-agent/analyze", {
    "stockCode": "300373",
    "directions": ["TECHNICAL_ANALYSIS"],
    "modelChoice": "deepseek-v4"
})
check("analyze 300373", status, body)

# Test 3: stock list
print("\n3. GET /api/stock/list")
status, body = api_get("/api/stock/list")
check("stock list", status, body)

print(f"\n{'=' * 60}")
print(f"Result: {passed} passed, {failed} failed")
print(f"{'=' * 60}")
