"""团购推荐工作流：识别单目标推荐并给出只读工具执行计划。"""

from __future__ import annotations

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class RecommendationPlan:
    """描述推荐工作流是否复合以及需要串行调用的工具。"""

    is_composite: bool
    tool_names: list[str]
    category: str | None = None
    max_price_cents: int | None = None


def run_workflow(query: str) -> RecommendationPlan:
    """把一个自然语言推荐请求转换为确定性只读计划。"""
    normalized = query.strip()
    # 出现两个可独立完成的目标时交给 DAG，不能让推荐工作流吞掉第二个目标。
    is_composite = bool(re.search(r"(?:并且|并|同时|另外|然后|，).*(?:订单|退款|投诉|优惠券|评价)", normalized))
    category = next((item for item in ("川菜", "火锅", "烧烤", "日料", "西餐") if item in normalized), None)
    price_match = re.search(r"人均\s*(\d+)\s*元", normalized)
    max_price_cents = int(price_match.group(1)) * 100 if price_match else None
    return RecommendationPlan(
        is_composite=is_composite,
        tool_names=[] if is_composite else ["search_stores", "list_deal_packages"],
        category=category,
        max_price_cents=max_price_cents,
    )
