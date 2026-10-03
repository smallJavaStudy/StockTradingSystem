"""市场数据日期覆盖度体检（只读）"""
import io
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import pymysql

conn = pymysql.connect(host="localhost", user="root", password="root", database="stock_db", charset="utf8mb4")
with conn.cursor() as cur:
    cur.execute("SELECT market, COUNT(*), MIN(trade_date), MAX(trade_date) FROM stock_margin_daily GROUP BY market")
    print("两融按市场:", cur.fetchall())
    for t in ("stock_zt_pool", "stock_lhb_detail", "stock_block_trade", "stock_north_flow"):
        cur.execute(f"SELECT trade_date, COUNT(*) FROM {t} GROUP BY 1 ORDER BY 1 DESC LIMIT 5")
        print(f"{t} 最近日期分布:", cur.fetchall())
conn.close()
