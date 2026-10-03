"""AKShare 股东接口探针：核验接口可用性与列名（Task #23 预研，不入库）"""
import io
import sys
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import akshare as ak

print("akshare", ak.__version__, flush=True)

def probe(name, fn):
    print("=" * 60, flush=True)
    print(f"[{name}]", flush=True)
    try:
        df = fn()
        print("columns:", list(df.columns), flush=True)
        print(df.head(12).to_string(), flush=True)
        print("rows:", len(df), flush=True)
    except Exception as e:
        print(f"FAILED: {type(e).__name__}: {e}", flush=True)
    time.sleep(5)

# 十大流通股东（东财，需 sh/sz 前缀 + 报告期）
probe("stock_gdfx_free_top_10_em sz300364 20260331",
      lambda: ak.stock_gdfx_free_top_10_em(symbol="sz300364", date="20260331"))
# 十大股东（东财）
probe("stock_gdfx_top_10_em sz300364 20260331",
      lambda: ak.stock_gdfx_top_10_em(symbol="sz300364", date="20260331"))
# 股东户数历史（东财，单股全部历史）
probe("stock_zh_a_gdhs_detail_em 300364",
      lambda: ak.stock_zh_a_gdhs_detail_em(symbol="300364"))
