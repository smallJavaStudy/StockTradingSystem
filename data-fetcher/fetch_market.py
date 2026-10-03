"""市场级数据抓取入库脚本（Task #19）
链路：AKShare → 清洗归一化 → MySQL(stock_db) 直写，不经过后端 API。

覆盖 5 类数据：
  zt      涨停股池   stock_zt_pool_em(今日) + stock_zt_pool_previous_em(昨日)  → stock_zt_pool
  lhb     龙虎榜     stock_lhb_detail_em                                      → stock_lhb_detail
  north   北向资金   stock_hsgt_hist_em(symbol="北向资金")                     → stock_north_flow
  margin  融资融券   stock_margin_sse + stock_margin_szse                      → stock_margin_daily
  block   大宗交易   stock_dzjy_mrmx(symbol="A股")                             → stock_block_trade

用法：
  python fetch_market.py                          # 抓取最近交易日全部 5 类
  python fetch_market.py --date 20260728          # 指定交易日
  python fetch_market.py --only zt,lhb            # 只抓部分
  python fetch_market.py --margin-days 10 --north-days 120

约定：
  - 每次 AKShare 请求间隔 ≥5s，失败指数退避重试（5s/10s/20s）
  - 幂等：有唯一索引的表用 INSERT ... ON DUPLICATE KEY UPDATE；
          无唯一索引的表（龙虎榜/大宗交易）先按业务键查已存在行再插差集，绝不 DELETE
  - 单位归一化：涨停/龙虎榜/大宗交易金额→元；两融→元；北向→亿元
  - 表不存在时用 CREATE TABLE IF NOT EXISTS 建表，列名与类型对齐 JPA 实体
    （Hibernate 命名策略 camelCase→snake_case），后端 ddl-auto=update 可平滑接管
"""
import argparse
import io
import sys
import time
import traceback
from datetime import date, datetime, timedelta

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import pandas as pd
import pymysql
import akshare as ak

# ======================== 配置 ========================
DB = dict(host="localhost", port=3306, user="root", password="root",
          database="stock_db", charset="utf8mb4", autocommit=False)

REQUEST_INTERVAL = 5          # 请求间隔(秒)
RETRY_BACKOFF = [5, 10, 20]   # 失败退避

POOL_TODAY = "TODAY"
POOL_PREVIOUS = "PREVIOUS"

DDL = {
    "stock_zt_pool": """
        CREATE TABLE IF NOT EXISTS stock_zt_pool (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(6) NOT NULL,
          name VARCHAR(32) DEFAULT NULL,
          trade_date DATE NOT NULL,
          pool_type VARCHAR(10) NOT NULL,
          close_price DECIMAL(12,2) DEFAULT NULL,
          change_pct DECIMAL(10,2) DEFAULT NULL,
          limit_up_days INT DEFAULT NULL,
          first_time VARCHAR(8) DEFAULT NULL,
          last_time VARCHAR(8) DEFAULT NULL,
          open_times INT DEFAULT NULL,
          amount DECIMAL(20,2) DEFAULT NULL,
          turnover_rate DECIMAL(10,2) DEFAULT NULL,
          industry VARCHAR(32) DEFAULT NULL,
          reason VARCHAR(200) DEFAULT NULL,
          zt_stat VARCHAR(16) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY idx_ztpool_code_date_type_unique (code, trade_date, pool_type),
          KEY idx_ztpool_code_date (code, trade_date DESC),
          KEY idx_ztpool_date_type (trade_date, pool_type)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """,
    "stock_lhb_detail": """
        CREATE TABLE IF NOT EXISTS stock_lhb_detail (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(6) NOT NULL,
          name VARCHAR(32) DEFAULT NULL,
          trade_date DATE NOT NULL,
          rank_reason VARCHAR(200) DEFAULT NULL,
          buy_amount DECIMAL(20,2) DEFAULT NULL,
          sell_amount DECIMAL(20,2) DEFAULT NULL,
          net_amount DECIMAL(20,2) DEFAULT NULL,
          total_amount DECIMAL(20,2) DEFAULT NULL,
          change_pct DECIMAL(10,2) DEFAULT NULL,
          close_price DECIMAL(12,2) DEFAULT NULL,
          interpretation VARCHAR(200) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          KEY idx_lhb_code_date (code, trade_date DESC),
          KEY idx_lhb_date (trade_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """,
    "stock_north_flow": """
        CREATE TABLE IF NOT EXISTS stock_north_flow (
          id BIGINT NOT NULL AUTO_INCREMENT,
          trade_date DATE NOT NULL,
          net_flow DECIMAL(16,4) DEFAULT NULL,
          accum_flow DECIMAL(18,4) DEFAULT NULL,
          buy_amount DECIMAL(16,4) DEFAULT NULL,
          sell_amount DECIMAL(16,4) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY idx_northflow_date_unique (trade_date)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """,
    "stock_margin_daily": """
        CREATE TABLE IF NOT EXISTS stock_margin_daily (
          id BIGINT NOT NULL AUTO_INCREMENT,
          trade_date DATE NOT NULL,
          market VARCHAR(4) NOT NULL,
          financing_balance DECIMAL(22,2) DEFAULT NULL,
          financing_buy_amount DECIMAL(22,2) DEFAULT NULL,
          securities_balance DECIMAL(22,2) DEFAULT NULL,
          total_balance DECIMAL(22,2) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY idx_margin_date_market_unique (trade_date, market),
          KEY idx_margin_date (trade_date DESC)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """,
    "stock_block_trade": """
        CREATE TABLE IF NOT EXISTS stock_block_trade (
          id BIGINT NOT NULL AUTO_INCREMENT,
          code VARCHAR(6) NOT NULL,
          name VARCHAR(32) DEFAULT NULL,
          trade_date DATE NOT NULL,
          price DECIMAL(12,2) DEFAULT NULL,
          close_price DECIMAL(12,2) DEFAULT NULL,
          volume BIGINT DEFAULT NULL,
          amount DECIMAL(20,2) DEFAULT NULL,
          premium_rate DECIMAL(10,2) DEFAULT NULL,
          buyer_branch VARCHAR(200) DEFAULT NULL,
          seller_branch VARCHAR(200) DEFAULT NULL,
          update_time DATETIME(6) NOT NULL,
          PRIMARY KEY (id),
          KEY idx_blocktrade_code_date (code, trade_date DESC),
          KEY idx_blocktrade_date (trade_date)
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
        if isinstance(v, float) and pd.isna(v):
            return None
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
    """date/datetime/str(20260728|2026-07-28) → datetime.date"""
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


def fmt_time(v):
    """'092500' → '09:25:00'；空值 → None"""
    if v is None:
        return None
    s = str(v).strip()
    if not s or s in ("nan", "None", "-"):
        return None
    s = s.split(".")[0].zfill(6)
    if len(s) == 6 and s.isdigit():
        return f"{s[0:2]}:{s[2:4]}:{s[4:6]}"
    return s[:8]


def truncate(v, n):
    if v is None:
        return None
    s = str(v).strip()
    if not s or s == "nan":
        return None
    return s[:n]


def ensure_tables(conn):
    with conn.cursor() as cur:
        for table, ddl in DDL.items():
            cur.execute(ddl)
    conn.commit()
    log(f"表结构就绪：{', '.join(DDL)}")


def upsert(conn, table, cols, rows, update_cols):
    """有唯一索引的表：INSERT ... ON DUPLICATE KEY UPDATE"""
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


def insert_missing(conn, table, cols, rows, key_cols, key_of):
    """无唯一索引的表：按业务键过滤已存在行后插入差集（不做任何 DELETE）"""
    if not rows:
        return 0, 0
    dates = sorted({r[cols.index("trade_date")] for r in rows})
    existing = set()
    with conn.cursor() as cur:
        in_clause = ",".join(["%s"] * len(dates))
        cur.execute(f"SELECT {','.join(key_cols)} FROM {table} WHERE trade_date IN ({in_clause})", dates)
        for row in cur.fetchall():
            existing.add(key_of(row))
    to_insert = [r for r in rows if key_of(tuple(r[cols.index(c)] for c in key_cols)) not in existing]
    skipped = len(rows) - len(to_insert)
    if to_insert:
        placeholders = ",".join(["%s"] * len(cols))
        with conn.cursor() as cur:
            cur.executemany(f"INSERT INTO {table} ({','.join(cols)}) VALUES ({placeholders})", to_insert)
        conn.commit()
    return len(to_insert), skipped


def table_count(conn, table, where=None, params=None):
    sql = f"SELECT COUNT(*) FROM {table}"
    if where:
        sql += f" WHERE {where}"
    with conn.cursor() as cur:
        cur.execute(sql, params or ())
        return cur.fetchone()[0]


NOW = lambda: datetime.now()  # noqa: E731


# ======================== 各数据域 ========================
def fetch_zt_pool(conn, day):
    """涨停股池：今日池 + 昨日池"""
    ymd = day.strftime("%Y%m%d")
    total = 0

    df = call_ak("stock_zt_pool_em", lambda: ak.stock_zt_pool_em(date=ymd))
    if df is not None and len(df):
        cols = ["code", "name", "trade_date", "pool_type", "close_price", "change_pct", "limit_up_days",
                "first_time", "last_time", "open_times", "amount", "turnover_rate", "industry",
                "zt_stat", "update_time"]
        rows = [(
            truncate(r["代码"], 6), truncate(r["名称"], 32), day, POOL_TODAY,
            sf(r["最新价"]), sf(r["涨跌幅"]), si(r["连板数"]),
            fmt_time(r["首次封板时间"]), fmt_time(r["最后封板时间"]), si(r["炸板次数"]),
            sf(r["成交额"]), sf(r["换手率"]), truncate(r["所属行业"], 32),
            truncate(r["涨停统计"], 16), NOW(),
        ) for _, r in df.iterrows()]
        n = upsert(conn, "stock_zt_pool", cols, rows, [c for c in cols if c not in ("code", "trade_date", "pool_type")])
        total += n
        log(f"  今日涨停池 {n} 条入库（连板≥2: {sum(1 for r in rows if (r[6] or 0) >= 2)} 只）")
    else:
        log("  [!] 今日涨停池无数据")

    df2 = call_ak("stock_zt_pool_previous_em", lambda: ak.stock_zt_pool_previous_em(date=ymd))
    if df2 is not None and len(df2):
        cols = ["code", "name", "trade_date", "pool_type", "close_price", "change_pct", "limit_up_days",
                "first_time", "amount", "turnover_rate", "industry", "zt_stat", "update_time"]
        rows = [(
            truncate(r["代码"], 6), truncate(r["名称"], 32), day, POOL_PREVIOUS,
            sf(r["最新价"]), sf(r["涨跌幅"]), si(r["昨日连板数"]),
            fmt_time(r["昨日封板时间"]), sf(r["成交额"]), sf(r["换手率"]),
            truncate(r["所属行业"], 32), truncate(r["涨停统计"], 16), NOW(),
        ) for _, r in df2.iterrows()]
        n = upsert(conn, "stock_zt_pool", cols, rows, [c for c in cols if c not in ("code", "trade_date", "pool_type")])
        total += n
        log(f"  昨日涨停池 {n} 条入库")
    else:
        log("  [!] 昨日涨停池无数据")
    return total


def fetch_lhb(conn, day):
    """龙虎榜明细（东财）"""
    ymd = day.strftime("%Y%m%d")
    df = call_ak("stock_lhb_detail_em", lambda: ak.stock_lhb_detail_em(start_date=ymd, end_date=ymd))
    if df is None or not len(df):
        log("  [!] 龙虎榜无数据")
        return 0
    cols = ["code", "name", "trade_date", "rank_reason", "buy_amount", "sell_amount", "net_amount",
            "total_amount", "change_pct", "close_price", "interpretation", "update_time"]
    rows = [(
        truncate(r["代码"], 6), truncate(r["名称"], 32), to_date(r["上榜日"]),
        truncate(r["上榜原因"], 200), sf(r["龙虎榜买入额"]), sf(r["龙虎榜卖出额"]),
        sf(r["龙虎榜净买额"]), sf(r["龙虎榜成交额"]), sf(r["涨跌幅"]), sf(r["收盘价"]),
        truncate(r["解读"], 200), NOW(),
    ) for _, r in df.iterrows()]
    inserted, skipped = insert_missing(
        conn, "stock_lhb_detail", cols, rows,
        ["code", "trade_date", "rank_reason"],
        lambda t: (str(t[0]), to_date(t[1]), str(t[2] or "")),
    )
    log(f"  龙虎榜 {inserted} 条入库（跳过已存在 {skipped} 条）")
    return inserted


def fetch_north(conn, days):
    """北向资金历史（仅入库有效行；2024-08-19 起交易所停止披露逐日净买额）"""
    df = call_ak("stock_hsgt_hist_em", lambda: ak.stock_hsgt_hist_em(symbol="北向资金"))
    if df is None or not len(df):
        log("  [!] 北向资金无数据")
        return 0
    valid = df.dropna(subset=["当日成交净买额"])
    if not len(valid):
        log("  [!] 北向资金全部为 NaN（接口已停更）")
        return 0
    tail = valid.tail(days)
    cols = ["trade_date", "net_flow", "accum_flow", "buy_amount", "sell_amount", "update_time"]
    rows = []
    for _, r in tail.iterrows():
        accum = sf(r["历史累计净买额"])           # 接口单位：万亿元
        rows.append((
            to_date(r["日期"]), sf(r["当日成交净买额"]),
            accum * 10000 if accum is not None else None,  # → 亿元
            sf(r["买入成交额"]), sf(r["卖出成交额"]), NOW(),
        ))
    n = upsert(conn, "stock_north_flow", cols, rows, [c for c in cols if c != "trade_date"])
    log(f"  北向资金 {n} 条入库（{rows[0][0]} ~ {rows[-1][0]}；有效数据截至 {to_date(valid.iloc[-1]['日期'])}）")
    return n


def fetch_margin(conn, day, days):
    """融资融券：上交所（区间）+ 深交所（逐日）"""
    cols = ["trade_date", "market", "financing_balance", "financing_buy_amount",
            "securities_balance", "total_balance", "update_time"]
    upd = [c for c in cols if c not in ("trade_date", "market")]
    total = 0

    start = (day - timedelta(days=max(days * 2, 20))).strftime("%Y%m%d")
    end = day.strftime("%Y%m%d")
    sse = call_ak("stock_margin_sse", lambda: ak.stock_margin_sse(start_date=start, end_date=end))
    sse_dates = []
    if sse is not None and len(sse):
        rows = []
        for _, r in sse.iterrows():
            d = to_date(r["信用交易日期"])
            if d is None:
                continue
            sse_dates.append(d)
            # SSE 原始单位：元
            rows.append((d, "SH", sf(r["融资余额"]), sf(r["融资买入额"]),
                         sf(r["融券余量金额"]), sf(r["融资融券余额"]), NOW()))
        n = upsert(conn, "stock_margin_daily", cols, rows, upd)
        total += n
        log(f"  两融-上交所 {n} 条入库（{min(sse_dates)} ~ {max(sse_dates)}）")
    else:
        log("  [!] 两融-上交所无数据")

    # 深交所接口仅支持单日查询：对最近 days 个交易日逐日抓取，已入库的日期跳过
    targets = sorted(set(sse_dates), reverse=True)[:days] if sse_dates else [day]
    done = 0
    for d in targets:
        if table_count(conn, "stock_margin_daily", "trade_date=%s AND market='SZ'", (d,)):
            continue
        df = call_ak("stock_margin_szse", lambda dd=d: ak.stock_margin_szse(date=dd.strftime("%Y%m%d")))
        if df is None or not len(df):
            log(f"  [!] 两融-深交所 {d} 无数据")
            continue
        r = df.iloc[0]
        E8 = 1e8  # SZSE 原始单位：亿元 → 元
        rows = [(d, "SZ",
                 (sf(r["融资余额"]) or 0) * E8, (sf(r["融资买入额"]) or 0) * E8,
                 (sf(r["融券余额"]) or 0) * E8, (sf(r["融资融券余额"]) or 0) * E8, NOW())]
        total += upsert(conn, "stock_margin_daily", cols, rows, upd)
        done += 1
    log(f"  两融-深交所 {done} 个交易日入库")
    return total


def fetch_block_trade(conn, day):
    """大宗交易每日明细"""
    ymd = day.strftime("%Y%m%d")
    df = call_ak("stock_dzjy_mrmx",
                 lambda: ak.stock_dzjy_mrmx(symbol="A股", start_date=ymd, end_date=ymd))
    if df is None or not len(df):
        log("  [!] 大宗交易无数据")
        return 0
    cols = ["code", "name", "trade_date", "price", "close_price", "volume", "amount",
            "premium_rate", "buyer_branch", "seller_branch", "update_time"]
    rows = []
    for _, r in df.iterrows():
        prem = sf(r["折溢率"])
        rows.append((
            truncate(r["证券代码"], 6), truncate(r["证券简称"], 32), to_date(r["交易日期"]),
            sf(r["成交价"]), sf(r["收盘价"]), si(r["成交量"]), sf(r["成交额"]),
            prem * 100 if prem is not None else None,   # 接口为小数比率 → %
            truncate(r["买方营业部"], 200), truncate(r["卖方营业部"], 200), NOW(),
        ))
    inserted, skipped = insert_missing(
        conn, "stock_block_trade", cols, rows,
        ["code", "trade_date", "price", "volume", "buyer_branch"],
        lambda t: (str(t[0]), to_date(t[1]), f"{sf(t[2]):.2f}" if sf(t[2]) is not None else None,
                   si(t[3]), str(t[4] or "")),
    )
    log(f"  大宗交易 {inserted} 条入库（跳过已存在 {skipped} 条）")
    return inserted


# ======================== 主流程 ========================
def resolve_trade_date(arg_date):
    if arg_date:
        d = to_date(arg_date)
        if d is None:
            log(f"[X] 无法解析日期: {arg_date}")
            sys.exit(1)
        return d
    # 缺省：今天（若为周末回退到最近周五）；若当日无涨停数据由调用方 --date 指定
    d = date.today()
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d


def main():
    p = argparse.ArgumentParser(description="市场级数据抓取入库（AKShare → MySQL）")
    p.add_argument("--date", help="交易日 YYYYMMDD 或 YYYY-MM-DD，缺省今日/最近工作日")
    p.add_argument("--only", help="仅抓取指定域，逗号分隔：zt,lhb,north,margin,block")
    p.add_argument("--north-days", type=int, default=120, help="北向资金入库最近 N 个有效交易日")
    p.add_argument("--margin-days", type=int, default=10, help="深交所两融逐日抓取的交易日数")
    args = p.parse_args()

    day = resolve_trade_date(args.date)
    only = {s.strip() for s in args.only.split(",")} if args.only else {"zt", "lhb", "north", "margin", "block"}

    print("=" * 66)
    log(f"市场数据抓取开始 | 交易日={day} | 数据域={sorted(only)} | akshare={ak.__version__}")
    print("=" * 66)

    conn = pymysql.connect(**DB)
    stats = {}
    try:
        ensure_tables(conn)
        tasks = [
            ("zt", "涨停股池", lambda: fetch_zt_pool(conn, day)),
            ("lhb", "龙虎榜", lambda: fetch_lhb(conn, day)),
            ("north", "北向资金", lambda: fetch_north(conn, args.north_days)),
            ("margin", "融资融券", lambda: fetch_margin(conn, day, args.margin_days)),
            ("block", "大宗交易", lambda: fetch_block_trade(conn, day)),
        ]
        for key, label, fn in tasks:
            if key not in only:
                continue
            log(f"[{label}] 开始")
            try:
                stats[label] = fn()
            except Exception as e:  # noqa: BLE001
                conn.rollback()
                stats[label] = f"失败: {type(e).__name__}: {e}"
                log(f"  [X] {label} 处理异常")
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
