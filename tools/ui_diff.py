#!/usr/bin/env python3
"""视觉回归 diff：内容帧归一化、区域评分、动态 mask、hierarchy 检查和 HTML 报告。"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw


def load_frame(path: Path, frame: list[int], size: tuple[int, int]) -> np.ndarray:
    image = Image.open(path).convert("RGB")
    x1, y1, x2, y2 = frame
    image = image.crop((x1, y1, x2, y2)).resize(size, Image.Resampling.LANCZOS)
    return np.asarray(image, dtype=np.float32) / 255.0


def box(region: dict, size: tuple[int, int]) -> tuple[int, int, int, int]:
    width, height = size
    x, y, w, h = (float(region[k]) for k in ("x", "y", "w", "h"))
    return round(x * width), round(y * height), round((x + w) * width), round((y + h) * height)


def apply_mask(diff: np.ndarray, masks: list[dict]) -> None:
    for region in masks:
        x1, y1, x2, y2 = box(region, (diff.shape[1], diff.shape[0]))
        diff[y1:y2, x1:x2] = 0


def resize_array(image: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    pixels = np.clip(image * 255, 0, 255).astype(np.uint8)
    resized = Image.fromarray(pixels).resize(size, Image.Resampling.LANCZOS)
    return np.asarray(resized, dtype=np.float32) / 255.0


def ssim_score(a: np.ndarray, b: np.ndarray) -> float:
    # 全局结构相似度用于报告；区域判定使用差异面积，避免局部误差被平均掉。
    x, y = a.mean(axis=2), b.mean(axis=2)
    ux, uy, vx, vy = x.mean(), y.mean(), x.var(), y.var()
    c1, c2 = 0.01**2, 0.03**2
    covariance = ((x - ux) * (y - uy)).mean()
    return float(((2 * ux * uy + c1) * (2 * covariance + c2)) / ((ux**2 + uy**2 + c1) * (vx + vy + c2)))


def hierarchy_items(path: Path) -> list[dict[str, str]]:
    if not path:
        return []
    items = []
    if path.suffix.lower() == ".json":
        def walk(node: object) -> None:
            if isinstance(node, dict):
                attrs = node.get("attributes", node)
                if isinstance(attrs, dict):
                    items.append({"id": str(attrs.get("resource-id", attrs.get("resourceId", ""))), "text": str(attrs.get("text", "")), "content": str(attrs.get("content-desc", attrs.get("contentDescription", "")))})
                for child in node.get("children", []) if isinstance(node.get("children", []), list) else []:
                    walk(child)
            elif isinstance(node, list):
                for child in node:
                    walk(child)
        walk(json.loads(path.read_text(encoding="utf-8")))
        return [item for item in items if " ".join(item.values()).strip()]
    root = ET.parse(path).getroot()
    for node in root.iter():
        attrs = node.attrib
        value = " ".join(filter(None, [attrs.get("resource-id", ""), attrs.get("content-desc", ""), attrs.get("text", "")])).strip()
        if value:
            items.append({"id": attrs.get("resource-id", ""), "text": attrs.get("text", ""), "content": attrs.get("content-desc", "")})
    return items


def compare_hierarchy(actual: Path | None, expected: list[str]) -> dict:
    actual_items = hierarchy_items(actual) if actual else []
    haystack = [" ".join(item.values()) for item in actual_items]
    missing = [needle for needle in expected if not any(needle in value for value in haystack)]
    return {"expected": expected, "actual_count": len(actual_items), "missing": missing, "passed": not missing}


def image_data(path: Path) -> str:
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate screenshot visual diff HTML report")
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--actual", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--hierarchy", type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    width, height = config.get("canonical_size", [412, 915])
    size = (int(width), int(height))
    reference = load_frame(args.reference, config["reference_frame"], size)
    actual = load_frame(args.actual, config["actual_frame"], size)
    delta = np.abs(reference - actual).mean(axis=2)
    masks = config.get("masks", [])
    apply_mask(delta, masks)
    args.out.mkdir(parents=True, exist_ok=True)

    heat = np.clip(delta * 4, 0, 1)
    heat_rgb = np.zeros((*heat.shape, 3), dtype=np.uint8)
    heat_rgb[..., 0] = (heat * 255).astype(np.uint8)
    heat_rgb[..., 1] = ((1 - heat) * 80).astype(np.uint8)
    heat_rgb[..., 2] = ((1 - heat) * 80).astype(np.uint8)
    Image.fromarray(heat_rgb).save(args.out / "diff-heatmap.png")
    Image.fromarray(((reference * 0.5 + actual * 0.5) * 255).astype(np.uint8)).save(args.out / "overlay.png")
    Image.fromarray((actual * 255).astype(np.uint8)).save(args.out / "actual-aligned.png")
    Image.fromarray((reference * 255).astype(np.uint8)).save(args.out / "reference-aligned.png")

    threshold = float(config.get("pixel_threshold", 0.12))
    results = []
    for region in config.get("regions", []):
        reference_region = region.get("reference_region", region)
        actual_region = region.get("actual_region", region)
        rx1, ry1, rx2, ry2 = box(reference_region, size)
        ax1, ay1, ax2, ay2 = box(actual_region, size)
        reference_crop = reference[ry1:ry2, rx1:rx2]
        actual_crop = actual[ay1:ay2, ax1:ax2]
        compare_width = max(1, rx2 - rx1)
        compare_height = max(1, ry2 - ry1)
        actual_crop = resize_array(actual_crop, (compare_width, compare_height))
        region_delta = np.abs(reference_crop - actual_crop).mean(axis=2)
        score = ssim_score(reference_crop, actual_crop)
        area = float((region_delta > threshold).mean()) if region_delta.size else 1.0
        limit = float(region.get("max_diff_area", config.get("default_max_diff_area", 0.08)))
        passed = score >= float(region.get("min_ssim", config.get("default_min_ssim", 0.85))) and area <= limit
        results.append({"name": region["name"], "ssim": round(score, 4), "diff_area": round(area, 4), "limit": limit, "passed": passed})

    hierarchy = compare_hierarchy(args.hierarchy, config.get("required_controls", []))
    overall = all(item["passed"] for item in results) and hierarchy["passed"]
    report = {"passed": overall, "reference": str(args.reference), "actual": str(args.actual), "canonical_size": size, "masks": masks, "regions": results, "hierarchy": hierarchy}
    (args.out / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    html = f'''<!doctype html><meta charset="utf-8"><title>UI Diff Report</title>
<style>body{{font:14px system-ui;margin:24px;background:#f7f8fa;color:#1f2329}}.grid{{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}}img{{width:100%;background:white;border-radius:12px}}.pass{{color:#18a058}}.fail{{color:#e5484d}}table{{border-collapse:collapse;background:white}}td,th{{padding:8px 12px;border-bottom:1px solid #eee;text-align:left}}</style>
<h1>UI Diff Report <span class="{'pass' if overall else 'fail'}">{'PASS' if overall else 'FAIL'}</span></h1>
<p>Canonical frame: {width} × {height} dp · masked regions: {len(masks)}</p>
<div class="grid"><figure><figcaption>Reference</figcaption><img src="data:image/png;base64,{image_data(args.out / 'reference-aligned.png')[22:]}"></figure><figure><figcaption>Actual</figcaption><img src="data:image/png;base64,{image_data(args.out / 'actual-aligned.png')[22:]}"></figure><figure><figcaption>Heatmap</figcaption><img src="data:image/png;base64,{image_data(args.out / 'diff-heatmap.png')[22:]}"></figure></div>
<h2>Regions</h2><table><tr><th>Region</th><th>SSIM</th><th>Diff area</th><th>Result</th></tr>{''.join(f"<tr><td>{r['name']}</td><td>{r['ssim']}</td><td>{r['diff_area']} / {r['limit']}</td><td class={'pass' if r['passed'] else 'fail'}>{'PASS' if r['passed'] else 'FAIL'}</td></tr>" for r in results)}</table>
<h2>Hierarchy</h2><p class="{'pass' if hierarchy['passed'] else 'fail'}">{'PASS' if hierarchy['passed'] else 'Missing: ' + ', '.join(hierarchy['missing'])}</p>'''
    (args.out / "report.html").write_text(html, encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())
