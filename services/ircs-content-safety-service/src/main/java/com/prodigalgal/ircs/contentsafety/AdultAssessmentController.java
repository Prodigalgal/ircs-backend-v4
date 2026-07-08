package com.prodigalgal.ircs.contentsafety;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchRequest;
import com.prodigalgal.ircs.contracts.contentsafety.AdultAssessmentBatchResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/content-safety")
@RequiredArgsConstructor
class AdultAssessmentController {

    private final ContentSafetyInternalAccessPolicy accessPolicy;
    private final AdultAssessmentService assessmentService;

    @PostMapping("/adult-assessments:batch")
    ResponseEntity<AdultAssessmentBatchResponse> assessBatch(
            @Valid @RequestBody AdultAssessmentBatchRequest request,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.ok(assessmentService.assess(request));
    }
}
