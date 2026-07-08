package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;

public class OutboundHttpException extends IOException {

    public OutboundHttpException(String message) {
        super(message);
    }

    public OutboundHttpException(String message, Throwable cause) {
        super(message, cause);
    }
}
