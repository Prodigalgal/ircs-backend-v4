package com.prodigalgal.ircs.notification.mail;

class MailCredentialLeaseException extends RuntimeException {

    MailCredentialLeaseException(String message) {
        super(message);
    }

    MailCredentialLeaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
