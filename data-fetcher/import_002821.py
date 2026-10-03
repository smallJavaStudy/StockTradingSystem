"""
凯莱英(002821) 数据一键导入脚本
链路：东方财富 API → 数据清洗 → Spring Boot H2 入库
用法：确保后端已启动在 8080 端口，然后 python import_002821.py
限流：行情≥3s、K线≥5s、基本面≥10s（串行，不并发）
"""
import requests
import json
import time
import sys
from datetime import datetime

# ======================== 配置 ========================
BACKEND_URL = "http://localhost:8080/api/stock"
CODE = "002821"
MARKET = "SZ"  # 00开头=深圳

HEADERS = {
    "Referer": "https://emweb.securities.eastmoney.com/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
}

QUOTE_API = "https://push2.eastmoney.com/api/qt/stock/get"
KLINE_API = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
FINANCE_API = "https://datacenter.eastmoney.com/securities/api/data/v1/get"


# ======================== 工具函数 ========================
def check_backend():
    """检查后端是否在线"""
    try:
        r = requests.get(f"{BACKEND_URL}/list", timeout=5)
        if r.status_code == 200:
            print("[✓] 后端在线 (8080)")
            return True
    except requests.ConnectionError:
        pass
    print("[✗] 后端未启动! 请先启动 backend (mvn spring-boot:run)")
    return False


def safe_float(val, default=0.0):
    """安全转 float，处理 None"""
    if val is None:
        return default
    return float(val)


def post_json(url, data):
    """POST JSON 到后端，返回响应"""
    try:
        r = requests.post(url, json=data, headers={"Content-Type": "application/json"}, timeout=15)
        r.raise_for_status()
        return r.json()
    except requests.RequestException as e:
        print(f"  [✗] POST 失败: {e}")
        if hasattr(e, 'response') and e.response is not None:
            print(f"      响应体: {e.response.text[:300]}")
        return None


# ======================== 主流程 ========================
def main():
    print("=" * 60)
    print(f"  凯莱英({CODE}) 数据一键导入")
    print(f"  时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 60)
    print()

    if not check_backend():
        sys.exit(1)

    # ---- Step 1: 抓取实时行情 ----
    print("[1/4] 抓取实时行情...")
    r = requests.get(
        QUOTE_API,
        params={
            "secid": f"0.{CODE}",
            "fields": "f43,f44,f45,f46,f47,f48,f57,f58,f60,f170"
        },
        headers=HEADERS,
        timeout=15
    )
    d = r.json().get("data", {})
    if not d:
        print("[✗] 行情接口返回空数据，请检查网络或交易时段")
        sys.exit(1)

    stock_name = d.get("f58", "凯莱英")
    price_raw   = safe_float(d.get("f43"))
    open_raw    = safe_float(d.get("f46"))
    high_raw    = safe_float(d.get("f44"))
    low_raw     = safe_float(d.get("f45"))
    pre_close   = safe_float(d.get("f60"))
    volume      = int(safe_float(d.get("f47")))
    amount      = safe_float(d.get("f48"))
    change_pct  = safe_float(d.get("f170"))

    # 东方财富价格字段需除以 100
    price    = round(price_raw / 100, 2) if abs(price_raw) > 100 else price_raw
    open_p   = round(open_raw / 100, 2) if abs(open_raw) > 100 else open_raw
    high_p   = round(high_raw / 100, 2) if abs(high_raw) > 100 else high_raw
    low_p    = round(low_raw / 100, 2) if abs(low_raw) > 100 else low_raw
    pre_p    = round(pre_close / 100, 2) if abs(pre_close) > 100 else pre_close
    chg_pct  = round(change_pct / 100, 2)

    print(f"  名称: {stock_name}  现价: {price}  涨跌幅: {chg_pct}%")
    print(f"  开盘: {open_p}  最高: {high_p}  最低: {low_p}  昨收: {pre_p}")
    print(f"  成交量: {volume}手  成交额: {amount/1e8:.2f}亿")

    # ---- Step 2: 入库基本信息和行情 ----
    print()
    print("[2/4] 入库基本信息和行情...")

    # 2a. 股票基本信息
    basic_data = {
        "code": CODE,
        "name": stock_name,
        "market": MARKET
    }
    result = post_json(f"{BACKEND_URL}/basic", basic_data)
    if result:
        print(f"  [✓] 基本信息入库: {result.get('name')} ({result.get('code')})")
    else:
        print("  [!] 基本信息入库失败，继续...")

    # 2b. 行情数据
    quote_data = {
        "price": price,
        "open": open_p,
        "high": high_p,
        "low": low_p,
        "preClose": pre_p,
        "volume": volume,
        "amount": amount,
        "changePct": chg_pct
    }
    result = post_json(f"{BACKEND_URL}/{CODE}/quote", quote_data)
    if result:
        print(f"  [✓] 行情入库: price={result.get('price')} changePct={result.get('changePct')}%")
    else:
        print("  [!] 行情入库失败，继续...")

    # ---- Step 3: K线（限流 5s）----
    print()
    print("[3/4] 抓取日K线（限流等待 5s）...")
    time.sleep(5)

    r2 = requests.get(
        KLINE_API,
        params={
            "secid": f"0.{CODE}",
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57",
            "klt": 101,       # 日K
            "fqt": 1,         # 前复权
            "end": "20500101",
            "lmt": 120        # 最近120个交易日
        },
        headers=HEADERS,
        timeout=15
    )
    klines_raw = r2.json().get("data", {}).get("klines", [])
    if not klines_raw:
        print("[✗] K线接口返回空数据")
        sys.exit(1)

    klines = []
    for line in klines_raw:
        parts = line.split(",")
        klines.append({
            "tradeDate": parts[0],          # "2026-07-03"
            "open": float(parts[1]),
            "close": float(parts[2]),
            "high": float(parts[3]),
            "low": float(parts[4]),
            "volume": int(parts[5]),
            "amount": float(parts[6])
        })

    print(f"  获取 {len(klines)} 条日K线 ({klines[0]['tradeDate']} ~ {klines[-1]['tradeDate']})")

    result = post_json(f"{BACKEND_URL}/{CODE}/kline", klines)
    if result:
        print(f"  [✓] K线入库: {len(result)} 条")
    else:
        print("  [!] K线入库失败，继续...")

    # ---- Step 4: 基本面（限流 10s）----
    print()
    print("[4/4] 抓取基本面财务数据（限流等待 10s）...")
    time.sleep(10)

    r3 = requests.get(
        FINANCE_API,
        params={
            "reportName": "RPT_LICO_FN_CPD",
            "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORT_DATE",
            "filter": f'(SECURITY_CODE="{CODE}")',
            "pageSize": 8,
            "sortColumns": "NOTICE_DATE",
            "sortTypes": -1
        },
        headers={"Referer": "https://data.eastmoney.com/", "User-Agent": HEADERS["User-Agent"]},
        timeout=15
    )
    finance_rows = r3.json().get("result", {}).get("data", [])
    if not finance_rows:
        print("[✗] 基本面接口返回空数据")
        sys.exit(1)

    finances = []
    for row in finance_rows:
        report_date_raw = row.get("REPORT_DATE", "")
        if report_date_raw:
            report_date = report_date_raw[:10]  # "2025-12-31 00:00:00" → "2025-12-31"
        else:
            report_date = ""

        finances.append({
            "reportDate": report_date,
            "basicEps": row.get("BASIC_EPS"),
            "weightedRoe": row.get("WEIGHTAVG_ROE"),
            "totalRevenue": row.get("TOTAL_OPERATE_INCOME"),
            "netProfit": row.get("PARENT_NETPROFIT")
        })

    print(f"  获取 {len(finances)} 期财务数据:")
    for f in finances:
        rev = (f["totalRevenue"] or 0) / 1e8
        profit = (f["netProfit"] or 0) / 1e8
        print(f"    {f['reportDate']} | EPS:{f['basicEps']} | ROE:{f['weightedRoe']}% | 营收:{rev:.2f}亿 | 净利润:{profit:.2f}亿")

    result = post_json(f"{BACKEND_URL}/{CODE}/finance", finances)
    if result:
        print(f"  [✓] 基本面入库: {len(result)} 期")
    else:
        print("  [!] 基本面入库失败，继续...")

    # ---- 完成 ----
    print()
    print("=" * 60)
    print("  ✓ 数据导入完成!")
    print(f"  前端访问: http://localhost:5173/stock/{CODE}")
    print(f"  API 验证: http://localhost:8080/api/stock/{CODE}/full")
    print("=" * 60)


if __name__ == "__main__":
    main()
