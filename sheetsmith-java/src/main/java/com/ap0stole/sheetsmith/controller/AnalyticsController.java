package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsQuery;
import com.ap0stole.sheetsmith.domain.dto.analytics.AnalyticsSummaryDto;
import com.ap0stole.sheetsmith.services.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * One endpoint for the whole analytics screen.
 * <p>
 * Not five, because every chart on that screen is drawn from the same filters, and five answers can
 * disagree with each other if a call lands between the second request and the third. One answer is
 * one moment in time.
 */
@Tag(name = "Analytics", description = "Spend and token figures over the calls this instance has made, filtered the same way for everyone who may see them.")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @PreAuthorize("@authz.signedIn()")
    @Operation(summary = "Every chart on one answer",
            description = "All the slices for one set of filters, in a single response: two of them fetched separately could disagree if a run finished in between. Money is only counted where a price exists; models without one are named rather than counted as zero.")
    @PostMapping("/summary")
    public AnalyticsSummaryDto summary(@RequestBody(required = false) AnalyticsQuery query) {
        return analyticsService.summary(query == null ? AnalyticsQuery.unfiltered() : query);
    }
}
