package com.prodigalgal.ircs.ops.api;

import com.prodigalgal.ircs.common.web.ApiErrorResponse;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import com.prodigalgal.ircs.ops.audit.governance.AuditGovernanceController;
import com.prodigalgal.ircs.ops.audit.notification.NotificationMailSendHistoryController;
import com.prodigalgal.ircs.ops.audit.request.RequestAuditController;
import com.prodigalgal.ircs.ops.audit.worker.WorkerJobAuditController;
import com.prodigalgal.ircs.ops.maintenance.controller.MaintenanceController;
import com.prodigalgal.ircs.ops.queue.dlq.persistence.DlqController;
import com.prodigalgal.ircs.ops.queue.dlq.rabbit.RabbitDlqController;
import com.prodigalgal.ircs.ops.selfhealing.LowRiskSelfHealingController;
import com.prodigalgal.ircs.ops.selfhealing.ServiceSelfHealingController;
import com.prodigalgal.ircs.ops.traffic.controller.TrafficMonitorController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        RequestAuditController.class,
        WorkerJobAuditController.class,
        NotificationMailSendHistoryController.class,
        AuditGovernanceController.class,
        DlqController.class,
        RabbitDlqController.class,
        LowRiskSelfHealingController.class,
        ServiceSelfHealingController.class,
        TrafficMonitorController.class,
        MaintenanceController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpsApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return ApiErrorResponses.response(
                HttpStatus.BAD_REQUEST,
                "ops.request.invalid",
                ex.getMessage(),
                "ops",
                request);
    }
}
