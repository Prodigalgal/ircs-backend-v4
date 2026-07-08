package com.prodigalgal.ircs.messaging;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLTransientException;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RabbitTaskFailureClassifier {

    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("(?i)(?:http|upstream)?\\s*status\\s+(\\d{3})");

    public RabbitTaskFailureDisposition classify(Throwable error) {
        Integer statusCode = firstHttpStatus(error);
        if (statusCode != null) {
            return retryableHttpStatus(statusCode)
                    ? RabbitTaskFailureDisposition.RETRY
                    : RabbitTaskFailureDisposition.DLQ;
        }
        if (containsFatal(error)) {
            return RabbitTaskFailureDisposition.DLQ;
        }
        return RabbitTaskFailureDisposition.RETRY;
    }

    public boolean retryable(Throwable error) {
        return classify(error) == RabbitTaskFailureDisposition.RETRY;
    }

    private boolean containsFatal(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (isFatal(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Integer firstHttpStatus(Throwable error) {
        Throwable current = error;
        while (current != null) {
            Integer statusCode = httpStatus(current);
            if (statusCode != null) {
                return statusCode;
            }
            current = current.getCause();
        }
        return null;
    }

    private Integer httpStatus(Throwable error) {
        if (error instanceof RabbitTaskHttpStatusException httpStatusException) {
            return httpStatusException.statusCode();
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean retryableHttpStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 409
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }

    private boolean isFatal(Throwable error) {
        if (error instanceof TaskSourceTerminalException
                || error instanceof IllegalArgumentException
                || error instanceof NoSuchElementException
                || error instanceof UnsupportedOperationException
                || error instanceof SecurityException) {
            return true;
        }
        if (isDeterministicOutboundConfiguration(error)) {
            return true;
        }
        return !isKnownTransient(error) && classNameContains(error, "Config")
                && messageContains(error, "invalid", "missing", "unsupported");
    }

    private boolean isDeterministicOutboundConfiguration(Throwable error) {
        if (!classNameContains(error, "OutboundHttpException")) {
            return false;
        }
        return messageContains(error,
                "blocked",
                "scheme is not allowed",
                "userinfo is not allowed",
                "must include scheme and host",
                "unsupported outbound transport");
    }

    private boolean isKnownTransient(Throwable error) {
        if (error instanceof IOException
                || error instanceof SocketTimeoutException
                || error instanceof ConnectException
                || error instanceof UnknownHostException
                || error instanceof SQLTransientException
                || error instanceof TimeoutException) {
            return true;
        }
        String name = error.getClass().getName();
        return containsAny(name,
                "Redis",
                "Jedis",
                "Lettuce",
                "Rabbit",
                "Amqp",
                "Timeout",
                "Transient",
                "ResourceAccess",
                "Connect",
                "Socket");
    }

    private boolean classNameContains(Throwable error, String token) {
        return error.getClass().getName().contains(token);
    }

    private boolean messageContains(Throwable error, String... tokens) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        for (String token : tokens) {
            if (lower.contains(token.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
