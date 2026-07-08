package com.prodigalgal.ircs.aggregation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/aggregation")
@RequiredArgsConstructor
class ManualAggregationController {

    private final AggregationService aggregationService;

    @PostMapping("/unified-videos/merge")
    ResponseEntity<ManualUnifiedMergeResponse> mergeUnifiedVideos(
            @Valid @RequestBody ManualUnifiedMergeRequest request) {
        return ResponseEntity.accepted().body(aggregationService.mergeUnifiedVideos(request.ids()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> handleBadRequest(IllegalArgumentException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }

    record ManualUnifiedMergeRequest(
            @NotNull
            @Size(min = 2, message = "ids must contain at least two unified videos")
            List<UUID> ids) {
    }
}
