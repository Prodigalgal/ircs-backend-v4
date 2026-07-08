package com.prodigalgal.ircs.common.maintenance;

public class MaintenanceGateLockedException extends RuntimeException {

    private final MaintenanceGateDecision decision;

    public MaintenanceGateLockedException(MaintenanceGateDecision decision) {
        super(decision == null ? "maintenance gate blocks write" : decision.blockedMessage());
        this.decision = decision;
    }

    public MaintenanceGateDecision decision() {
        return decision;
    }
}
