"""用 AKShare 更新个股数据（行情/K线/资金流）→ POST 到后端 8080
用法: python import_akshare.py <6位代码>
"""
import sys
import io
import time
import akshare as ak
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

BACKEND = "http://localhost:8080/api/stock"
HEADERS = {"Content-Type": "application/json"}


def main():
    if len(sys.argv) < 2:
        print("用法: python import_akshare.py <6位代码>")
        sys.exit(1)
    code = sys.argv[1]
    print(f"==== {code} AKShare 数据更新 {time.strftime('%Y-%m-%d %H:%M:%S')} ====")

    # 1) 实时行情（akshare 个股实时行情）
    print("[1/3] 实时行情...")
    try:
        df_quote = ak.stock_zh_a_spot_em()
        row = df_quote[df_quote["代码"] == code]
        if not row.empty:
            r = row.iloc[0]
            quote = {
                "code": code,
                "name": str(r.get("名称", "")),
                "price": float(r.get("最新价", 0)) if r.get("最新价") else None,
                "changePercent": float(r.get("涨跌幅", 0)) if r.get("涨跌幅") else None,
                "changeAmount": float(r.get("涨跌额", 0)) if r.get("涨跌额") else None,
                "volume": int(float(r.get("成交量", 0))) if r.get("成交量") else None,
                "amount": float(r.get("成交额", 0)) if r.get("成交额") else None,
                "open": float(r.get("今开", 0)) if r.get("今开") else None,
                "high": float(r.get("最高", 0)) if r.get("最高") else None,
                "low": float(r.get("最低", 0)) if r.get("最低") else None,
                "preClose": float(r.get("昨收", 0)) if r.get("昨收") else None,
                "turnover": float(r.get("换手率", 0)) if r.get("换手率") else None,
                "pe": float(r.get("市盈率-动态", 0)) if r.get("市盈率-动态") else None,
                "pb": float(r.get("市净率", 0)) if r.get("市净率") else None,
                "totalMarketCap": float(r.get("总市值", 0)) if r.get("总市值") else None,
            }
            resp = requests.post(f"{BACKEND}/{code}/quote", json=quote, headers=HEADERS, timeout=30)
            print(f"  行情入库 OK 最新价:{quote['price']} 涨跌:{quote['changePercent']}%")
        else:
            print(f"  [X] AKShare 未找到 {code} 行情")
    except Exception as e:
        print(f"  [X] 行情失败: {e}")

    # 2) K线（日K，最近120个交易日）
    print("[2/3] 日K线...")
    time.sleep(3)
    try:
        df_k = ak.stock_zh_a_hist(symbol=code, period="daily", start_date="20250101", adjust="qfq")
        if df_k is not None and not df_k.empty:
            df_k = df_k.tail(120)
            rows = []
            for _, r in df_k.iterrows():
                rows.append({
                    "tradeDate": str(r["日期"]),
                    "open": float(r["开盘"]), "close": float(r["收盘"]),
                    "high": float(r["最高"]), "low": float(r["最低"]),
                    "volume": int(float(r["成交量"])),
                    "amount": float(r["成交额"]),
                    "amplitude": float(r["振幅"]),
                    "pctChange": float(r["涨跌幅"]),
                    "priceChange": float(r["涨跌额"]),
                    "turnover": float(r["换手率"]),
                })
            resp = requests.post(f"{BACKEND}/{code}/kline", json=rows, headers=HEADERS, timeout=30)
            print(f"  K线入库 {len(resp.json())} 条 ({rows[0]['tradeDate']} ~ {rows[-1]['tradeDate']})")
        else:
            print(f"  [X] K线为空")
    except Exception as e:
        print(f"  [X] K线失败: {e}")

    # 3) 资金流（AKShare 个股资金流向）
    print("[3/3] 资金流...")
    time.sleep(3)
    try:
        df_f = ak.stock_individual_fund_flow(stock=code, market="sz" if code.startswith(("0","3")) else "sh")
        if df_f is not None and not df_f.empty:
            df_f = df_f.head(60)
            rows = []
            for _, r in df_f.iterrows():
                # AKShare 返回列: 日期,主力净流入,小单净流入,中单净流入,大单净流入,超大单净流入,主力净占比,小单净占比,中单净占比,大单净占比,超大单净占比
                rows.append({
                    "tradeDate": str(r.iloc[0]),
                    "mainNetInflow": float(r.iloc[1]) if r.iloc[1] else None,
                    "smallNetInflow": float(r.iloc[2]) if r.iloc[2] else None,
                    "mediumNetInflow": float(r.iloc[3]) if r.iloc[3] else None,
                    "largeNetInflow": float(r.iloc[4]) if r.iloc[4] else None,
                    "superLargeNetInflow": float(r.iloc[5]) if r.iloc[5] else None,
                    "mainNetRatio": float(r.iloc[6].replace("%","")) if isinstance(r.iloc[6], str) and r.iloc[6].endswith("%") else (float(r.iloc[6]) if r.iloc[6] else None),
                })
            resp = requests.post(f"{BACKEND}/{code}/fundflow", json=rows, headers=HEADERS, timeout=30)
            print(f"  资金流入库 {len(resp.json())} 条 ({rows[0]['tradeDate']} ~ {rows[-1]['tradeDate']})")
            tail = rows[:3]
            for t in tail:
                mn = (t["mainNetInflow"] or 0) / 1e8
                print(f"    {t['tradeDate']} 主力净流入 {mn:+.2f}亿")
        else:
            print(f"  [X] 资金流为空")
    except Exception as e:
        print(f"  [X] 资金流失败: {e}")

    print(f"==== {code} AKShare 更新完成 ====")


if __name__ == "__main__":
    main()
