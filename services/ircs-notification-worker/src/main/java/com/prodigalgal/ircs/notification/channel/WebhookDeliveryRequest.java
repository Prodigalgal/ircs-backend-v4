package com.prodigalgal.ircs.notification.channel;

import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

record WebhookDeliveryRequest(
        URI uri,
        Map<String, String> headers,
        byte[] body,
        OutboundHttpPolicy policy) {

    WebhookDeliveryRequest {
        headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        body = body == null ? new byte[0] : body.clone();
    }
}
