"""查看 RPT_LICO_FN_CPD 当前可用字段"""
import sys
import io
import json
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

CODE = sys.argv[1] if len(sys.argv) > 1 else "300364"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

r = requests.get(
    "https://datacenter.eastmoney.com/securities/api/data/v1/get",
    params={
        "reportName": "RPT_LICO_FN_CPD",
        "columns": "ALL",
        "filter": f'(SECURITY_CODE="{CODE}")',
        "pageSize": 2,
    },
    headers={"Referer": "https://data.eastmoney.com/", "User-Agent": UA},
    timeout=15,
)
print("HTTP:", r.status_code)
d = r.json()
print("success:", d.get("success"), "message:", d.get("message"))
rows = (d.get("result") or {}).get("data") or []
print("rows:", len(rows))
if rows:
    print(json.dumps(rows[0], ensure_ascii=False, indent=1))
