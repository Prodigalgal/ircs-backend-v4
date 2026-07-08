package com.prodigalgal.ircs.common.lock;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.util.StringUtils;

public final class TrafficLimitKeys {

    public static final String PREFIX = "traffic:limit:";
    public static final String ACTIVE_INDEX_KEY = "traffic:limit:index";
    public static final String META_PREFIX = "traffic:limit:meta:";

    private TrafficLimitKeys() {
    }

    public static boolean isTrafficKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    public static boolean isCurrentTrafficKey(String key) {
        String rawKey = stripPrefix(key);
        if (!StringUtils.hasText(rawKey)) {
            return false;
        }
        return hasEgress(rawKey, "cred:")
                || hasTrailingTarget(rawKey, "Provider:Metadata:Ip:")
                || hasTrailingTarget(rawKey, "Provider:Magnet:Ip:")
                || hasTrailingTarget(rawKey, "DataSource:Scraper:Ip:")
                || hasTrailingTarget(rawKey, "Domain:ImageDownload:Ip:")
                || hasEgress(rawKey, "Global:ImageDownload:Ip:");
    }

    public static String metaKey(String trafficKey) {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((trafficKey == null ? "" : trafficKey).getBytes(StandardCharsets.UTF_8));
        return META_PREFIX + encoded;
    }

    public static TrafficKeyDescription describe(String key) {
        String rawKey = stripPrefix(key);
        if (!StringUtils.hasText(rawKey)) {
            return new TrafficKeyDescription("未知", "unknown", rawKey, null, rawKey);
        }
        if (rawKey.startsWith("cred:")) {
            String target = rawKey.substring(5);
            return new TrafficKeyDescription("凭证", "credential", target, null, "凭证限流: " + target);
        }
        if (rawKey.startsWith("Provider:Metadata:Ip:")) {
            return trailingIp(rawKey.substring(21), "元数据 Provider", "provider");
        }
        if (rawKey.startsWith("Provider:Magnet:Ip:")) {
            return trailingIp(rawKey.substring(19), "磁链 Provider", "provider");
        }
        if (rawKey.startsWith("DataSource:Scraper:Ip:")) {
            return trailingIp(rawKey.substring(22), "资源站采集", "dataSource");
        }
        if (rawKey.startsWith("Domain:ImageDownload:Ip:")) {
            return trailingIp(rawKey.substring(24), "图片域名", "domain");
        }
        if (rawKey.startsWith("Global:")) {
            return global(rawKey.substring(7));
        }
        return new TrafficKeyDescription("自定义", "custom", rawKey, null, rawKey);
    }

    public static String stripPrefix(String key) {
        if (key == null) {
            return null;
        }
        return key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
    }

    private static TrafficKeyDescription global(String value) {
        int marker = value.indexOf(":Ip:");
        if (marker < 1 || marker >= value.length() - 4) {
            return new TrafficKeyDescription("全局", "global", value, null, "全局: " + value);
        }
        String target = value.substring(0, marker);
        String egressIdentity = value.substring(marker + 4);
        String business = "ImageDownload".equals(target) ? "图片下载全局" : "全局";
        String displayName = "ImageDownload".equals(target)
                ? "图片下载全局 / 出口 " + egressIdentity
                : business + ": " + target + " / 出口 " + egressIdentity;
        return new TrafficKeyDescription(
                business,
                "global",
                target,
                egressIdentity,
                displayName);
    }

    private static TrafficKeyDescription trailingIp(String value, String business, String scope) {
        int delimiter = value.lastIndexOf(':');
        if (delimiter < 1 || delimiter >= value.length() - 1) {
            return new TrafficKeyDescription(business, scope, value, null, business + ": " + value);
        }
        String egressIdentity = value.substring(0, delimiter);
        String target = value.substring(delimiter + 1);
        return new TrafficKeyDescription(
                business,
                scope,
                target,
                egressIdentity,
                business + ": " + target + " / 出口 " + egressIdentity);
    }

    private static boolean hasTrailingTarget(String rawKey, String prefix) {
        if (!rawKey.startsWith(prefix)) {
            return false;
        }
        String value = rawKey.substring(prefix.length());
        int delimiter = value.lastIndexOf(':');
        return delimiter > 0 && delimiter < value.length() - 1;
    }

    private static boolean hasEgress(String rawKey, String prefix) {
        return rawKey.startsWith(prefix) && StringUtils.hasText(rawKey.substring(prefix.length()));
    }

    public record TrafficKeyDescription(
            String business,
            String scope,
            String target,
            String egressIdentity,
            String displayName) {
    }
}
