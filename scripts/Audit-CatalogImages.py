"""公开餐图审计入口。

本模块服务于 Task13 的只读盘点：从隔离业务库读取菜品、套餐身份，
再与公开门店 API 的展示投影交叉核对。数据库密码只在既有容器内部读取，
输出只包含公开目录事实，不读取用户、订单或交易表。
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


# 固定访问集成环境中的业务数据库；容器名可由参数覆盖以适配隔离快照。
DEFAULT_CONTAINER = "fandazi-t12-business-db-1"


def query_rows(container: str, sql: str) -> list[dict[str, Any]]:
    """执行只读 SQL 并解析逐行 JSON；查询失败时抛错，不生成不完整的合格结论。"""
    # 密码变量仅在数据库容器的 shell 中展开；主机命令行、日志和审计 JSON 均不接触密码值。
    command = [
        "docker", "exec", container, "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D "$MYSQL_DATABASE" '
        '--batch --skip-column-names --raw --default-character-set=utf8mb4 -e "$1"',
        "sh", sql,
    ]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", check=False)
    if result.returncode != 0:
        raise RuntimeError(f"数据库只读查询失败，退出码 {result.returncode}")
    # MySQL 每行输出一个 JSON_OBJECT，避免 TSV 中空值与转义符产生歧义。
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def get_json(opener: urllib.request.OpenerDirector, url: str) -> Any:
    """读取公开 API 的 JSON；HTTP、网络或解码失败向上报告，禁止默默跳过投影。"""
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with opener.open(request, timeout=15) as response:
        if response.status != 200:
            raise RuntimeError(f"公开目录返回 HTTP {response.status}: {url}")
        return json.load(response)


def classify_url(url: Any, licensed_urls: set[str]) -> list[str]:
    """按可自动确定的条件分类；内容错配与授权未知由后续逐图人工审核。"""
    if not isinstance(url, str) or not url.strip():
        return ["missing"]
    problems: list[str] = []
    parsed = urllib.parse.urlparse(url)
    # 项目托管资源使用审核清单中的根相对路径；其它相对路径不视为可用图片。
    local_asset = bool(url.startswith("/catalog-images/v1/full/")
                       and not parsed.netloc and not parsed.query and not parsed.fragment)
    if not local_asset and (parsed.scheme not in {"http", "https"} or not parsed.netloc):
        problems.append("invalid_url")
    if url not in licensed_urls:
        problems.append("unknown_license")
    return problems


def manifest_urls(path: Path) -> set[str]:
    """读取已有审核清单中的已授权地址；清单尚未建立时保持所有现有图待核。"""
    if not path.exists():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    entries = data.get("images", []) if isinstance(data, dict) else data
    if not isinstance(entries, list):
        raise ValueError("图片清单 images 必须是数组")
    # 只有同时记录来源、许可、审核结论的条目才解除“未知授权”标志。
    return {
        entry["url"] for entry in entries
        if isinstance(entry, dict)
        and isinstance(entry.get("url"), str)
        and entry.get("sourceUrl")
        and entry.get("licenseName")
        and entry.get("contentReviewed") is True
    }


def probe_image(opener: urllib.request.OpenerDirector, url: str, base: str) -> dict[str, Any]:
    """检查既有图片的 HTTP 可达性与媒体类型；不依赖远程图片的随机重定向。"""
    # 根相对餐图只对固定集成后端补全地址，不访问用户输入的任意主机。
    request_url = base + url if url.startswith("/catalog-images/v1/full/") else url
    parsed = urllib.parse.urlparse(request_url)
    if parsed.hostname == "10.0.2.2":
        # 模拟器专用宿主地址无法作为正式公开资源；记录配置问题而不是尝试碰其它本地服务。
        return {"status": "emulator_only_host", "httpStatus": None, "contentType": None}
    try:
        request = urllib.request.Request(request_url, method="HEAD", headers={"Accept": "image/*"})
        with opener.open(request, timeout=15) as response:
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
            healthy = response.status == 200 and content_type.startswith("image/")
            return {"status": "reachable" if healthy else "invalid_media",
                    "httpStatus": response.status, "contentType": content_type,
                    "contentLength": response.headers.get("Content-Length")}
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        # 网络暂时不可用与真实 404 的根因不同，异常类型保留但不暴露远端正文。
        return {"status": "probe_error", "errorType": type(error).__name__}


def main() -> int:
    """校验参数、读取权威实体和 API、构造审计结果并写入指定文件。"""
    parser = argparse.ArgumentParser(description="只读审计饭搭子公开餐图")
    parser.add_argument("--backend", required=True, help="集成 Spring 基地址，例如 http://127.0.0.1:18080")
    parser.add_argument("--manifest", required=True, type=Path, help="已审核图片清单；不存在时全部图片保留待授权")
    parser.add_argument("--output", required=True, type=Path, help="审计 JSON 输出路径")
    parser.add_argument("--database-container", default=DEFAULT_CONTAINER, help="只读查询的隔离业务库容器")
    args = parser.parse_args()

    # 输入校验：仅允许本机集成 API，避免本审计工具被误用于线上服务或外部主机。
    base = args.backend.rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"} or parsed.port != 18080:
        parser.error("--backend 必须指向本机集成环境的 HTTP 18080")
    licensed = manifest_urls(args.manifest)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    # 数据规范化：实体主键来自数据库，展示记录来自公开接口，两种分母分开保存。
    dishes = query_rows(args.database_container, "SELECT JSON_OBJECT('id',id,'merchantId',merchant_id,"
                        "'name',name,'listed',listed,'imageUrl',image_url) FROM dishes ORDER BY id")
    deals = query_rows(args.database_container, "SELECT JSON_OBJECT('id',id,'publicId',public_id,"
                       "'storeId',store_id,'title',title,'active',active,'imageUrl',image_url,'imageAlt',image_alt) "
                       "FROM deal_packages ORDER BY id")
    stores = query_rows(args.database_container, "SELECT JSON_OBJECT('id',id,'publicId',public_id,"
                        "'merchantId',merchant_id) FROM stores ORDER BY id")
    public_stores = get_json(opener, f"{base}/api/stores")
    if not isinstance(public_stores, list):
        raise ValueError("公开门店接口未返回数组")
    deals_by_id = {row["publicId"]: row for row in deals}
    stores_by_id = {row["publicId"]: row for row in stores}
    dishes_by_merchant: dict[int, list[dict[str, Any]]] = collections.defaultdict(list)
    for dish in dishes:
        if dish["listed"]:
            dishes_by_merchant[dish["merchantId"]].append(dish)

    # 规则判断：逐门店读取套餐和菜单投影，保留其身份及图片地址以发现漏映射。
    projections: list[dict[str, Any]] = []
    projection_mismatches: list[dict[str, Any]] = []
    for store in public_stores:
        public_id = store["publicId"]
        safe_id = urllib.parse.quote(public_id, safe="")
        authoritative_store = stores_by_id.get(public_id)
        if authoritative_store is None:
            projection_mismatches.append({"kind": "store", "id": public_id, "reason": "实体不存在"})
        if store.get("imageUrl") != (store.get("featuredDeal") or {}).get("imageUrl"):
            projection_mismatches.append({"kind": "store", "id": public_id, "reason": "封面不等于当前摘要套餐图"})
        projections.append({"kind": "store", "storePublicId": public_id,
                            "entityId": public_id, "name": store.get("name"),
                            "imageUrl": store.get("imageUrl")})
        for deal in get_json(opener, f"{base}/api/stores/{safe_id}/deals"):
            authoritative_deal = deals_by_id.get(deal.get("publicId"))
            if authoritative_deal is None or authoritative_deal["imageUrl"] != deal.get("imageUrl"):
                projection_mismatches.append({"kind": "deal", "id": deal.get("publicId"),
                                              "reason": "套餐实体缺失或图片不一致"})
            projections.append({"kind": "deal", "storePublicId": public_id,
                                "entityId": deal.get("publicId"), "name": deal.get("title"),
                                "imageUrl": deal.get("imageUrl")})
        public_dishes = get_json(opener, f"{base}/api/stores/{safe_id}/dishes")
        expected_dishes = collections.Counter(
            (row["name"], row["imageUrl"])
            for row in dishes_by_merchant[authoritative_store["merchantId"]]
        ) if authoritative_store and authoritative_store["merchantId"] is not None else collections.Counter()
        actual_dishes = collections.Counter((row.get("name"), row.get("imageUrl")) for row in public_dishes)
        if actual_dishes != expected_dishes:
            projection_mismatches.append({"kind": "dish", "id": public_id,
                                          "reason": "门店菜单与关联商家实体集合不一致",
                                          "expected": sum(expected_dishes.values()),
                                          "actual": sum(actual_dishes.values())})
        for dish in public_dishes:
            projections.append({"kind": "dish", "storePublicId": public_id,
                                "entityId": None, "name": dish.get("name"),
                                "imageUrl": dish.get("imageUrl")})

    # 以数据库实体 ID 建立唯一清单；同图跨不同菜名仅提示内容错配风险，不能自动断定画面。
    entities: list[dict[str, Any]] = []
    for kind, rows, id_field in (("dish", dishes, "id"), ("deal", deals, "publicId")):
        for row in rows:
            url = row.get("imageUrl")
            # 非活动历史套餐仍列入权威分母，但不属于本轮公开图片覆盖率的验收范围。
            inactive = kind == "deal" and not bool(row["active"])
            entities.append({"kind": kind, "entityId": row[id_field],
                             "name": row["name" if kind == "dish" else "title"],
                             "imageUrl": url, "scope": "inactive_excluded" if inactive else "active_catalog",
                             "problems": [] if inactive else classify_url(url, licensed)})
    names_by_url: dict[str, set[str]] = collections.defaultdict(set)
    for item in entities:
        if item["kind"] == "dish" and item["imageUrl"]:
            names_by_url[item["imageUrl"]].add(item["name"].rsplit(" ", 1)[0])
    collision_urls = {url for url, names in names_by_url.items() if len(names) > 1}
    for item in entities:
        if item["kind"] == "dish" and item["imageUrl"] in collision_urls:
            item["problems"].append("possible_content_mismatch")

    # 网络核验：每个不同 URL 只请求一次，避免重复展示行带来无意义的外部请求。
    url_checks = {url: probe_image(opener, url, base) for url in sorted({
        item["imageUrl"] for item in entities
        if item["scope"] == "active_catalog" and item["imageUrl"]
    })}
    for item in entities:
        if item["scope"] == "active_catalog" and item["imageUrl"] and url_checks[item["imageUrl"]]["status"] != "reachable":
            item["problems"].append("broken_or_unusable")

    # 结果构造：记录自动事实及待人工审核，不能把 HEAD 通过当作内容匹配或授权通过。
    counts = collections.Counter(problem for item in entities for problem in item["problems"])
    projection_counts = collections.Counter(item["kind"] for item in projections)
    report = {
        "schemaVersion": 1,
        "auditedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "backend": base,
        "databaseContainer": args.database_container,
        "manifestPresent": args.manifest.exists(),
        "uniqueEntityCounts": {"dish": len(dishes), "deal": len(deals), "store": len(stores)},
        "publicStoreCount": len(public_stores),
        "projectionCounts": dict(projection_counts),
        "problemCounts": dict(counts),
        "projectionMismatches": projection_mismatches,
        "networkChecks": url_checks,
        "manualReviewStatus": "版本化来源清单已逐项审核" if licensed and not counts else "内容及授权仍需逐项人工审核",
        "entities": entities,
        "projections": projections,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"uniqueEntityCounts": report["uniqueEntityCounts"],
                      "projectionCounts": report["projectionCounts"],
                      "problemCounts": report["problemCounts"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError, urllib.error.URLError) as error:
        # 错误只输出类型和简短信息，不回显 SQL 环境变量、响应正文或用户内容。
        print(f"审计失败：{type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
