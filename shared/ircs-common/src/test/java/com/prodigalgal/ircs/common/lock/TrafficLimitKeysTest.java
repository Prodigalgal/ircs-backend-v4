package com.prodigalgal.ircs.common.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrafficLimitKeysTest {

    @Test
    void acceptsOnlyCurrentBusinessQualifiedTrafficKeys() {
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Provider:Metadata:Ip:203.0.113.10:TMDB"))
                .isTrue();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Provider:Magnet:Ip:203.0.113.10:YTS_BZ"))
                .isTrue();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:DataSource:Scraper:Ip:node-a:source-1"))
                .isTrue();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Domain:ImageDownload:Ip:node-a:img.example.com"))
                .isTrue();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Global:ImageDownload:Ip:node-a"))
                .isTrue();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:cred:tmdb-1"))
                .isTrue();

        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Provider:TMDB")).isFalse();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:cred:")).isFalse();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Provider:Ip:203.0.113.10:TMDB")).isFalse();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:DataSource:Ip:203.0.113.10:source-1"))
                .isFalse();
        assertThat(TrafficLimitKeys.isCurrentTrafficKey("traffic:limit:Domain:Ip:203.0.113.10:img.example.com"))
                .isFalse();
    }

    @Test
    void describesCurrentKeysWithBusinessNames() {
        TrafficLimitKeys.TrafficKeyDescription description =
                TrafficLimitKeys.describe("traffic:limit:Provider:Magnet:Ip:node-a:YTS_BZ");

        assertThat(description.business()).isEqualTo("磁链 Provider");
        assertThat(description.scope()).isEqualTo("provider");
        assertThat(description.target()).isEqualTo("YTS_BZ");
        assertThat(description.egressIdentity()).isEqualTo("node-a");
        assertThat(description.displayName()).isEqualTo("磁链 Provider: YTS_BZ / 出口 node-a");
    }
}
