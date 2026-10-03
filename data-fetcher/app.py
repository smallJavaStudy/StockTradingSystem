from fastapi import FastAPI
from routers.stock import router as stock_router

app = FastAPI(title="Stock Data Fetcher", version="1.0.0")
app.include_router(stock_router)

@app.get("/health")
def health():
    return {"status": "ok"}
