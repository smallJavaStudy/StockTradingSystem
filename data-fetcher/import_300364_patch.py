"""300364 数据补抓（AKShare 备选，跳过被限流的行情接口）"""
import sys, io, requests, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

CODE = "300364"
BACKEND = "http://localhost:8080/api/stock"
HEADERS = {"Content-Type": "application/json"}

# 1) K线（东财 his 接口通常更稳定）
print("[1/2] 抓取 K线...")
time.sleep(5)
r = requests.get("https://push2his.eastmoney.com/api/qt/stock/kline/get", params={
    "secid": f"0.{CODE}", "fields1": "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13",
    "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
    "klt": 101, "fqt": 0, "lmt": 120, "end": "20500101", "iscca": 1,
}, headers={"Referer": "https://quote.eastmoney.com/", "User-Agent": "Mozilla/5.0"}, timeout=15)
d = r.json().get("data") or {}
klines = d.get("klines") or []
print(f"  K线 {len(klines)} 条")
if klines:
    rows = []
    for line in klines:
        p = line.split(",")
        rows.append({"tradeDate": p[0], "open": float(p[1]), "close": float(p[2]),
                     "high": float(p[3]), "low": float(p[4]), "volume": int(float(p[5])),
                     "amount": float(p[6]), "amplitude": float(p[7]), "pctChange": float(p[8]),
                     "priceChange": float(p[9]), "turnover": float(p[10])})
    resp = requests.post(f"{BACKEND}/{CODE}/kline", json=rows, headers=HEADERS, timeout=30)
    print(f"  K线入库 {len(resp.json())} 条")

# 2) 财务（datacenter 接口通常稳定）
print("[2/2] 抓取 财务...")
time.sleep(10)
r = requests.get("https://datacenter.eastmoney.com/securities/api/data/v1/get", params={
    "reportName": "RPT_LICO_FN_CPD",
    "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORTDATE",
    "filter": f'(SECURITY_CODE="{CODE}")', "pageSize": 8,
    "sortColumns": "NOTICE_DATE", "sortTypes": -1,
}, headers={"Referer": "https://data.eastmoney.com/", "User-Agent": "Mozilla/5.0"}, timeout=15)
fin_rows = []
result_block = (r.json().get("result") or {})
if isinstance(result_block, dict):
    fin_rows = result_block.get("data") or []
print(f"  财务 {len(fin_rows)} 条")
if fin_rows:
    out = []
    for row in fin_rows:
        rd = (row.get("REPORTDATE") or row.get("REPORT_DATE") or "")[:10]
        out.append({"reportDate": rd, "eps": row.get("BASIC_EPS"), "roe": row.get("WEIGHTAVG_ROE"),
                    "revenue": row.get("TOTAL_OPERATE_INCOME"), "netProfit": row.get("PARENT_NETPROFIT")})
    resp = requests.post(f"{BACKEND}/{CODE}/finance", json=out, headers=HEADERS, timeout=30)
    print(f"  财务入库 {len(resp.json())} 条")

print("==== 300364 数据补抓完成 ====")
