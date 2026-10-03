"""市场数据入库核验：行数、日期分布、单位量级抽样
用法：python verify_market_data.py
"""
import io
import sys
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import pymysql

DB = dict(host="localhost", port=3306, user="root", password="root",
          database="stock_db", charset="utf8mb4")

QUERIES = [
    ("各表行数", """
        SELECT 'stock_zt_pool' t, COUNT(*) c FROM stock_zt_pool
        UNION ALL SELECT 'stock_lhb_detail', COUNT(*) FROM stock_lhb_detail
        UNION ALL SELECT 'stock_north_flow', COUNT(*) FROM stock_north_flow
        UNION ALL SELECT 'stock_margin_daily', COUNT(*) FROM stock_margin_daily
        UNION ALL SELECT 'stock_block_trade', COUNT(*) FROM stock_block_trade
    """),
    ("涨停池按日期/类型", "SELECT trade_date, pool_type, COUNT(*) FROM stock_zt_pool GROUP BY 1,2 ORDER BY 1 DESC,2"),
    ("涨停池连板前5", """SELECT code,name,trade_date,close_price,change_pct,limit_up_days,first_time,last_time,
        open_times,ROUND(amount/1e8,2) amount_yi,turnover_rate,industry,zt_stat FROM stock_zt_pool
        WHERE pool_type='TODAY' ORDER BY limit_up_days DESC LIMIT 5"""),
    ("龙虎榜前3", """SELECT code,name,trade_date,LEFT(rank_reason,20) rr,ROUND(buy_amount/1e4,1) buy_wan,
        ROUND(net_amount/1e4,1) net_wan,change_pct FROM stock_lhb_detail ORDER BY net_amount DESC LIMIT 3"""),
    ("北向资金最新5", "SELECT trade_date,net_flow,accum_flow FROM stock_north_flow ORDER BY trade_date DESC LIMIT 5"),
    ("两融最新6", """SELECT trade_date,market,ROUND(financing_balance/1e8,2) fin_yi,
        ROUND(securities_balance/1e8,2) sec_yi,ROUND(total_balance/1e8,2) tot_yi
        FROM stock_margin_daily ORDER BY trade_date DESC,market LIMIT 6"""),
    ("大宗交易前3", """SELECT code,name,trade_date,price,close_price,volume,ROUND(amount/1e4,1) amt_wan,
        premium_rate,LEFT(buyer_branch,14) buyer FROM stock_block_trade ORDER BY amount DESC LIMIT 3"""),
    ("空值体检(涨停池)", """SELECT SUM(close_price IS NULL) close_null, SUM(change_pct IS NULL) pct_null,
        SUM(limit_up_days IS NULL) days_null, SUM(industry IS NULL) ind_null, SUM(reason IS NULL) reason_null
        FROM stock_zt_pool"""),
]


def main():
    conn = pymysql.connect(**DB)
    print(f"==== 市场数据核验 {datetime.now():%Y-%m-%d %H:%M:%S} ====")
    with conn.cursor() as cur:
        for title, sql in QUERIES:
            print(f"\n-- {title} --")
            cur.execute(sql)
            cols = [d[0] for d in cur.description]
            print(" | ".join(cols))
            for row in cur.fetchall():
                print(" | ".join("NULL" if v is None else str(v) for v in row))
    conn.close()


if __name__ == "__main__":
    main()
