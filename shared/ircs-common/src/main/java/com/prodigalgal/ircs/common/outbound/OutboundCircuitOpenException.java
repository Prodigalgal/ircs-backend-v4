package com.prodigalgal.ircs.common.outbound;

public class OutboundCircuitOpenException extends OutboundHttpException {

    public OutboundCircuitOpenException(String message) {
        super(message);
    }
}
