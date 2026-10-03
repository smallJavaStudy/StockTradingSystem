"""
通用股票数据一键导入脚本（基于 import_002821.py 参数化）
链路：东方财富 API → 数据清洗 → Spring Boot H2 入库
用法：python import_stock.py <6位代码>   （后端需已启动在 8080）
限流：行情→K线≥5s、K线→基本面≥10s（串行，不并发）
"""
import requests
import sys
import time
from datetime import datetime

BACKEND_URL = "http://localhost:8080/api/stock"

HEADERS = {
    "Referer": "https://emweb.securities.eastmoney.com/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
}

QUOTE_API = "https://push2.eastmoney.com/api/qt/stock/get"
KLINE_API = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
FINANCE_API = "https://datacenter.eastmoney.com/securities/api/data/v1/get"


def secid(code: str) -> str:
    return f"1.{code}" if code.startswith("6") else f"0.{code}"


def safe_float(val, default=0.0):
    if val is None:
        return default
    return float(val)


def post_json(url, data):
    try:
        r = requests.post(url, json=data, headers={"Content-Type": "application/json"}, timeout=15)
        r.raise_for_status()
        return r.json()
    except requests.RequestException as e:
        print(f"  [X] POST 失败: {e}")
        if hasattr(e, 'response') and e.response is not None:
            print(f"      响应体: {e.response.text[:300]}")
        return None


def main():
    if len(sys.argv) < 2:
        print("用法: python import_stock.py <6位代码>")
        sys.exit(1)
    code = sys.argv[1]
    market = "SH" if code.startswith("6") else "SZ"

    print(f"==== {code} 数据导入 {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} ====")

    try:
        r = requests.get(f"{BACKEND_URL}/list", timeout=5)
        r.raise_for_status()
    except requests.RequestException:
        print("[X] 后端未启动 (8080)")
        sys.exit(1)
    print("[OK] 后端在线")

    # Step 1: 实时行情
    print("[1/4] 抓取实时行情...")
    r = requests.get(QUOTE_API, params={
        "secid": secid(code),
        "fields": "f43,f44,f45,f46,f47,f48,f57,f58,f60,f170"
    }, headers=HEADERS, timeout=15)
    d = r.json().get("data", {})
    if not d:
        print("[X] 行情接口返回空数据")
        sys.exit(1)

    stock_name = d.get("f58", code)
    def px(v):
        v = safe_float(v)
        return round(v / 100, 2) if abs(v) > 100 else v
    price, open_p, high_p, low_p, pre_p = px(d.get("f43")), px(d.get("f46")), px(d.get("f44")), px(d.get("f45")), px(d.get("f60"))
    volume = int(safe_float(d.get("f47")))
    amount = safe_float(d.get("f48"))
    chg_pct = round(safe_float(d.get("f170")) / 100, 2)
    print(f"  {stock_name} 现价:{price} 涨跌:{chg_pct}%")

    # Step 2: 入库基本信息与行情
    print("[2/4] 入库基本信息和行情...")
    result = post_json(f"{BACKEND_URL}/basic", {"code": code, "name": stock_name, "market": market})
    print(f"  基本信息: {'OK' if result else 'FAIL'}")
    result = post_json(f"{BACKEND_URL}/{code}/quote", {
        "price": price, "open": open_p, "high": high_p, "low": low_p,
        "preClose": pre_p, "volume": volume, "amount": amount, "changePct": chg_pct})
    print(f"  行情: {'OK' if result else 'FAIL'}")

    # Step 3: K线（限流5s）
    print("[3/4] 抓取日K线（限流5s）...")
    time.sleep(5)
    r2 = requests.get(KLINE_API, params={
        "secid": secid(code),
        "fields1": "f1,f2,f3,f4,f5,f6",
        "fields2": "f51,f52,f53,f54,f55,f56,f57",
        "klt": 101, "fqt": 1, "end": "20500101", "lmt": 120
    }, headers=HEADERS, timeout=15)
    klines_raw = r2.json().get("data", {}).get("klines", [])
    if not klines_raw:
        print("[X] K线接口返回空数据")
        sys.exit(1)
    klines = []
    for line in klines_raw:
        p = line.split(",")
        klines.append({"tradeDate": p[0], "open": float(p[1]), "close": float(p[2]),
                       "high": float(p[3]), "low": float(p[4]), "volume": int(p[5]), "amount": float(p[6])})
    print(f"  获取 {len(klines)} 条 ({klines[0]['tradeDate']} ~ {klines[-1]['tradeDate']})")
    result = post_json(f"{BACKEND_URL}/{code}/kline", klines)
    print(f"  K线入库: {len(result) if result else 'FAIL'} 条")

    # Step 4: 财务（限流10s）
    print("[4/4] 抓取财务数据（限流10s）...")
    time.sleep(10)
    r3 = requests.get(FINANCE_API, params={
        "reportName": "RPT_LICO_FN_CPD",
        "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORTDATE",
        "filter": f'(SECURITY_CODE="{code}")',
        "pageSize": 8,
        "sortColumns": "NOTICE_DATE",
        "sortTypes": -1
    }, headers={"Referer": "https://data.eastmoney.com/", "User-Agent": HEADERS["User-Agent"]}, timeout=15)
    try:
        finance_root = r3.json()
    except Exception as e:
        print(f"[!] 基本面响应解析失败: {e}")
        finance_root = {}
    finance_rows = []
    if isinstance(finance_root, dict):
        result_block = finance_root.get("result")
        if isinstance(result_block, dict):
            finance_rows = result_block.get("data") or []
    if not finance_rows:
        print("[!] 基本面接口返回空数据，跳过")
    else:
        finances = []
        for row in finance_rows:
            # 2026-04 起东财该报表字段由 REPORT_DATE 更名为 REPORTDATE，做双向兼容
            rd = (row.get("REPORTDATE") or row.get("REPORT_DATE") or "")[:10]
            finances.append({"reportDate": rd, "basicEps": row.get("BASIC_EPS"),
                             "weightedRoe": row.get("WEIGHTAVG_ROE"),
                             "totalRevenue": row.get("TOTAL_OPERATE_INCOME"),
                             "netProfit": row.get("PARENT_NETPROFIT")})
        for f in finances:
            rev = (f["totalRevenue"] or 0) / 1e8
            profit = (f["netProfit"] or 0) / 1e8
            print(f"    {f['reportDate']} EPS:{f['basicEps']} ROE:{f['weightedRoe']}% 营收:{rev:.2f}亿 净利:{profit:.2f}亿")
        result = post_json(f"{BACKEND_URL}/{code}/finance", finances)
        print(f"  财务入库: {len(result) if result else 'FAIL'} 期")

    print(f"==== {code} {stock_name} 数据导入完成 ====")


if __name__ == "__main__":
    main()
