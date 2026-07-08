package com.prodigalgal.ircs.notification.channel;

import java.io.IOException;

interface WebhookDeliveryClient {

    WebhookDeliveryResponse post(WebhookDeliveryRequest request) throws IOException, InterruptedException;
}
