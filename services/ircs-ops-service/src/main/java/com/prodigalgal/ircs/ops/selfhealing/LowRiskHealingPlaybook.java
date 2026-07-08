package com.prodigalgal.ircs.ops.selfhealing;

public enum LowRiskHealingPlaybook {
    RUNTIME_EXPIRED_INFLIGHT_REQUEUE,
    RUNTIME_DLQ_REQUEUE_ONE,
    RABBIT_DLQ_RETRY_ONE,
    DASHBOARD_REFRESH
}
