"""个股股东数据抓取入库脚本（Task #23）
链路：AKShare → 清洗归一化 → MySQL(stock_db) 直写，不经过后端 API。

覆盖 2 类数据：
  top     十大股东/十大流通股东  stock_gdfx_top_10_em / stock_gdfx_free_top_10_em → stock_holder_top
  count   股东户数历史           stock_zh_a_gdhs_detail_em                        → stock_holder_count

用法：
  python fetch_holders.py --codes 300364,002821            # 抓最近 4 期十大股东 + 全部户数历史
  python fetch_holders.py --codes 300364 --periods 6       # 最近 6 期报告
  python fetch_holders.py --codes 300364 --only count      # 只抓户数

约定（与 fetch_market.py 一致）：
  - 每次 AKShare 请求间隔 ≥5s，失败指数退避重试（5s/10s/20s）
  - 幂等：两表均有唯一索引，INSERT ... ON DUPLICATE KEY UPDATE，绝不 DELETE
  - 报告期自动取最近 N 个季度末，某期无数据（未披露）跳过
  - 表结构对齐 JPA 实体（camelCase→snake_case），后端 ddl-auto=update 可平滑接管
"""
import argparse
import io
import sys
import time
import traceback
from datetime import date, datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import pandas as pd
import pymysql
import akshare as ak

# ======================== 配置 ========================
DB = dict(host="localhost", port=3306, user="root", password="root",
          database="stock_db", charset="utf8mb4", autocommit=False)

REQUEST_INTERVAL = 5          # 请求间隔(秒)
RETRY_BACKOFF = [5, 10, 20]   # 失败退避

TYPE_TOP10 = "TOP10"
TYPE_TOP10_FLOAT = "TOP10_FLOAT"

DDL = {
    "stock_holder_top": """
        CREATE TABLE IF NOT EXISTS stock_holder_top (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(6) NOT NULL,
          report_date DATE NOT NULL,
          holder_type VARCHAR(12) NOT NULL,
          holder_rank INT NOT NULL,
          holder_name VARCHAR(200) NOT NULL,
          holder_nature VARCHAR(50) DEFAULT NULL,
          shares BIGINT DEFAULT NULL,
          hold_ratio DECIMAL(10,4) DEFAULT NULL,
          change_desc VARCHAR(32) DEFAULT NULL,
          change_ratio DECIMAL(12,4) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY idx_holdertop_unique (code, report_date, holder_type, holder_rank),
          KEY idx_holdertop_code_date (code, report_date DESC)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """,
    "stock_holder_count": """
        CREATE TABLE IF NOT EXISTS stock_holder_count (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(6) NOT NULL,
          stat_date DATE NOT NULL,
          holder_count BIGINT DEFAULT NULL,
          prev_count BIGINT DEFAULT NULL,
          change_ratio DECIMAL(12,4) DEFAULT NULL,
          avg_hold_shares DECIMAL(20,2) DEFAULT NULL,
          avg_hold_value DECIMAL(20,2) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY idx_holdercnt_unique (code, stat_date),
          KEY idx_holdercnt_code_date (code, stat_date DESC)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """,
}


# ======================== 工具 ========================
def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def call_ak(name, fn):
    """调用 AKShare 接口：失败指数退避重试，成功/放弃后统一睡 REQUEST_INTERVAL"""
    last_err = None
    for attempt, backoff in enumerate([0] + RETRY_BACKOFF):
        if backoff:
            log(f"  重试 {attempt}/{len(RETRY_BACKOFF)}，退避 {backoff}s ...")
            time.sleep(backoff)
        try:
            df = fn()
            time.sleep(REQUEST_INTERVAL)
            return df
        except ValueError as e:
            # 东财接口对未披露报告期返回空表头，akshare 抛 Length mismatch：无数据，不重试
            if "Length mismatch" in str(e):
                time.sleep(REQUEST_INTERVAL)
                log(f"  [!] {name} 无数据（报告期未披露）")
                return None
            last_err = e
            log(f"  [X] {name} 调用失败: {type(e).__name__}: {e}")
        except Exception as e:  # noqa: BLE001 - 第三方接口异常类型不确定
            last_err = e
            log(f"  [X] {name} 调用失败: {type(e).__name__}: {e}")
    time.sleep(REQUEST_INTERVAL)
    log(f"  [X] {name} 重试耗尽，跳过。最后错误: {last_err}")
    return None


def sf(v):
    """安全转 float，NaN/None → None"""
    if v is None:
        return None
    try:
        if pd.isna(v):
            return None
    except (TypeError, ValueError):
        pass
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def si(v):
    f = sf(v)
    return int(f) if f is not None else None


def to_date(v):
    if v is None:
        return None
    if isinstance(v, datetime):
        return v.date()
    if isinstance(v, date):
        return v
    s = str(v).strip()
    for fmt in ("%Y-%m-%d", "%Y%m%d", "%Y/%m/%d"):
        try:
            return datetime.strptime(s[:10] if "-" in s or "/" in s else s, fmt).date()
        except ValueError:
            continue
    return None


def truncate(v, n):
    if v is None:
        return None
    s = str(v).strip()
    if not s or s == "nan":
        return None
    return s[:n]


def em_symbol(code):
    """6位代码 → 东财 symbol（sh/sz/bj 前缀）"""
    if code.startswith(("60", "68", "9")):
        return f"sh{code}"
    if code.startswith(("4", "8")):
        return f"bj{code}"
    return f"sz{code}"


def recent_report_dates(n, today=None):
    """最近 n 个季度末报告期（含尚未披露的最新一期，由调用侧容错跳过）"""
    d = today or date.today()
    quarter_ends = [(3, 31), (6, 30), (9, 30), (12, 31)]
    candidates = []
    y = d.year
    while len(candidates) < n:
        for m, dd in reversed(quarter_ends):
            qd = date(y, m, dd)
            if qd <= d:
                candidates.append(qd)
                if len(candidates) >= n:
                    break
        y -= 1
    return candidates[:n]


def ensure_tables(conn):
    with conn.cursor() as cur:
        for table, ddl in DDL.items():
            cur.execute(ddl)
    conn.commit()
    log(f"表结构就绪：{', '.join(DDL)}")


def upsert(conn, table, cols, rows, update_cols):
    if not rows:
        return 0
    placeholders = ",".join(["%s"] * len(cols))
    updates = ",".join(f"{c}=VALUES({c})" for c in update_cols)
    sql = (f"INSERT INTO {table} ({','.join(cols)}) VALUES ({placeholders}) "
           f"ON DUPLICATE KEY UPDATE {updates}")
    with conn.cursor() as cur:
        cur.executemany(sql, rows)
    conn.commit()
    return len(rows)


def table_count(conn, table, where=None, params=None):
    sql = f"SELECT COUNT(*) FROM {table}"
    if where:
        sql += f" WHERE {where}"
    with conn.cursor() as cur:
        cur.execute(sql, params or ())
        return cur.fetchone()[0]


NOW = lambda: datetime.now()  # noqa: E731

TOP_COLS = ["code", "report_date", "holder_type", "holder_rank", "holder_name",
            "holder_nature", "shares", "hold_ratio", "change_desc", "change_ratio", "update_time"]
TOP_UPDATE = [c for c in TOP_COLS if c not in ("code", "report_date", "holder_type", "holder_rank")]


# ======================== 各数据域 ========================
def rows_from_top_df(df, code, report_date, holder_type, ratio_col):
    """东财十大(流通)股东 DataFrame → stock_holder_top 行"""
    rows = []
    for _, r in df.iterrows():
        rows.append((
            code, report_date, holder_type, si(r["名次"]),
            truncate(r["股东名称"], 200),
            truncate(r.get("股东性质"), 50),
            si(r["持股数"]), sf(r[ratio_col]),
            truncate(r["增减"], 32), sf(r["变动比率"]), NOW(),
        ))
    return [r for r in rows if r[3] is not None and r[4]]


def fetch_holder_top(conn, code, periods):
    """十大股东 + 十大流通股东：最近 periods 个报告期，未披露期跳过"""
    symbol = em_symbol(code)
    total = 0
    for rd in recent_report_dates(periods):
        ymd = rd.strftime("%Y%m%d")

        df = call_ak(f"stock_gdfx_top_10_em {symbol} {ymd}",
                     lambda: ak.stock_gdfx_top_10_em(symbol=symbol, date=ymd))
        if df is not None and len(df):
            rows = rows_from_top_df(df, code, rd, TYPE_TOP10, "占总股本持股比例")
            n = upsert(conn, "stock_holder_top", TOP_COLS, rows, TOP_UPDATE)
            total += n
            log(f"  {code} 十大股东 {rd}: {n} 条入库")
        else:
            log(f"  [!] {code} 十大股东 {rd} 无数据（未披露或接口异常）")

        df2 = call_ak(f"stock_gdfx_free_top_10_em {symbol} {ymd}",
                      lambda: ak.stock_gdfx_free_top_10_em(symbol=symbol, date=ymd))
        if df2 is not None and len(df2):
            rows = rows_from_top_df(df2, code, rd, TYPE_TOP10_FLOAT, "占总流通股本持股比例")
            n = upsert(conn, "stock_holder_top", TOP_COLS, rows, TOP_UPDATE)
            total += n
            log(f"  {code} 十大流通股东 {rd}: {n} 条入库")
        else:
            log(f"  [!] {code} 十大流通股东 {rd} 无数据（未披露或接口异常）")
    return total


def fetch_holder_count(conn, code):
    """股东户数历史（东财单股全部历史）"""
    df = call_ak(f"stock_zh_a_gdhs_detail_em {code}",
                 lambda: ak.stock_zh_a_gdhs_detail_em(symbol=code))
    if df is None or not len(df):
        log(f"  [!] {code} 股东户数无数据")
        return 0
    cols = ["code", "stat_date", "holder_count", "prev_count", "change_ratio",
            "avg_hold_shares", "avg_hold_value", "update_time"]
    rows = []
    for _, r in df.iterrows():
        d = to_date(r["股东户数统计截止日"])
        if d is None:
            continue
        rows.append((
            code, d, si(r["股东户数-本次"]), si(r["股东户数-上次"]),
            sf(r["股东户数-增减比例"]), sf(r["户均持股数量"]), sf(r["户均持股市值"]), NOW(),
        ))
    n = upsert(conn, "stock_holder_count", cols, rows, [c for c in cols if c not in ("code", "stat_date")])
    log(f"  {code} 股东户数 {n} 条入库（{rows[0][1]} ~ {rows[-1][1]}）")
    return n


# ======================== 主流程 ========================
def main():
    p = argparse.ArgumentParser(description="个股股东数据抓取入库（AKShare → MySQL）")
    p.add_argument("--codes", required=True, help="6位股票代码，逗号分隔，如 300364,002821")
    p.add_argument("--periods", type=int, default=4, help="十大股东抓取最近 N 个报告期，缺省 4")
    p.add_argument("--only", help="仅抓取指定域，逗号分隔：top,count")
    args = p.parse_args()

    codes = [c.strip() for c in args.codes.split(",") if c.strip()]
    only = {s.strip() for s in args.only.split(",")} if args.only else {"top", "count"}

    print("=" * 66)
    log(f"股东数据抓取开始 | codes={codes} | periods={args.periods} | 数据域={sorted(only)} | akshare={ak.__version__}")
    print("=" * 66)

    conn = pymysql.connect(**DB)
    stats = {}
    try:
        ensure_tables(conn)
        for code in codes:
            log(f"[{code}] 开始")
            try:
                if "top" in only:
                    stats[f"{code} 十大股东"] = fetch_holder_top(conn, code, args.periods)
                if "count" in only:
                    stats[f"{code} 股东户数"] = fetch_holder_count(conn, code)
            except Exception as e:  # noqa: BLE001
                conn.rollback()
                stats[f"{code}"] = f"失败: {type(e).__name__}: {e}"
                log(f"  [X] {code} 处理异常")
                traceback.print_exc(limit=3)

        print("=" * 66)
        log("入库统计（本次写入行数）")
        for k, v in stats.items():
            log(f"  {k}: {v}")
        log("各表总行数")
        for t in DDL:
            log(f"  {t}: {table_count(conn, t)}")
        print("=" * 66)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
