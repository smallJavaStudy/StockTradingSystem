import requests, json, time

H = {
    'Referer': 'https://emweb.securities.eastmoney.com/',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
}

print("=== 凯莱英 002821 ===")
print()

# --- 行情 ---
r = requests.get(
    'https://push2.eastmoney.com/api/qt/stock/get',
    params={'secid': '0.002821', 'fields': 'f43,f44,f45,f46,f47,f48,f57,f58,f60,f170'},
    headers=H, timeout=15
)
d = r.json().get('data', {})
print("【实时行情】")
print(f"  名称: {d.get('f58','?')}  代码: {d.get('f57','?')}")
print(f"  现价: {d.get('f43',0)/100:.2f}  开盘: {d.get('f46',0)/100:.2f}")
print(f"  最高: {d.get('f44',0)/100:.2f}  最低: {d.get('f45',0)/100:.2f}  昨收: {d.get('f60',0)/100:.2f}")
print(f"  涨跌幅: {d.get('f170',0)/100:.2f}%  成交量: {d.get('f47',0)}手  成交额: {d.get('f48',0)/1e8:.2f}亿")

time.sleep(3)

# --- K线 ---
print()
r2 = requests.get(
    'https://push2his.eastmoney.com/api/qt/stock/kline/get',
    params={
        'secid': '0.002821',
        'fields1': 'f1,f2,f3,f4,f5,f6',
        'fields2': 'f51,f52,f53,f54,f55,f56,f57',
        'klt': 101, 'fqt': 1, 'end': '20500101', 'lmt': 5
    },
    headers=H, timeout=15
)
klines = r2.json().get('data', {}).get('klines', [])
print("【日K线(最近5日)】")
for k in klines:
    print(f"  {k}")

time.sleep(5)

# --- 基本面 ---
print()
r3 = requests.get(
    'https://datacenter.eastmoney.com/securities/api/data/v1/get',
    params={
        'reportName': 'RPT_LICO_FN_CPD',
        'columns': 'SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORT_DATE',
        'filter': '(SECURITY_CODE="002821")',
        'pageSize': 3,
        'sortColumns': 'NOTICE_DATE',
        'sortTypes': -1
    },
    headers={'Referer': 'https://data.eastmoney.com/', 'User-Agent': H['User-Agent']},
    timeout=15
)
finances = r3.json().get('result', {}).get('data', [])
print("【基本面(最近3期)】")
for row in finances:
    rev = (row.get('TOTAL_OPERATE_INCOME') or 0) / 1e8
    profit = (row.get('PARENT_NETPROFIT') or 0) / 1e8
    print(f"  {row['SECURITY_CODE']} | 报告期:{row.get('REPORT_DATE','?')} | EPS:{row.get('BASIC_EPS','?')} | ROE:{row.get('WEIGHTAVG_ROE','?')}% | 营收:{rev:.2f}亿 | 净利润:{profit:.2f}亿")

print()
print("=== 完成 ===")
