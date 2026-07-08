package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransportRouter;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundUserAgents;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class TrendProviderHttpClient {

    private final OutboundHttpClient httpClient;
    private final ScraperTrendConfigValues configValues;

    TrendProviderHttpClient(
            ObjectProvider<OutboundHttpClient> httpClientProvider,
            ScraperTrendConfigValues configValues) {
        this.httpClient = httpClient(httpClientProvider);
        this.configValues = configValues;
    }

    String get(String url) {
        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.publicFetch(configValues.providerTimeout())
                    .withUserAgent(OutboundUserAgents.defaultBrowser())
                    .withCallerCircuitBreakerKey("scraper-trend-provider");
            OutboundHttpRequest request = OutboundHttpRequest.get(url, policy)
                    .withHeader("Accept", "text/html,application/json")
                    .withHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("upstream status " + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provider fetch interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("provider fetch failed: " + ex.getMessage(), ex);
        }
    }

    private static OutboundHttpClient httpClient(ObjectProvider<OutboundHttpClient> httpClientProvider) {
        if (httpClientProvider != null) {
            OutboundHttpClient provided = httpClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new OutboundTransportRouter());
    }
}
