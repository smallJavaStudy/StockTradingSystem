import requests
from core.config import EASTMONEY_HEADERS, QUOTE_API, KLINE_API, FINANCE_API

# 行情
r = requests.get(QUOTE_API, params={"secid": "1.600519", "fields": "f43,f44,f45,f46,f47,f48,f57,f58,f60,f170"}, headers=EASTMONEY_HEADERS, timeout=15)
d = r.json().get("data", {})
print("quote OK:", d.get("f58"), d.get("f43") / 100 if d.get("f43") else "?")

# K线
r2 = requests.get(KLINE_API, params={"secid": "1.600519", "fields1": "f1,f2,f3,f4,f5,f6", "fields2": "f51,f52,f53,f54,f55,f56,f57", "klt": 101, "fqt": 1, "end": "20500101", "lmt": 3}, headers=EASTMONEY_HEADERS, timeout=15)
k = r2.json().get("data", {}).get("klines", [])
print("kline OK:", len(k), "days, latest:", k[-1] if k else "none")

# 基本面
r3 = requests.get(FINANCE_API, params={"reportName": "RPT_LICO_FN_CPD", "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT", "filter": '(SECURITY_CODE="600519")', "pageSize": 2, "sortColumns": "NOTICE_DATE", "sortTypes": -1}, headers={"Referer": "https://data.eastmoney.com/", "User-Agent": EASTMONEY_HEADERS["User-Agent"]}, timeout=15)
f = r3.json().get("result", {}).get("data", [])
print("finance OK:", f)
