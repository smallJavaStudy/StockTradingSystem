from fastapi import APIRouter, HTTPException, Query
import requests
import re
from core.config import EASTMONEY_HEADERS, QUOTE_API, KLINE_API, FINANCE_API, REQUEST_TIMEOUT

router = APIRouter(prefix="/api/stock", tags=["stock"])


def get_secid(code: str) -> str:
    """将6位代码转为东方财富secid: 0.000001 / 1.600519"""
    if code.startswith("6"):
        return f"1.{code}"
    return f"0.{code}"


# ==================== 实时行情 ====================

@router.get("/{code}/quote")
def fetch_quote(code: str):
    """拉取实时行情"""
    secid = get_secid(code)
    fields = "f43,f44,f45,f46,f47,f48,f50,f57,f58,f60,f116,f117,f170"
    try:
        r = requests.get(QUOTE_API, params={"secid": secid, "fields": fields},
                         headers=EASTMONEY_HEADERS, timeout=REQUEST_TIMEOUT)
        data = r.json().get("data", {})
        if not data:
            raise HTTPException(502, "东方财富行情接口返回空数据")

        result = {
            "code": data.get("f57", code),
            "name": data.get("f58", ""),
            "price": data.get("f43"),
            "open": data.get("f46"),
            "high": data.get("f44"),
            "low": data.get("f45"),
            "preClose": data.get("f60"),
            "volume": data.get("f47"),
            "amount": data.get("f48"),
            "changePct": data.get("f170"),
        }
        # 数值字段除100
        for key in ["price", "open", "high", "low", "preClose"]:
            if result[key] and isinstance(result[key], (int, float)):
                result[key] = result[key] / 100 if abs(result[key]) > 100 else result[key]
        return result
    except requests.RequestException as e:
        raise HTTPException(502, f"行情接口请求失败: {e}")


# ==================== 日K线 ====================

@router.get("/{code}/kline")
def fetch_kline(code: str, limit: int = Query(default=120, le=365)):
    """拉取日K线"""
    secid = get_secid(code)
    try:
        r = requests.get(KLINE_API, params={
            "secid": secid,
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57",
            "klt": 101, "fqt": 1, "end": "20500101", "lmt": limit
        }, headers=EASTMONEY_HEADERS, timeout=REQUEST_TIMEOUT)
        data = r.json().get("data", {})
        klines_raw = data.get("klines", [])
        if not klines_raw:
            raise HTTPException(502, "东方财富K线接口返回空数据")

        result = []
        for line in klines_raw:
            parts = line.split(",")
            result.append({
                "tradeDate": parts[0],
                "open": float(parts[1]),
                "close": float(parts[2]),
                "high": float(parts[3]),
                "low": float(parts[4]),
                "volume": int(parts[5]),
                "amount": float(parts[6]),
            })
        return result
    except requests.RequestException as e:
        raise HTTPException(502, f"K线接口请求失败: {e}")


# ==================== 基本面（最新一期年报） ====================

@router.get("/{code}/finance")
def fetch_finance(code: str):
    """拉取基本面财务数据"""
    try:
        r = requests.get(FINANCE_API, params={
            "reportName": "RPT_LICO_FN_CPD",
            "columns": "SECURITY_CODE,SECURITY_NAME_ABBR,NOTICE_DATE,BASIC_EPS,WEIGHTAVG_ROE,TOTAL_OPERATE_INCOME,PARENT_NETPROFIT",
            "filter": f'(SECURITY_CODE="{code}")',
            "pageSize": 8,
            "sortColumns": "NOTICE_DATE",
            "sortTypes": -1,
        }, headers={"Referer": "https://data.eastmoney.com/", "User-Agent": EASTMONEY_HEADERS["User-Agent"]},
           timeout=REQUEST_TIMEOUT)
        rows = r.json().get("result", {}).get("data")
        if not rows:
            raise HTTPException(502, "基本面接口返回空数据")

        result = []
        for row in rows:
            result.append({
                "code": row.get("SECURITY_CODE", code),
                "reportDate": (row.get("NOTICE_DATE", ""))[:10],
                "basicEps": row.get("BASIC_EPS"),
                "weightedRoe": row.get("WEIGHTAVG_ROE"),
                "totalRevenue": row.get("TOTAL_OPERATE_INCOME"),
                "netProfit": row.get("PARENT_NETPROFIT"),
            })
        return result
    except requests.RequestException as e:
        raise HTTPException(502, f"基本面接口请求失败: {e}")


# ==================== 一键拉取全部数据 ====================

@router.get("/{code}/fetch-all")
def fetch_all(code: str):
    """一键拉取：基本信息 + 行情 + K线 + 基本面"""
    secid = get_secid(code)

    # 行情
    quote = fetch_quote(code)

    # K线
    klines = fetch_kline(code)

    # 基本面
    finances = fetch_finance(code)

    return {
        "code": code,
        "name": quote.get("name", ""),
        "market": "SH" if code.startswith("6") else "SZ",
        "quote": quote,
        "klines": klines,
        "finances": finances,
    }
