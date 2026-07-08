package com.prodigalgal.ircs.common.security;

public class IrcsAuthException extends RuntimeException {

    private final Reason reason;

    public IrcsAuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING,
        EXPIRED,
        STALE,
        INVALID,
        FORBIDDEN
    }
}
