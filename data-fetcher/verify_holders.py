"""股东数据入库抽样核验（Task #23，只读查询）"""
import io
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import pymysql

conn = pymysql.connect(host="localhost", port=3306, user="root", password="root",
                       database="stock_db", charset="utf8mb4")
cur = conn.cursor()

print("== stock_holder_top 分组统计 ==")
cur.execute("SELECT code, holder_type, report_date, COUNT(*) FROM stock_holder_top "
            "GROUP BY code, holder_type, report_date ORDER BY code, holder_type, report_date DESC")
for r in cur.fetchall():
    print(r)

print("\n== 300364 最新期 TOP10 前5名 ==")
cur.execute("SELECT holder_rank, holder_name, holder_nature, shares, hold_ratio, change_desc, change_ratio "
            "FROM stock_holder_top WHERE code='300364' AND holder_type='TOP10' "
            "ORDER BY report_date DESC, holder_rank LIMIT 5")
for r in cur.fetchall():
    print(r)

print("\n== 002821 最新期 TOP10_FLOAT 前5名 ==")
cur.execute("SELECT holder_rank, holder_name, shares, hold_ratio, change_desc "
            "FROM stock_holder_top WHERE code='002821' AND holder_type='TOP10_FLOAT' "
            "ORDER BY report_date DESC, holder_rank LIMIT 5")
for r in cur.fetchall():
    print(r)

print("\n== 股东户数最新3条/股 ==")
for code in ("300364", "002821"):
    cur.execute("SELECT stat_date, holder_count, prev_count, change_ratio, avg_hold_value "
                "FROM stock_holder_count WHERE code=%s ORDER BY stat_date DESC LIMIT 3", (code,))
    print(code, cur.fetchall())

conn.close()
