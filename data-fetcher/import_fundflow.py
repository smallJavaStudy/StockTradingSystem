"""个股每日资金流导入脚本
链路：东方财富 fflow/daykline API → 清洗 → Spring Boot H2 入库
用法：python import_fundflow.py <6位代码> [天数=60]   （后端需已启动在 8080）
东财返回 klines 每行逗号分隔：
  日期,主力净额,小单净额,中单净额,大单净额,超大单净额,主力净占比,小单净占比,中单净占比,大单净占比,超大单净占比,...
"""
import sys
import io
import requests
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

BACKEND_URL = "http://localhost:8080/api/stock"
FFLOW_API = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get"
HEADERS = {
    "Referer": "https://data.eastmoney.com/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
}


def secid(code: str) -> str:
    return f"1.{code}" if code.startswith("6") else f"0.{code}"


def sf(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def main():
    if len(sys.argv) < 2:
        print("用法: python import_fundflow.py <6位代码> [天数=60]")
        sys.exit(1)
    code = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 60

    print(f"==== {code} 资金流导入 {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} ====")

    r = requests.get(FFLOW_API, params={
        "secid": secid(code),
        "fields1": "f1,f2,f3,f7",
        "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
        "klt": 101,
        "lmt": limit,
    }, headers=HEADERS, timeout=15)
    data = r.json().get("data") or {}
    klines = data.get("klines") or []
    if not klines:
        print("[X] 资金流接口返回空数据:", r.text[:200])
        sys.exit(1)

    rows = []
    for line in klines:
        p = line.split(",")
        # p[1]=主力净额 p[2]=小单 p[3]=中单 p[4]=大单 p[5]=超大单 p[6]=主力净占比
        rows.append({
            "tradeDate": p[0],
            "mainNetInflow": sf(p[1]),
            "smallNetInflow": sf(p[2]),
            "mediumNetInflow": sf(p[3]),
            "largeNetInflow": sf(p[4]),
            "superLargeNetInflow": sf(p[5]),
            "mainNetRatio": sf(p[6]) if len(p) > 6 else None,
        })

    print(f"  获取 {len(rows)} 条 ({rows[0]['tradeDate']} ~ {rows[-1]['tradeDate']})")
    tail = rows[-5:]
    for t in tail:
        mn = (t["mainNetInflow"] or 0) / 1e8
        print(f"    {t['tradeDate']} 主力净流入 {mn:+.2f}亿 占比 {t['mainNetRatio']}%")

    resp = requests.post(f"{BACKEND_URL}/{code}/fundflow", json=rows,
                         headers={"Content-Type": "application/json"}, timeout=30)
    resp.raise_for_status()
    print(f"  [OK] 入库 {len(resp.json())} 条")
    print(f"==== {code} 资金流导入完成 ====")


if __name__ == "__main__":
    main()
