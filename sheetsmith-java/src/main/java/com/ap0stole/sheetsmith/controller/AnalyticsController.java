package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsQuery;
import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsSummaryDto;
import com.ap0stole.sheetsmith.services.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * One endpoint for the whole analytics screen.
 * <p>
 * Not five, because every chart on that screen is drawn from the same filters, and five answers can
 * disagree with each other if a call lands between the second request and the third. One answer is
 * one moment in time.
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @PostMapping("/summary")
    public AnalyticsSummaryDto summary(@RequestBody(required = false) AnalyticsQuery query) {
        return analyticsService.summary(query == null ? AnalyticsQuery.unfiltered() : query);
    }
}
