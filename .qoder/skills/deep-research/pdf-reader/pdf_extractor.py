"""
PDF深度阅读工具——将中文PDF转为结构化分析摘要。

用法:
  python pdf_extractor.py <pdf_path>                    # 单文件
  python pdf_extractor.py <dir_path> --batch            # 批量
  python pdf_extractor.py <pdf_path> --raw              # 输出原始OCR文本
  python pdf_extractor.py <pdf_path> --pages 1-5        # 指定页码范围

依赖: pip install PyMuPDF pytesseract Pillow
      + Tesseract OCR + chi_sim语言包
"""

import fitz  # PyMuPDF
import pytesseract
from PIL import Image
import io
import json
import os
import sys
import argparse
from pathlib import Path


def find_tesseract():
    """查找tesseract可执行文件路径"""
    import subprocess
    # Common install locations
    candidates = [
        r"C:\Program Files\Tesseract-OCR\tesseract.exe",
        r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
        r"C:\Tesseract-OCR\tesseract.exe",
    ]
    # Also check PATH
    import shutil
    path_tess = shutil.which("tesseract")
    if path_tess:
        return path_tess
    for c in candidates:
        if os.path.exists(c):
            return c
    return "tesseract"  # fallback to PATH


def pdf_to_images(pdf_path, pages=None, dpi=200):
    """将PDF页面渲染为PIL Image列表"""
    doc = fitz.open(pdf_path)
    images = []
    
    if pages is None:
        pages = range(doc.page_count)
    elif isinstance(pages, str):
        # Parse "1-5" format
        parts = pages.split("-")
        start = int(parts[0]) - 1
        end = int(parts[1]) if len(parts) > 1 else start + 1
        pages = range(max(0, start), min(end, doc.page_count))
    
    for i in pages:
        if i >= doc.page_count:
            break
        page = doc[i]
        pix = page.get_pixmap(dpi=dpi)
        img = Image.open(io.BytesIO(pix.tobytes("png")))
        images.append((i + 1, img))  # 1-indexed page numbers
    
    doc.close()
    return images


def ocr_images(images, lang="chi_sim"):
    """对图像列表进行OCR"""
    tess_path = find_tesseract()
    if tess_path != "tesseract":
        pytesseract.pytesseract.tesseract_cmd = tess_path
    
    full_text = []
    for page_num, img in images:
        text = pytesseract.image_to_string(img, lang=lang)
        full_text.append(f"\n{'='*60}\n第{page_num}页\n{'='*60}\n{text}")
    
    return "\n".join(full_text)


def main():
    parser = argparse.ArgumentParser(description="PDF深度阅读工具")
    parser.add_argument("path", help="PDF文件路径或目录路径")
    parser.add_argument("--batch", action="store_true", help="批量处理目录下所有PDF")
    parser.add_argument("--raw", action="store_true", help="输出原始OCR文本")
    parser.add_argument("--pages", help="指定页码范围，如 1-5")
    parser.add_argument("--dpi", type=int, default=200, help="渲染DPI (默认200)")
    parser.add_argument("--output", "-o", help="输出文件路径")
    parser.add_argument("--lang", default="chi_sim+eng", help="OCR语言 (默认chi_sim+eng)")
    
    args = parser.parse_args()
    
    path = Path(args.path)
    
    if args.batch and path.is_dir():
        pdfs = sorted(path.glob("*.pdf"))
        print(f"找到 {len(pdfs)} 个PDF文件")
        for pdf in pdfs:
            print(f"\n处理: {pdf.name}")
            images = pdf_to_images(str(pdf), pages=args.pages, dpi=args.dpi)
            text = ocr_images(images, lang=args.lang)
            out_path = pdf.with_suffix(".ocr.txt")
            with open(out_path, "w", encoding="utf-8") as f:
                f.write(text)
            print(f"  输出: {out_path} ({len(text)} 字符)")
    else:
        if not path.exists():
            print(f"错误: 文件不存在 {path}")
            sys.exit(1)
        
        print(f"处理: {path.name}")
        images = pdf_to_images(str(path), pages=args.pages, dpi=args.dpi)
        print(f"  渲染了 {len(images)} 页")
        text = ocr_images(images, lang=args.lang)
        
        if args.output:
            out_path = Path(args.output)
        else:
            out_path = path.with_suffix(".ocr.txt")
        
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"  输出: {out_path} ({len(text)} 字符)")
        
        if args.raw:
            print("\n" + text[:2000])


if __name__ == "__main__":
    main()
