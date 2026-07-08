package com.prodigalgal.ircs.ops.dashboard.controller;

import com.prodigalgal.ircs.ops.dashboard.application.DashboardQueryService;
import com.prodigalgal.ircs.ops.dashboard.application.DashboardStreamService;
import com.prodigalgal.ircs.ops.dashboard.dto.ChartDataPoint;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardDistributionsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardEfficiencyResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardStatsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.SourceQualityResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.SystemMetricsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;
    private final DashboardStreamService dashboardStreamService;

    @GetMapping("/statistics")
    public ResponseEntity<DashboardStatsResponse> statistics() {
        return ResponseEntity.ok(dashboardQueryService.getStatistics());
    }

    @GetMapping("/trend")
    public ResponseEntity<List<ChartDataPoint>> trend(@RequestParam(name = "days", defaultValue = "14") int days) {
        return ResponseEntity.ok(dashboardQueryService.getTrend(days));
    }

    @GetMapping("/distributions")
    public ResponseEntity<DashboardDistributionsResponse> distributions() {
        return ResponseEntity.ok(dashboardQueryService.getDistributions());
    }

    @GetMapping("/coverage")
    public ResponseEntity<Map<String, Long>> coverage() {
        return ResponseEntity.ok(dashboardQueryService.getCoverage());
    }

    @GetMapping("/efficiency")
    public ResponseEntity<DashboardEfficiencyResponse> efficiency() {
        return ResponseEntity.ok(dashboardQueryService.getEfficiency());
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@RequestParam(name = "days", defaultValue = "14") int days) {
        dashboardQueryService.refresh(days);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metrics")
    public ResponseEntity<SystemMetricsResponse> metrics() {
        return ResponseEntity.ok(dashboardQueryService.getMetrics());
    }

    @GetMapping("/task-runtime")
    public ResponseEntity<TaskRuntimeOverviewResponse> taskRuntime(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(dashboardQueryService.getTaskRuntimeOverview(limit));
    }

    @GetMapping("/search-ops")
    public ResponseEntity<Map<String, Object>> searchOps() {
        return ResponseEntity.ok(dashboardQueryService.getSearchOpsStats());
    }

    @GetMapping("/aggregation-ops")
    public ResponseEntity<Map<String, Object>> aggregationOps() {
        return ResponseEntity.ok(dashboardQueryService.getAggregationOpsStats());
    }

    @GetMapping(value = "/stream/{topic}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter topicStream(
            @PathVariable(name = "topic") String topic,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        try {
            return dashboardStreamService.stream(topic, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/source-quality")
    public ResponseEntity<List<SourceQualityResponse>> sourceQuality() {
        return ResponseEntity.ok(dashboardQueryService.getSourceQuality());
    }
}
