"""验证团购推荐只读工具的确定性顺序和单目标语义。"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from fandazi_agent.workflows.recommendation import run_workflow


def test_single_recommendation_goal_uses_deterministic_workflow():
    """同一个推荐目标可以串行调用两个只读工具，但不能被误标成复合任务。"""
    result = run_workflow("推荐附近人均100以内的川菜团购")

    assert result.is_composite is False
    assert result.tool_names == ["search_stores", "list_deal_packages"]


def test_multiple_goals_are_not_collapsed_into_one_recommendation():
    """两个独立目标必须保留复合标记，后续由 DAG 层处理。"""
    result = run_workflow("推荐川菜团购，并查询我的订单")

    assert result.is_composite is True
