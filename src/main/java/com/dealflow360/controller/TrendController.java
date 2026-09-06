package com.dealflow360.controller;

import com.dealflow360.dto.TrendDtos.Dimension;
import com.dealflow360.dto.TrendDtos.TrendFilter;
import com.dealflow360.dto.TrendDtos.TrendResponse;
import com.dealflow360.service.TrendService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Trends & Analytics - "last N months of data for some particular thing,
 * as a number and a graph too". This is a differentiator on top of the
 * original PDF scope, added on request; kept as its own controller so it
 * is obviously separate from the PDF-mandated A7 reporting screen
 * ({@link DashboardController}).
 */
@RestController
@RequestMapping("/api/reports/trends")
@PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','FINANCE','SALES_REP')")
public class TrendController {

    private final TrendService trendService;

    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

    @GetMapping
    public TrendResponse trends(@RequestParam(required = false) Integer months,
                                 @RequestParam(required = false) String dimension,
                                 @RequestParam(required = false) Long dimensionId,
                                 @RequestParam(required = false) String category) {
        TrendFilter filter = new TrendFilter();
        filter.months = months != null ? months : 3;
        if (dimension != null && !dimension.isBlank()) {
            try {
                filter.dimension = Dimension.valueOf(dimension.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown dimension: " + dimension);
            }
        }
        filter.dimensionId = dimensionId;
        filter.category = category;
        return trendService.run(filter);
    }
}
