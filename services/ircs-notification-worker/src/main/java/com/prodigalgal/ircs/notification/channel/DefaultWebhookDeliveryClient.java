package com.prodigalgal.ircs.notification.channel;

import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import java.io.IOException;

class DefaultWebhookDeliveryClient implements WebhookDeliveryClient {

    private final OutboundHttpClient httpClient;

    DefaultWebhookDeliveryClient(OutboundHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public WebhookDeliveryResponse post(WebhookDeliveryRequest request) throws IOException, InterruptedException {
        OutboundHttpRequest outboundRequest = new OutboundHttpRequest(
                request.uri(),
                "POST",
                request.headers(),
                request.policy(),
                request.body());
        OutboundHttpResponse response = httpClient.execute(outboundRequest);
        return new WebhookDeliveryResponse(response.statusCode());
    }
}
