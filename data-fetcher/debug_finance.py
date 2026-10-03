"""调试东财财务接口：打印原始响应，尝试多种 reportName/filter 组合"""
import sys
import io
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

CODE = sys.argv[1] if len(sys.argv) > 1 else "300364"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

attempts = [
    {
        "name": "RPT_LICO_FN_CPD + NOTICE_DATE",
        "params": {
            "reportName": "RPT_LICO_FN_CPD",
            "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORT_DATE",
            "filter": f'(SECURITY_CODE="{CODE}")',
            "pageSize": 8,
            "sortColumns": "NOTICE_DATE",
            "sortTypes": -1,
        },
    },
    {
        "name": "RPT_LICO_FN_CPD + REPORT_DATE 排序",
        "params": {
            "reportName": "RPT_LICO_FN_CPD",
            "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORT_DATE",
            "filter": f'(SECURITY_CODE="{CODE}")',
            "pageSize": 8,
            "sortColumns": "REPORT_DATE",
            "sortTypes": -1,
        },
    },
    {
        "name": "RPT_LICO_FN_CPD_NEW 业绩快报",
        "params": {
            "reportName": "RPT_LICO_FN_CPD_NEW",
            "columns": "SECURITY_CODE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT,REPORT_DATE",
            "filter": f'(SECURITY_CODE="{CODE}")',
            "pageSize": 8,
            "sortColumns": "REPORT_DATE",
            "sortTypes": -1,
        },
    },
]

for a in attempts:
    print("=" * 60)
    print("尝试:", a["name"])
    try:
        r = requests.get(
            "https://datacenter.eastmoney.com/securities/api/data/v1/get",
            params=a["params"],
            headers={"Referer": "https://data.eastmoney.com/", "User-Agent": UA},
            timeout=15,
        )
        print("HTTP:", r.status_code)
        print("BODY:", r.text[:600])
    except Exception as e:
        print("EXC:", e)
