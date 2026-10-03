"""AKShare 接口可用性探针（Task #19 前置核验）
逐个调用候选接口，打印列名与首行样例，间隔 5s。
用法：python probe_akshare.py
"""
import sys
import io
import time
import traceback

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import akshare as ak

print(f"akshare version: {ak.__version__}")

# 探测目标：接口名 -> (调用 lambda, 说明)
PROBES = [
    ("stock_zt_pool_em", lambda: ak.stock_zt_pool_em(date="20260728"), "今日涨停股池"),
    ("stock_zt_pool_previous_em", lambda: ak.stock_zt_pool_previous_em(date="20260728"), "昨日涨停股池"),
    ("stock_lhb_detail_em", lambda: ak.stock_lhb_detail_em(start_date="20260728", end_date="20260728"), "龙虎榜-东财"),
    ("stock_sina_lhb_detail_daily", lambda: ak.stock_sina_lhb_detail_daily(date="20260728"), "龙虎榜-新浪"),
    ("stock_hsgt_north_net_flow_in_em", lambda: ak.stock_hsgt_north_net_flow_in_em(symbol="北上"), "北向资金净流入"),
    ("stock_hsgt_fund_flow_summary_em", lambda: ak.stock_hsgt_fund_flow_summary_em(), "沪深港通资金流向汇总"),
    ("stock_margin_sse", lambda: ak.stock_margin_sse(start_date="20260720", end_date="20260728"), "融资融券-上交所"),
    ("stock_margin_szse", lambda: ak.stock_margin_szse(date="20260728"), "融资融券-深交所"),
    ("stock_dzjy_mrmx", lambda: ak.stock_dzjy_mrmx(symbol="A股", start_date="20260728", end_date="20260728"), "大宗交易每日明细"),
]


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, fn, desc in PROBES:
        if only and only != name:
            continue
        print(f"\n===== {name} ({desc}) =====")
        if not hasattr(ak, name):
            print("[X] 接口在当前 akshare 版本中不存在")
            continue
        try:
            df = fn()
            if df is None or len(df) == 0:
                print("[!] 返回空数据")
            else:
                print(f"[OK] {len(df)} 行")
                print("columns:", list(df.columns))
                print("first row:", df.iloc[0].to_dict())
        except Exception as e:
            print(f"[X] 调用异常: {type(e).__name__}: {e}")
            traceback.print_exc(limit=2)
        time.sleep(5)


if __name__ == "__main__":
    main()
