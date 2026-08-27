package com.ap0stole.sheetsmith.llm;

import com.ap0stole.sheetsmith.requests.AutomationRequest;

/**
 * A plan and what it cost to produce. The two travel together because the cost is only knowable at
 * the call site, while the job record it belongs to may not exist yet — the user has still to
 * approve the plan.
 */
public record PlanningResult(AutomationRequest plan, TokenUsage usage) {
}
