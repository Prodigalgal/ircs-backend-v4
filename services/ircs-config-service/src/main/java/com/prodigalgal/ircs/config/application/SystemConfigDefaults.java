package com.prodigalgal.ircs.config.application;

import com.prodigalgal.ircs.config.config.RuntimeInjectedConfig;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SystemConfigDefaults {

    static final String ADMIN_PASSWORD_KEY = "security.admin.password";
    static final String JWT_SECRET_KEY = "security.jwt.secret";
    static final Set<String> REQUIRED_KEYS = Set.of(
            ADMIN_PASSWORD_KEY,
            JWT_SECRET_KEY,
            "app.storage.r2.bucket-name",
            "app.storage.r2.public-domain");
    static final Set<String> LLM_PROMPT_KEYS = Set.of(
            "app.ai.llm.prompt.language",
            "app.ai.llm.prompt.area",
            "app.ai.llm.prompt.genre",
            "app.ai.llm.prompt.category");
    static final Set<String> CORE_KEYS;
    private static final Map<String, List<String>> INJECTION_ALIASES = Map.ofEntries(
            Map.entry("security.admin.username", List.of("app.identity.admin.username")),
            Map.entry("security.admin.password", List.of("app.identity.admin.password-hash")),
            Map.entry("security.jwt.secret", List.of("app.identity.jwt.secret")),
            Map.entry("security.jwt.iat-floor", List.of("app.identity.jwt.iat-floor")),
            Map.entry("member.auth.code.validity-seconds", List.of("app.identity.code.validity")),
            Map.entry("member.auth.code.rate-limit-seconds", List.of("app.identity.code.rate-limit")),
            Map.entry("member.register.email-verify.enabled", List.of("app.identity.register.email-verify-enabled")),
            Map.entry("member.register.timezone", List.of("app.identity.register.timezone")),
            Map.entry("app.mail.enabled", List.of("app.identity.mail.enabled")),
            Map.entry("app.frontend.url", List.of("app.identity.frontend-url")),
            Map.entry("app.storage.r2.public-domain", List.of("app.storage.r2-public-domain")));

    private static final List<DefaultConfig> DEFAULTS = List.of(
            cfg("normalization.timeout.processing-min", "30", "归一化处理超时时间(分钟)"),
            cfg("normalization.timeout.pending-min", "60", "归一化等待超时补救(分钟)"),
            cfg("normalization.max-retries", "5", "归一化最大重试次数"),
            cfg("normalization.backoff.base-seconds", "60", "归一化重试基础退避时间(秒)"),
            cfg("normalization.batch-size", "500", "归一化处理基准批次"),
            cfg("normalization.batch-size.min", "100", "归一化最小批次"),
            cfg("normalization.batch-size.max", "1000", "归一化最大批次"),
            cfg("app.normalization.valkey-worker.batch-size", "10", "归一化 Valkey worker 每轮领取数量"),
            cfg("app.normalization.valkey-worker.visibility-timeout", "PT10M", "归一化 Valkey worker 任务可见性超时"),
            cfg("app.normalization.valkey-worker.initial-delay-ms", "10000", "归一化 Valkey worker 启动延迟(毫秒)"),
            cfg("app.normalization.valkey-worker.fixed-delay-ms", "1000", "归一化 Valkey worker 空转轮询间隔(毫秒)"),
            cfg("app.normalization.pending-watchdog.enabled", "true", "是否启用归一化 PENDING 补投递 Watchdog"),
            cfg("app.normalization.pending-watchdog.batch-size", "200", "归一化 PENDING Watchdog 每轮扫描数量"),
            cfg("app.normalization.pending-watchdog.min-pending-age", "PT5M", "归一化 PENDING Watchdog 最小等待时间"),
            cfg("app.normalization.pending-watchdog.lease-ttl", "PT45S", "归一化 PENDING Watchdog 集群租约 TTL"),
            cfg("app.normalization.pending-watchdog.initial-delay-ms", "30000", "归一化 PENDING Watchdog 启动延迟(毫秒)"),
            cfg("app.normalization.pending-watchdog.fixed-delay-ms", "60000", "归一化 PENDING Watchdog 扫描间隔(毫秒)"),
            cfg("app.metadata.timeout.processing-min", "45", "元数据丰富处理超时时间(分钟)"),
            cfg("app.metadata.timeout.pending-min", "120", "元数据等待超时补救(分钟)"),
            cfg("app.metadata.cron", "0 0/30 * * * ?", "元数据填充任务 CRON 表达式"),
            cfg("app.metadata.interval.ms", "1800000", "元数据填充回退间隔(毫秒)"),
            cfg("app.metadata.batch-size", "200", "元数据填充任务批处理大小"),
            cfg("app.metadata.batch-size.min", "50", "元数据填充最小批次"),
            cfg("app.metadata.batch-size.max", "500", "元数据填充最大批次"),
            cfg("app.search.sync.batch-size", "200", "搜索增量同步每批次数量"),
            cfg("app.frontend.url", "https://huawai.mnnu.eu.org", "前端访问域名"),
            cfg("security.admin.username", "admin", "管理员账号"),
            cfg(ADMIN_PASSWORD_KEY, "", "管理员密码 (BCrypt)"),
            cfg(JWT_SECRET_KEY, "", "JWT 签名密钥"),
            cfg("security.jwt.iat-floor", "0", "JWT 签发时间地板"),
            cfg("security.ratelimit.enabled", "true", "是否启用 API 请求限流"),
            cfg("security.ratelimit.rules", "classpath:presets/ratelimit_rules.json", "动态限流路由规则 (JSON数组)"),
            cfg("member.auth.code.validity-seconds", "900", "注册/激活验证码有效期(秒)"),
            cfg("member.auth.code.rate-limit-seconds", "120", "验证码获取频率限制(秒)"),
            cfg("member.register.email-verify.enabled", "true", "是否启用注册邮箱强制验证"),
            cfg("member.register.time-window.enabled", "false", "是否启用注册时间段限制"),
            cfg("member.register.time-window.start", "09:00", "允许注册开始时间"),
            cfg("member.register.time-window.end", "18:00", "允许注册结束时间"),
            cfg("member.register.timezone", "Asia/Shanghai", "注册时间限制所依据的时区"),
            cfg("member.message.daily-limit", "5", "每个用户每日允许提交的留言数量"),
            cfg("member.message.point-cost", "1", "每次留言消耗的会员积分"),
            cfg("member.media-request.daily-limit", "5", "每个用户每日允许提交的求片数量"),
            cfg("member.media-request.point-cost", "3", "每次求片消耗的会员积分"),
            cfg("member.oauth.enabled", "false", "是否启用会员第三方登录"),
            cfg("member.oauth.auto-register.enabled", "true", "第三方登录首次进入时是否自动创建会员"),
            cfg("member.oauth.bind-existing-email.enabled", "false", "第三方登录邮箱命中已有会员时是否自动绑定"),
            cfg("member.oauth.email-ownership-verification.enabled", "true", "第三方登录绑定已有邮箱前是否强制验证邮箱归属"),
            cfg("member.oauth.redirect-base-url", "https://huawai.mnnu.eu.org", "第三方登录回调基础域名"),
            cfg("member.oauth.allowed-providers", "google,x,github,gitee,wechat,qq", "允许展示的第三方登录 Provider 列表"),
            cfg("member.oauth.google.enabled", "false", "是否启用 Google 登录"),
            cfg("member.oauth.google.client-id", "", "Google OAuth Client ID"),
            cfg("member.oauth.google.client-secret", "", "Google OAuth Client Secret"),
            cfg("member.oauth.google.scope", "openid email profile", "Google OAuth Scope"),
            cfg("member.oauth.google.redirect-uri", "/api/portal/auth/oauth/google/callback", "Google OAuth 回调路径"),
            cfg("member.oauth.x.enabled", "false", "是否启用 X 登录"),
            cfg("member.oauth.x.client-id", "", "X OAuth Client ID"),
            cfg("member.oauth.x.client-secret", "", "X OAuth Client Secret"),
            cfg("member.oauth.x.scope", "users.read tweet.read offline.access", "X OAuth Scope"),
            cfg("member.oauth.x.redirect-uri", "/api/portal/auth/oauth/x/callback", "X OAuth 回调路径"),
            cfg("member.oauth.github.enabled", "false", "是否启用 GitHub 登录"),
            cfg("member.oauth.github.client-id", "", "GitHub OAuth Client ID"),
            cfg("member.oauth.github.client-secret", "", "GitHub OAuth Client Secret"),
            cfg("member.oauth.github.scope", "read:user user:email", "GitHub OAuth Scope"),
            cfg("member.oauth.github.redirect-uri", "/api/portal/auth/oauth/github/callback", "GitHub OAuth 回调路径"),
            cfg("member.oauth.gitee.enabled", "false", "是否启用 Gitee 登录"),
            cfg("member.oauth.gitee.client-id", "", "Gitee OAuth Client ID"),
            cfg("member.oauth.gitee.client-secret", "", "Gitee OAuth Client Secret"),
            cfg("member.oauth.gitee.scope", "user_info emails", "Gitee OAuth Scope"),
            cfg("member.oauth.gitee.redirect-uri", "/api/portal/auth/oauth/gitee/callback", "Gitee OAuth 回调路径"),
            cfg("member.oauth.wechat.enabled", "false", "是否启用微信登录"),
            cfg("member.oauth.wechat.app-id", "", "微信开放平台 App ID"),
            cfg("member.oauth.wechat.app-secret", "", "微信开放平台 App Secret"),
            cfg("member.oauth.wechat.scope", "snsapi_login", "微信 OAuth Scope"),
            cfg("member.oauth.wechat.redirect-uri", "/api/portal/auth/oauth/wechat/callback", "微信登录回调路径"),
            cfg("member.oauth.qq.enabled", "false", "是否启用 QQ 登录"),
            cfg("member.oauth.qq.app-id", "", "QQ 互联 App ID"),
            cfg("member.oauth.qq.app-key", "", "QQ 互联 App Key"),
            cfg("member.oauth.qq.scope", "get_user_info", "QQ OAuth Scope"),
            cfg("member.oauth.qq.redirect-uri", "/api/portal/auth/oauth/qq/callback", "QQ 登录回调路径"),
            cfg("app.mail.enabled", "true", "是否启用邮件发送功能"),
            cfg("app.mail.host", "smtp.gmail.com", "SMTP 服务器地址"),
            cfg("app.mail.port", "465", "SMTP 端口"),
            cfg("app.mail.protocol", "smtp", "邮件协议"),
            cfg("app.mail.from", "HuaWai System <huawai.system@gmail.com>", "默认发件人地址"),
            cfg("app.mail.properties.auth", "true", "是否需要SMTP认证"),
            cfg("app.mail.properties.starttls", "false", "是否启用STARTTLS"),
            cfg("app.mail.properties.ssl", "true", "是否启用纯SSL"),
            cfg("app.mail.timeout", "10000", "邮件超时时间(毫秒)"),
            cfg("app.mail.debug", "false", "是否开启邮件调试日志"),
            cfg("app.mail.rate-limit.min-ms", "1000", "邮件发送最小间隔(毫秒)"),
            cfg("app.mail.rate-limit.max-ms", "2000", "邮件发送最大间隔(毫秒)"),
            cfg("app.mail.rate-limit.max-wait-ms", "60000", "邮件限流最大等待时间(毫秒)"),
            cfg("app.notification.webhook.enabled", "false", "是否启用通知 Webhook 通道"),
            cfg("app.notification.webhook.request-timeout", "PT5S", "通知 Webhook 请求超时"),
            cfg("app.notification.webhook.max-retries", "1", "通知 Webhook 可重试 HTTP/网络失败最大重试次数"),
            cfg("app.notification.webhook.max-payload-bytes", "65536", "通知 Webhook 单次载荷最大字节数"),
            cfg("app.notification.webhook.user-agent", "IRCS-Notification-Webhook/0.1", "通知 Webhook 请求 User-Agent"),
            cfg("app.notification.webhook.allow-private-addresses", "false", "是否允许通知 Webhook 投递到内网地址"),
            cfg("app.adaptive.enabled", "true", "是否启用自适应负载调度"),
            cfg("app.adaptive.load-threshold", "0.8", "系统高负载阈值 (0.0-1.0)"),
            cfg("app.adaptive.check-interval-ms", "10000", "资源监控采样间隔(毫秒)"),
            cfg("app.runtime-config.local-cache.ttl", "PT2S", "运行时系统配置本地缓存 TTL"),
            cfg("app.ops.config-listener.enabled", "true", "是否启用运维服务配置变更事件监听"),
            cfg("app.aggregation.config-listener.enabled", "true", "是否启用聚合 Worker 配置变更事件监听"),
            cfg("app.content.config-listener.enabled", "true", "是否启用内容服务配置变更事件监听"),
            cfg("app.content.admin-video-search.es-enabled", "true", "是否启用内容管理列表 ES ID 发现加速"),
            cfg("app.identity.config-listener.enabled", "true", "是否启用认证服务配置变更事件监听"),
            cfg("app.interaction.config-listener.enabled", "true", "是否启用互动服务配置变更事件监听"),
            cfg("app.metadata.config-listener.enabled", "true", "是否启用元数据 Worker 配置变更事件监听"),
            cfg("app.normalization.config-listener.enabled", "true", "是否启用归一化 Worker 配置变更事件监听"),
            cfg("app.notification.config-listener.enabled", "true", "是否启用通知 Worker 配置变更事件监听"),
            cfg("app.portal.config-listener.enabled", "true", "是否启用门户服务配置变更事件监听"),
            cfg("app.scraper.config-listener.enabled", "true", "是否启用采集服务配置变更事件监听"),
            cfg("app.search.config-listener.enabled", "true", "是否启用搜索服务配置变更事件监听"),
            cfg("app.storage.config-listener.enabled", "true", "是否启用存储服务配置变更事件监听"),
            cfg("app.ops.dashboard.stream.interval", "PT3S", "仪表盘 Topic SSE 默认推送间隔"),
            cfg("app.ops.dashboard.stream.metrics.interval", "PT3S", "仪表盘 Metrics Topic SSE 推送间隔"),
            cfg("app.ops.dashboard.stream.task-runtime.interval", "PT3S", "仪表盘 Task Runtime Topic SSE 推送间隔"),
            cfg("app.ops.dashboard.stream.search-ops.interval", "PT5S", "仪表盘 Search Ops Topic SSE 推送间隔"),
            cfg("app.ops.dashboard.stream.aggregation-ops.interval", "PT5S", "仪表盘 Aggregation Ops Topic SSE 推送间隔"),
            cfg("app.ops.dashboard.stream.timeout", "PT1H", "仪表盘 SSE 连接超时"),
            cfg("app.ops.dashboard.snapshot.enabled", "true", "是否启用仪表盘统计快照预热"),
            cfgUpgradeLegacyValue("app.ops.dashboard.snapshot.refresh-interval", "PT1M", "仪表盘统计快照刷新间隔", "PT30S"),
            cfg("app.ops.dashboard.snapshot.refresh-budget", "PT3S", "仪表盘统计快照单轮刷新预算"),
            cfgUpgradeLegacyValue("app.ops.dashboard.snapshot.fresh-ttl", "PT2M", "仪表盘统计快照新鲜 TTL", "PT30S"),
            cfgUpgradeLegacyValue("app.ops.dashboard.snapshot.stale-grace", "PT15M", "仪表盘统计快照过期兜底宽限", "PT5M"),
            cfg("app.ops.dashboard.read-model-snapshot.fresh-ttl", "PT2M", "仪表盘读模型快照新鲜 TTL"),
            cfg("app.ops.dashboard.read-model-snapshot.stale-grace", "PT15M", "仪表盘读模型快照过期兜底宽限"),
            cfg("app.ops.dashboard.snapshot.default-task-runtime-limit", "50", "仪表盘预热 Task Runtime 默认数量"),
            cfg("app.ops.metrics.cache-ttl", "PT15S", "运维指标接口本地缓存 TTL"),
            cfg("app.ops.metrics.instant-window", "PT60S", "运维速率指标即时窗口"),
            cfg("app.ops.metrics.stable-window", "PT5M", "运维速率指标稳定窗口"),
            cfg("app.ops.metrics.consumer-no-progress-grace", "PT10M", "队列消费者无进展判定宽限时间"),
            cfg("app.ops.metrics.ewma-alpha", "0.25", "运维速率 EWMA 平滑系数"),
            cfg("app.aggregation.work-queue.worker.processing-stall-threshold", "PT10M", "聚合运行队列处理中卡住判定阈值"),
            cfg("app.ops.task-runtime.overview.max-limit", "200", "运行任务概览最大返回数量"),
            cfg("app.ops.task-runtime.overview.cache-ttl", "PT5S", "运行任务概览本地缓存 TTL"),
            cfg("app.ops.runtime-dlq.cache-ttl", "PT5S", "运行队列 DLQ 概览本地缓存 TTL"),
            cfg("app.ops.request-audit.summary-sample-limit", "50000", "请求审计摘要最大采样行数"),
            cfg("app.ops.worker-job-audit.summary-sample-limit", "50000", "Worker 审计摘要最大采样行数"),
            cfg("app.ops.rabbit-management.enabled", "true", "是否启用 RabbitMQ 管理 API 速率采样"),
            cfg("app.ops.rabbit-management.base-url", "", "RabbitMQ 管理 API 基础地址"),
            cfg("app.ops.rabbit-management.vhost", "/", "RabbitMQ 管理 API vhost"),
            cfg("app.ops.rabbit-management.connect-timeout", "PT3S", "RabbitMQ 管理 API 连接超时"),
            cfg("app.ops.rabbit-management.request-timeout", "PT5S", "RabbitMQ 管理 API 请求超时"),
            cfg("app.ops.rabbit-management.initial-delay-ms", "10000", "RabbitMQ 管理采样启动延迟(毫秒)"),
            cfg("app.ops.rabbit-management.fixed-delay-ms", "10000", "RabbitMQ 管理采样间隔(毫秒)"),
            cfg("app.ops.rabbit-dlq.sampled-queue-limit", "3", "RabbitMQ DLQ 单次列表最多采样队列数"),
            cfg("app.ops.rabbit-dlq.queue-snapshot-cache-ttl", "PT3S", "RabbitMQ DLQ 队列快照缓存 TTL"),
            cfg("app.ops.rabbit-dlq.sample-cache-ttl", "PT5S", "RabbitMQ DLQ 消息样本缓存 TTL"),
            cfg("app.ops.service-restart.enabled", "false", "是否允许 ops-service 触发 Kubernetes Deployment 重启"),
            cfg("app.ops.service-restart.kubernetes-api-base-url", "", "Kubernetes API 地址，留空使用集群内默认地址"),
            cfg("app.ops.service-restart.namespace", "", "服务重启目标命名空间，留空使用当前 Pod 命名空间"),
            cfg("app.ops.service-restart.allowed-services", String.join(",",
                    "ircs-api-gateway",
                    "ircs-aggregation-worker",
                    "ircs-catalog-service",
                    "ircs-config-service",
                    "ircs-content-service",
                    "ircs-credential-service",
                    "ircs-identity-service",
                    "ircs-ingestion-worker",
                    "ircs-interaction-service",
                    "ircs-magnet-service",
                    "ircs-metadata-worker",
                    "ircs-normalization-worker",
                    "ircs-notification-worker",
                    "ircs-ops-service",
                    "ircs-portal-service",
                    "ircs-scraper-service",
                    "ircs-search-service",
                    "ircs-storage-service",
                    "ircs-task-service"), "允许从系统设置页重启的 Deployment 列表"),
            cfg("app.ops.service-restart.request-timeout", "PT10S", "Kubernetes 重启请求超时"),
            cfg("app.ops-alert.first-page-cache.warmup.enabled", "true", "是否启用告警首页列表预热"),
            cfg("app.ops-alert.first-page-cache.warmup.refresh-interval", "PT15S", "告警首页列表预热刷新间隔"),
            cfg("app.ops-alert.first-page-cache.warmup.page-size", "20", "告警首页列表预热页大小"),
            cfg("app.rate-metrics.key-prefix", "ircs:metrics:rate", "运行速率指标 Valkey Key 前缀"),
            cfg("app.rate-metrics.bucket-size", "PT10S", "运行速率指标桶大小"),
            cfg("app.rate-metrics.bucket-ttl", "PT30M", "运行速率指标桶 TTL"),
            cfg("app.rate-metrics.bucket-index-retention", "PT31M", "运行速率指标桶索引保留时间"),
            cfg("app.log-retention.enabled", "true", "是否启用通用日志滚动保留清理"),
            cfg("app.log-retention.default-retention", "P30D", "通用日志默认保留时间"),
            cfg("app.log-retention.initial-delay-ms", "60000", "通用日志保留清理启动延迟(毫秒)"),
            cfg("app.log-retention.fixed-delay-ms", "3600000", "通用日志保留清理间隔(毫秒)"),
            cfg("app.log-retention.target.audit-es.enabled", "true", "是否启用 Audit ES 日志保留清理"),
            cfg("app.log-retention.target.audit-es.retention", "P30D", "Audit ES 日志保留时间"),
            cfg("app.portal.cache.enabled", "true", "是否启用门户读模型缓存"),
            cfg("app.portal.cache.metadata-ttl", "PT5M", "门户元数据缓存 TTL"),
            cfg("app.portal.cache.home-ttl", "PT60S", "门户首页缓存 TTL"),
            cfg("app.portal.cache.explore-ttl", "PT60S", "门户探索页缓存 TTL"),
            cfgUpgradeLegacyValue("app.portal.cache.detail-ttl", "PT15M", "门户详情页缓存 TTL", "PT5M"),
            cfg("app.portal.cache.dictionary-ttl", "PT30M", "门户标准字典缓存 TTL"),
            cfg("app.search.cache.enabled", "true", "是否启用搜索门户读模型缓存"),
            cfg("app.search.cache.portal-suggest-ttl", "PT60S", "搜索建议缓存 TTL"),
            cfg("app.search.cache.portal-recommend-ttl", "PT60S", "搜索推荐缓存 TTL"),
            cfg("app.search.cache.portal-public-version-key", "ircs:portal:public:version", "门户公开版本缓存 Key"),
            cfg("app.search.cache.portal-version-refresh-interval", "PT2S", "门户公开版本刷新间隔"),
            cfg("app.catalog.cache.enabled", "true", "是否启用目录读模型缓存"),
            cfg("app.catalog.cache.standard-dictionary-ttl", "PT10M", "标准字典缓存 TTL"),
            cfg("app.credential.cache.enabled", "true", "是否启用凭证概览缓存"),
            cfg("app.credential.cache.summary-ttl", "PT60S", "凭证概览缓存 TTL"),
            cfg("app.credential.lease-cache.ttl", "PT30S", "凭证租约缓存 TTL"),
            cfg("app.magnet.cache.enabled", "true", "是否启用磁链读模型缓存"),
            cfg("app.magnet.cache.provider-ttl", "PT5M", "磁链 Provider 缓存 TTL"),
            cfg("app.magnet.cache.approved-links-ttl", "PT60S", "已审核磁链缓存 TTL"),
            cfg("app.magnet.real-provider.enabled", "false", "是否启用真实磁链 Provider 爬取"),
            cfg("app.magnet.real-provider.allowlist",
                    "YTS_BZ,THE_PIRATE_BAY,EZTV,EXT_TO,THE_PIRATE_BAY_FRONTEND",
                    "允许调用的真实磁链 Provider 类型或编码"),
            cfg("app.magnet.auto-search.enabled", "false", "是否启用聚合视频磁链自动补链"),
            cfg("app.magnet.auto-search.batch-size", "10", "磁链自动补链每轮聚合视频数量"),
            cfg("app.magnet.auto-search.cooldown", "PT12H", "同一聚合视频自动补链冷却时间"),
            cfg("app.magnet.auto-search.initial-delay-ms", "60000", "磁链自动补链启动延迟(毫秒)"),
            cfg("app.magnet.auto-search.fixed-delay-ms", "300000", "磁链自动补链轮询间隔(毫秒)"),
            cfg("app.magnet.work-queue.submission.enabled", "true", "是否允许磁链补链任务投递到运行队列"),
            cfg("app.magnet.work-queue.worker.enabled", "true", "是否启用磁链补链运行队列 worker"),
            cfg("app.magnet.work-queue.worker.batch-size", "5", "磁链补链 worker 每轮领取数量"),
            cfg("app.magnet.work-queue.worker.visibility-seconds", "900", "磁链补链 worker 任务可见性超时(秒)"),
            cfg("app.magnet.work-queue.worker.max-retries", "3", "磁链补链 worker 最大重试次数"),
            cfg("app.magnet.work-queue.worker.max-backoff-seconds", "900", "磁链补链 worker 最大退避时间(秒)"),
            cfg("app.magnet.work-queue.worker.initial-delay-ms", "10000", "磁链补链 worker 启动延迟(毫秒)"),
            cfg("app.magnet.work-queue.worker.fixed-delay-ms", "1000", "磁链补链 worker 空转轮询间隔(毫秒)"),
            cfg("app.magnet.work-queue.worker.heartbeat-initial-delay-ms", "5000", "磁链补链 worker 心跳启动延迟(毫秒)"),
            cfg("app.magnet.work-queue.worker.heartbeat-fixed-delay-ms", "15000", "磁链补链 worker 心跳间隔(毫秒)"),
            cfg("app.task.cluster-lock.enabled", "true", "任务服务集群锁开关"),
            cfg("app.task.cluster-lock.ttl", "PT30S", "任务服务集群锁 TTL"),
            cfg("app.task.cluster-lock.worker-id", "", "任务服务集群锁 Worker 标识，留空自动生成"),
            cfg("app.task.default-seed.enabled", "true", "是否启用任务默认种子初始化"),
            cfg("app.task.internal-access.require-token", "false", "任务内部接口是否要求 Token"),
            cfg("app.task.internal-access.token", "", "任务内部接口访问 Token"),
            cfg("app.task.internal-access.required-scope", "task:maintenance", "任务内部接口所需权限范围"),
            cfg("app.task.queue.enabled", "true", "是否启用任务运行队列"),
            cfg("app.task.queue.max-pages-per-run", "0", "任务队列单轮最大分页数，0 表示不限制"),
            cfg("app.task.queue.retry.max-retries", "3", "任务队列最大重试次数"),
            cfg("app.task.queue.dispatch.concurrency", "2", "任务队列调度线程数"),
            cfg("app.task.queue.dispatch.queue-capacity", "500", "任务队列调度线程池队列容量"),
            cfg("app.task.queue.dispatch.await-termination-seconds", "120", "任务队列调度线程池关闭等待秒数"),
            cfg("app.task.runner.concurrency", "1", "任务执行线程数"),
            cfg("app.task.runner.queue-capacity", "500", "任务执行线程池队列容量"),
            cfg("app.task.runner.await-termination-seconds", "300", "任务执行线程池关闭等待秒数"),
            cfg("app.task.runner.scraper-base-url", "http://ircs-scraper-service.ircs-dev.svc.cluster.local:8080", "任务执行器调用 scraper-service 的基础地址"),
            cfg("app.task.runner.scraper-request-timeout", "10s", "任务执行器调用 scraper-service 的请求超时"),
            cfg("app.task.snapshot.flush.min-dirty-age", "PT5S", "任务快照最小脏数据停留时间"),
            cfg("app.task.snapshot.flush.batch-size", "100", "任务快照刷新批次"),
            cfg("app.task.snapshot.ttl", "PT24H", "任务运行快照 TTL"),
            cfg("app.task.runtime.event-stream-maxlen", "50000", "任务运行事件流最大长度"),
            cfg("app.task.runtime.repair.batch-size", "100", "任务运行态修复批次"),
            cfg("app.task.trend-discovery.enabled", "true", "是否启用任务趋势发现"),
            cfg("app.task.trend-discovery.max-keywords", "50", "趋势发现最大关键词数"),
            cfg("app.task.trend-discovery.max-data-sources", "0", "趋势发现最大数据源数，0 表示不限制"),
            cfg("app.task.media-request.enabled", "true", "是否启用门户求片自动采集调度"),
            cfg("app.task.media-request.initial-delay-ms", "30000", "求片调度启动延迟(毫秒)"),
            cfg("app.task.media-request.fixed-delay-ms", "60000", "求片调度轮询间隔(毫秒)"),
            cfg("app.task.media-request.batch-size", "20", "求片调度每轮领取数量"),
            cfg("app.task.media-request.start-page", "1", "求片采集起始页"),
            cfg("app.task.media-request.end-page", "1", "求片采集结束页"),
            cfg("app.task.media-request.request-fixed-delay-ms", "0", "求片采集任务内部固定延迟(毫秒)"),
            cfg("app.task.media-request.max-data-sources", "0", "求片采集最大数据源数，0 表示不限制"),
            cfg("app.task.scheduler.enabled", "true", "是否启用任务调度器"),
            cfg("app.aggregation.work-queue.batch-size", "100", "聚合运行队列基准批次"),
            cfg("app.aggregation.work-queue.batch-size.min", "20", "聚合运行队列最小批次"),
            cfg("app.aggregation.work-queue.batch-size.max", "500", "聚合运行队列最大批次"),
            cfg("app.aggregation.work-queue.worker.batch-size", "100", "聚合 Valkey worker 每轮领取数量"),
            cfg("app.aggregation.work-queue.worker.visibility-seconds", "600", "聚合 Valkey worker 任务可见性超时(秒)"),
            cfg("app.aggregation.work-queue.worker.max-retries", "8", "聚合 Valkey worker 最大重试次数"),
            cfg("app.aggregation.work-queue.worker.max-backoff-seconds", "900", "聚合 Valkey worker 最大退避时间(秒)"),
            cfg("app.aggregation.work-queue.worker.initial-delay-ms", "10000", "聚合 Valkey worker 启动延迟(毫秒)"),
            cfg("app.aggregation.work-queue.worker.fixed-delay-ms", "1000", "聚合 Valkey worker 空转轮询间隔(毫秒)"),
            cfg("app.maintenance.sanitize.batch-size", "1000", "全量清洗任务每批次数量"),
            cfg("app.maintenance.aggregation-reset.batch-size", "1000", "聚合重置任务每批次数量"),
            cfg("app.maintenance.rule-cleaning.batch-size", "100", "规则清洗每批次数量"),
            cfg("app.maintenance.search.reindex-batch-size", "500", "搜索索引全量重建每批次数量"),
            cfg("app.maintenance.batch-size.min", "200", "运维任务最小批次"),
            cfg("app.maintenance.batch-size.max", "2000", "运维任务最大批次"),
            cfgUpgradeLegacyFalse("app.maintenance.playlist-repair.enabled", "true", "是否自动修复无剧集的视频"),
            cfg("app.maintenance.playlist-repair.batch-size", "50", "播放列表修复基准批次"),
            cfg("app.maintenance.playlist-repair.batch-size.min", "10", "播放列表修复最小批次"),
            cfg("app.maintenance.playlist-repair.batch-size.max", "100", "播放列表修复最大批次"),
            cfg("app.search.work-queue.batch-size", "500", "搜索同步运行队列基准批次"),
            cfg("app.search.work-queue.batch-size.min", "100", "搜索同步运行队列最小批次"),
            cfg("app.search.work-queue.batch-size.max", "1000", "搜索同步运行队列最大批次"),
            cfg("app.search.work-queue.worker.enabled", "false", "是否启用搜索 Valkey 运行队列 worker"),
            cfg("app.search.work-queue.worker.batch-size", "100", "搜索 Valkey worker 每轮领取数量"),
            cfg("app.search.work-queue.worker.visibility-seconds", "300", "搜索 Valkey worker 任务可见性超时(秒)"),
            cfg("app.search.work-queue.worker.max-retries", "5", "搜索 Valkey worker 最大重试次数"),
            cfg("app.search.work-queue.worker.max-backoff-seconds", "300", "搜索 Valkey worker 最大退避时间(秒)"),
            cfg("app.search.work-queue.worker.initial-delay-ms", "10000", "搜索 Valkey worker 启动延迟(毫秒)"),
            cfg("app.search.work-queue.worker.fixed-rate-ms", "1000", "搜索 Valkey worker 固定触发频率(毫秒)"),
            cfg("app.audit.es-replication.enabled", "true", "是否将审计日志投递到 ES 投影队列"),
            cfg("app.search.audit-es-replication.worker.enabled", "true", "是否启用审计日志 ES 投影 Valkey worker"),
            cfg("app.search.audit-es-replication.worker.batch-size", "100", "审计日志 ES 投影 worker 每轮领取数量"),
            cfg("app.search.audit-es-replication.worker.visibility-seconds", "600", "审计日志 ES 投影任务可见性超时(秒)"),
            cfg("app.search.audit-es-replication.worker.max-retries", "8", "审计日志 ES 投影最大重试次数"),
            cfg("app.search.audit-es-replication.worker.max-backoff-seconds", "900", "审计日志 ES 投影最大退避时间(秒)"),
            cfg("app.search.audit-es-replication.worker.initial-delay-ms", "10000", "审计日志 ES 投影 worker 启动延迟(毫秒)"),
            cfg("app.search.audit-es-replication.worker.fixed-rate-ms", "1000", "审计日志 ES 投影 worker 固定触发频率(毫秒)"),
            cfg("app.search.audit-es-replication.worker.heartbeat-fixed-rate-ms", "15000", "审计日志 ES 投影 worker 心跳触发频率(毫秒)"),
            cfg("app.search.reconciliation.enabled", "true", "是否启用ES-DB数据对账定时任务"),
            cfg("app.storage.base-path", "./storage", "文件存储本地根目录"),
            cfg("app.storage.public-path", "/media", "静态资源Web访问前缀"),
            cfg("app.storage.path.prefix.cover", "covers", "影视封面存储目录前缀"),
            cfg("app.storage.path.prefix.avatar", "avatars", "用户头像存储目录前缀"),
            cfg("app.maintenance.queue-retention-days", "7", "采集队列日志保留天数"),
            cfgUpgradeLegacyFalse("app.storage.image.download.enabled", "true", "是否开启图片本地化下载"),
            cfg("app.storage.image.max-retries", "3", "图片下载/上传最大重试次数"),
            cfg("app.storage.image.timeout-min", "30", "图片处理超时时间(分钟)"),
            cfg("app.storage.image.download.batch-size", "100", "图片下载任务基准批次"),
            cfg("app.storage.image.download.batch-size.min", "20", "图片下载任务最小批次"),
            cfg("app.storage.image.download.batch-size.max", "200", "图片下载任务最大批次"),
            cfg("app.storage.image.download-backfill.enabled", "true", "是否启用封面下载任务安全回填"),
            cfg("app.storage.image.download-backfill.batch-size", "25", "封面下载任务安全回填每轮投递数量"),
            cfg("app.storage.image.download-backfill.initial-delay-ms", "60000", "封面下载任务安全回填启动延迟(毫秒)"),
            cfg("app.storage.image.download-backfill.fixed-delay-ms", "30000", "封面下载任务安全回填轮询间隔(毫秒)"),
            cfg("app.storage.image.cleanup.batch-size", "100", "图片清理任务基准批次"),
            cfg("app.storage.image.cleanup.batch-size.min", "50", "图片清理任务最小批次"),
            cfg("app.storage.image.cleanup.batch-size.max", "500", "图片清理任务最大批次"),
            cfg("app.storage.r2.work-queue.worker.enabled", "false", "是否启用存储 R2 Valkey 运行队列 worker"),
            cfg("app.storage.r2.work-queue.worker.batch-size", "25", "存储 R2 Valkey worker 每轮领取数量"),
            cfg("app.storage.r2.work-queue.worker.visibility-seconds", "900", "存储 R2 Valkey worker 任务可见性超时(秒)"),
            cfg("app.storage.r2.work-queue.worker.max-retries", "8", "存储 R2 Valkey worker 最大重试次数"),
            cfg("app.storage.r2.work-queue.worker.max-backoff-seconds", "1800", "存储 R2 Valkey worker 最大退避时间(秒)"),
            cfg("app.storage.r2.work-queue.worker.initial-delay-ms", "30000", "存储 R2 Valkey worker 启动延迟(毫秒)"),
            cfg("app.storage.r2.work-queue.worker.fixed-delay-ms", "5000", "存储 R2 Valkey worker 空转轮询间隔(毫秒)"),
            cfgUpgradeLegacyFalse("app.storage.r2.enabled", "true", "是否启用 Cloudflare R2"),
            cfg("app.storage.r2.bucket-name", "ircs", "R2 Bucket Name"),
            cfg("app.storage.r2.public-domain", "img.mnnu.eu.org", "R2 公开访问域名"),
            cfgUpgradeLegacyFalse("app.ai.llm.enabled", "true", "是否启用 LLM 辅助清洗"),
            cfg("app.ai.llm.model", "gemma-4-31b-it", "LLM 模型名称"),
            cfg("app.ai.llm.rate-limit.rpm", "10", "LLM RPM 限制"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.enabled", "false", "是否启用 LLM 清洗 Valkey 运行队列 worker"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.batch-size", "100", "LLM 清洗 Valkey worker 每轮领取数量"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.visibility-seconds", "900", "LLM 清洗 Valkey worker 任务可见性超时(秒)"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.max-retries", "8", "LLM 清洗 Valkey worker 最大重试次数"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.max-backoff-seconds", "1800", "LLM 清洗 Valkey worker 最大退避时间(秒)"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.initial-delay-ms", "30000", "LLM 清洗 Valkey worker 启动延迟(毫秒)"),
            cfg("app.normalization.llm-cleaning.work-queue.worker.fixed-delay-ms", "5000", "LLM 清洗 Valkey worker 空转轮询间隔(毫秒)"),
            cfg("app.ai.llm.prompt.language", llmPrompt("linguistic", "standard list"), "LLM 语言清洗 Prompt 模板"),
            cfg("app.ai.llm.prompt.area", llmPrompt("geographic", "country or region list"), "LLM 地区清洗 Prompt 模板"),
            cfg("app.ai.llm.prompt.genre", llmPrompt("video genre", "genre list"), "LLM 题材清洗 Prompt 模板"),
            cfg("app.ai.llm.prompt.category", llmPrompt("video category", "category list"), "LLM 分类清洗 Prompt 模板"),
            cfg("global.traffic.safety-floor-ms", "3000", "源站+出口IP流量安全底线(毫秒)"),
            cfg("global.traffic.max-wait-ms", "120000", "源站+出口IP流量控制最大容忍等待时间(毫秒)"),
            cfg("app.identity.auth-rate-limit.enabled", "true", "是否启用身份服务登录限流"),
            cfg("app.identity.auth-rate-limit.max-attempts", "30", "身份登录限流窗口最大尝试次数"),
            cfg("app.identity.auth-rate-limit.window", "PT5M", "身份登录限流窗口"),
            cfg("app.identity.pow.enabled", "true", "是否启用身份服务 PoW 防护"),
            cfg("app.scraper.traffic.source-enabled", "true", "是否启用采集服务按数据源流量闸门"),
            cfg("app.scraper.traffic.enabled", "true", "是否启用采集服务全局流量闸门"),
            cfgUpgradeLegacyValue("app.scraper.traffic.egress-id", "", "采集服务流量出口标识", "unknown"),
            cfg("app.scraper.traffic.default-gap-ms", "1000", "采集服务同出口默认请求间隔(毫秒)"),
            cfg("app.scraper.traffic.max-wait", "PT2M", "采集服务流量闸门最大等待时间"),
            cfg("app.scraper.traffic.ttl", "PT10M", "采集服务流量闸门 Valkey 状态 TTL"),
            cfg("app.metadata.public-traffic.enabled", "true", "是否启用元数据公网 Provider 全局流量闸门"),
            cfg("app.metadata.public-traffic.default-gap-ms", "150", "元数据公网 Provider 同出口默认请求间隔(毫秒)"),
            cfg("app.metadata.public-traffic.max-wait", "PT2M", "元数据公网 Provider 流量闸门最大等待时间"),
            cfg("app.metadata.public-traffic.ttl", "PT10M", "元数据公网 Provider 流量闸门 Valkey 状态 TTL"),
            cfg("app.metadata.tmdb.enabled", "true", "启用 TMDB 数据源"),
            cfg("app.metadata.tmdb.min-delay-ms", "200", "TMDB 请求最小延迟"),
            cfg("app.metadata.tmdb.max-delay-ms", "500", "TMDB 请求最大延迟"),
            cfg("app.metadata.tmdb.proxy.enabled", "false", "TMDB 是否走代理"),
            cfg("app.metadata.tmdb.proxy.type", "HTTP", "TMDB 代理类型"),
            cfg("app.metadata.tmdb.proxy.host", "", "TMDB 代理主机"),
            cfg("app.metadata.tmdb.proxy.port", "0", "TMDB 代理端口"),
            cfg("app.metadata.tmdb.proxy.username", "", "TMDB 代理用户名"),
            cfg("app.metadata.tmdb.proxy.password", "", "TMDB 代理密码"),
            cfg("app.metadata.douban.enabled", "true", "启用豆瓣爬虫"),
            cfg("app.metadata.douban.min-delay-ms", "2000", "豆瓣请求最小延迟"),
            cfg("app.metadata.douban.max-delay-ms", "5000", "豆瓣请求最大延迟"),
            cfg("app.metadata.douban.proxy.enabled", "false", "豆瓣是否走代理"),
            cfg("app.metadata.douban.proxy.type", "HTTP", "豆瓣 代理类型"),
            cfg("app.metadata.douban.proxy.host", "", "豆瓣 代理主机"),
            cfg("app.metadata.douban.proxy.port", "0", "豆瓣 代理端口"),
            cfg("app.metadata.douban.proxy.username", "", "豆瓣 代理用户名"),
            cfg("app.metadata.douban.proxy.password", "", "豆瓣 代理密码"),
            cfg("app.metadata.rotten-tomatoes.enabled", "true", "启用烂番茄爬虫"),
            cfg("app.metadata.rotten-tomatoes.min-delay-ms", "2000", "烂番茄请求最小延迟"),
            cfg("app.metadata.rotten-tomatoes.max-delay-ms", "5000", "烂番茄请求最大延迟"),
            cfg("app.metadata.rotten-tomatoes.proxy.enabled", "false", "烂番茄是否走代理"),
            cfg("app.metadata.rotten-tomatoes.proxy.type", "HTTP", "烂番茄 代理类型"),
            cfg("app.metadata.rotten-tomatoes.proxy.host", "", "烂番茄 代理主机"),
            cfg("app.metadata.rotten-tomatoes.proxy.port", "0", "烂番茄 代理端口"),
            cfg("app.metadata.rotten-tomatoes.proxy.username", "", "烂番茄 代理用户名"),
            cfg("app.metadata.rotten-tomatoes.proxy.password", "", "烂番茄 代理密码"),
            cfg("app.magnet.traffic.enabled", "true", "是否启用磁链 Provider 流量闸门"),
            cfgUpgradeLegacyValue("app.magnet.traffic.egress-id", "", "磁链 Provider 流量出口标识", "unknown"),
            cfg("app.magnet.traffic.default-gap-ms", "3000", "磁链 Provider 同出口默认请求间隔(毫秒)"),
            cfg("app.magnet.traffic.provider-gap-ms",
                    "YTS_BZ=3000,THE_PIRATE_BAY=8000,EZTV=3000,EXT_TO=15000,THE_PIRATE_BAY_FRONTEND=15000",
                    "磁链 Provider 单站请求间隔覆盖(毫秒)，格式 PROVIDER=MS"),
            cfg("app.magnet.traffic.max-wait", "PT2M", "磁链 Provider 流量闸门最大等待时间"),
            cfg("app.magnet.traffic.ttl", "PT10M", "磁链 Provider 流量闸门 Valkey 状态 TTL"),
            cfg("app.storage.image.traffic.enabled", "true", "是否启用图片下载流量闸门"),
            cfgUpgradeLegacyValue("app.storage.image.traffic.egress-id", "", "图片下载流量出口标识", "unknown"),
            cfg("app.storage.image.traffic.global-gap-ms", "500", "图片下载全局请求间隔(毫秒)"),
            cfg("app.storage.image.traffic.domain-gap-ms", "1000", "图片下载同域名请求间隔(毫秒)"),
            cfg("app.storage.image.traffic.max-wait", "PT2M", "图片下载流量闸门最大等待时间"),
            cfg("app.storage.image.traffic.ttl", "PT10M", "图片下载流量闸门 Valkey 状态 TTL"),
            cfg("app.metadata.valkey-dispatcher.batch-size", "10", "元数据分发 Valkey worker 每轮领取数量"),
            cfg("app.metadata.valkey-dispatcher.visibility-timeout", "PT10M", "元数据分发 Valkey worker 任务可见性超时"),
            cfg("app.metadata.valkey-dispatcher.retry-delay", "PT5M", "元数据分发 Valkey worker 重试延迟"),
            cfg("app.metadata.valkey-dispatcher.initial-delay-ms", "10000", "元数据分发 Valkey worker 启动延迟(毫秒)"),
            cfg("app.metadata.valkey-dispatcher.fixed-delay-ms", "1000", "元数据分发 Valkey worker 空转轮询间隔(毫秒)"),
            cfgUpgradeLegacyValue(
                    "app.metadata.valkey-provider-worker.batch-size",
                    "40",
                    "元数据 Provider Valkey worker 每轮领取数量",
                    "10"),
            cfg("app.metadata.valkey-provider-worker.parallelism", "16", "元数据 Provider Valkey worker 并行处理线程数"),
            cfg("app.metadata.valkey-provider-worker.visibility-timeout", "PT10M", "元数据 Provider Valkey worker 任务可见性超时"),
            cfg("app.metadata.valkey-provider-worker.retry-delay", "PT2M", "元数据 Provider Valkey worker 重试延迟"),
            cfg("app.metadata.valkey-provider-worker.initial-delay-ms", "10000", "元数据 Provider Valkey worker 启动延迟(毫秒)"),
            cfg("app.metadata.valkey-provider-worker.fixed-delay-ms", "1000", "元数据 Provider Valkey worker 空转轮询间隔(毫秒)"),
            cfg("app.scraper.trend-sync.enabled", "true", "是否启用热门榜单自动同步"),
            cfg("app.scraper.trend-sync.cron", "0 0 3 * * ?", "热门榜单同步任务 CRON 表达式"),
            cfg("app.scraper.trend-sync.timezone", "Asia/Shanghai", "热门榜单同步任务执行时区"),
            cfg("app.search.sync.enabled", "true", "是否启用搜索增量同步"),
            cfg("app.search.sync.cron", "0 0/5 * * * ?", "搜索增量同步任务 CRON"));

    static {
        CORE_KEYS = DEFAULTS.stream().map(DefaultConfig::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private final PasswordEncoder passwordEncoder;

    public SystemConfigDefaults(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public List<DefaultConfig> all() {
        return DEFAULTS;
    }

    public ResolvedDefault resolve(DefaultConfig config) {
        return resolve(config, null);
    }

    public ResolvedDefault resolve(DefaultConfig config, Environment environment) {
        java.util.Optional<String> injectedValue = RuntimeInjectedConfig.find(environment, injectionKeys(config.key()));
        if (ADMIN_PASSWORD_KEY.equals(config.key())) {
            return new ResolvedDefault(
                    config.key(),
                    injectedValue.orElseGet(() -> passwordEncoder.encode(generateRandomPassword())),
                    config.description(),
                    config.upgradeLegacyFalse());
        }
        if (JWT_SECRET_KEY.equals(config.key())) {
            return new ResolvedDefault(
                    config.key(),
                    injectedValue.orElseGet(this::generateJwtSecret),
                    config.description(),
                    config.upgradeLegacyFalse());
        }
        return new ResolvedDefault(
                config.key(),
                injectedValue.orElse(config.defaultValue()),
                config.description(),
                config.upgradeLegacyFalse(),
                config.upgradeLegacyValue());
    }

    public boolean isCoreKey(String key) {
        return CORE_KEYS.contains(key);
    }

    public boolean isRequiredKey(String key) {
        return REQUIRED_KEYS.contains(key);
    }

    public java.util.Optional<String> staticDefaultValue(String key) {
        return DEFAULTS.stream()
                .filter(config -> config.key().equals(key))
                .findFirst()
                .map(DefaultConfig::defaultValue);
    }

    public ConfigMetadata metadata(String key) {
        if (key == null || key.isBlank()) {
            return ConfigMetadata.hot();
        }
        String normalized = key.trim();
        if (isKnownHotKey(normalized)) {
            return ConfigMetadata.hot();
        }
        if (isRestartRequiredKey(normalized)) {
            return ConfigMetadata.restartRequired(restartServices(normalized));
        }
        return ConfigMetadata.hot();
    }

    public List<String> injectionKeys(String key) {
        List<String> aliases = INJECTION_ALIASES.getOrDefault(key, List.of());
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(key), aliases.stream())
                .flatMap(SystemConfigDefaults::propertyAndEnvKeys)
                .distinct()
                .toList();
    }

    private static java.util.stream.Stream<String> propertyAndEnvKeys(String key) {
        return java.util.stream.Stream.of(key, toEnvironmentVariableName(key));
    }

    private static String toEnvironmentVariableName(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, 12);
    }

    private String generateJwtSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static DefaultConfig cfg(String key, String defaultValue, String description) {
        return new DefaultConfig(key, defaultValue, description, false, null);
    }

    private static DefaultConfig cfgUpgradeLegacyFalse(String key, String defaultValue, String description) {
        return new DefaultConfig(key, defaultValue, description, true, null);
    }

    private static DefaultConfig cfgUpgradeLegacyValue(
            String key,
            String defaultValue,
            String description,
            String upgradeLegacyValue) {
        return new DefaultConfig(key, defaultValue, description, false, upgradeLegacyValue);
    }

    private static boolean isRestartRequiredKey(String key) {
        return key.contains(".client-secret")
                || key.contains(".app-secret")
                || key.contains(".app-key")
                || key.contains(".password")
                || key.contains(".secret")
                || key.contains(".token")
                || key.contains(".access-key")
                || key.contains(".secret-key")
                || key.contains(".worker-id")
                || key.contains(".cluster-lock.")
                || key.contains(".thread-pool.")
                || key.contains(".concurrency")
                || key.contains(".queue-capacity")
                || key.contains(".await-termination")
                || key.endsWith(".config-listener.enabled")
                || key.contains(".base-url")
                || key.contains(".targets.")
                || key.startsWith("app.task.")
                || isCacheStartupKey(key)
                || key.startsWith("app.storage.r2.")
                || key.startsWith("app.storage.image.download-backfill.")
                || key.startsWith("app.normalization.valkey-worker.initial-delay-ms")
                || key.startsWith("app.normalization.valkey-worker.fixed-delay-ms")
                || key.startsWith("app.normalization.pending-watchdog.initial-delay-ms")
                || key.startsWith("app.normalization.pending-watchdog.fixed-delay-ms")
                || key.startsWith("app.magnet.auto-search.initial-delay-ms")
                || key.startsWith("app.magnet.auto-search.fixed-delay-ms")
                || key.startsWith("app.magnet.work-queue.worker.initial-delay-ms")
                || key.startsWith("app.magnet.work-queue.worker.fixed-delay-ms")
                || key.startsWith("app.magnet.work-queue.worker.heartbeat-initial-delay-ms")
                || key.startsWith("app.magnet.work-queue.worker.heartbeat-fixed-delay-ms")
                || key.startsWith("app.storage.base-path")
                || key.startsWith("app.storage.public-path")
                || key.startsWith("app.system-config.valkey-cache.")
                || key.startsWith("app.log-retention.initial-delay-ms")
                || key.startsWith("app.log-retention.fixed-delay-ms")
                || key.startsWith("app.search.work-queue.worker.initial-delay-ms")
                || key.startsWith("app.search.work-queue.worker.fixed-rate-ms")
                || key.startsWith("app.search.audit-es-replication.worker.initial-delay-ms")
                || key.startsWith("app.search.audit-es-replication.worker.fixed-rate-ms")
                || key.startsWith("app.search.audit-es-replication.worker.heartbeat-initial-delay-ms")
                || key.startsWith("app.search.audit-es-replication.worker.heartbeat-fixed-rate-ms")
                || "app.runtime-config.local-cache.ttl".equals(key)
                || "security.jwt.secret".equals(key)
                || "security.admin.password".equals(key);
    }

    private static boolean isKnownHotKey(String key) {
        return key.startsWith("app.ops.service-restart.")
                || key.startsWith("app.ops-alert.first-page-cache.warmup.")
                || key.startsWith("app.scraper.traffic.")
                || key.startsWith("app.magnet.traffic.")
                || key.startsWith("app.magnet.real-provider.")
                || (key.startsWith("app.magnet.auto-search.")
                && !key.equals("app.magnet.auto-search.initial-delay-ms")
                && !key.equals("app.magnet.auto-search.fixed-delay-ms"))
                || (key.startsWith("app.magnet.work-queue.")
                && !key.startsWith("app.magnet.work-queue.worker.initial-delay-ms")
                && !key.startsWith("app.magnet.work-queue.worker.fixed-delay-ms")
                && !key.startsWith("app.magnet.work-queue.worker.heartbeat-initial-delay-ms")
                && !key.startsWith("app.magnet.work-queue.worker.heartbeat-fixed-delay-ms"))
                || key.startsWith("app.storage.image.traffic.")
                || (key.startsWith("app.log-retention.")
                && !key.equals("app.log-retention.initial-delay-ms")
                && !key.equals("app.log-retention.fixed-delay-ms"))
                || key.startsWith("app.notification.webhook.")
                || key.startsWith("app.ops.dashboard.snapshot.")
                || key.startsWith("app.ops.dashboard.read-model-snapshot.")
                || key.startsWith("app.identity.auth-rate-limit.")
                || "app.identity.pow.enabled".equals(key)
                || "app.portal.cache.enabled".equals(key)
                || "app.search.cache.enabled".equals(key)
                || "app.catalog.cache.enabled".equals(key)
                || "app.credential.cache.enabled".equals(key)
                || "app.magnet.cache.enabled".equals(key);
    }

    private static boolean isCacheStartupKey(String key) {
        return key.startsWith("app.portal.cache.")
                || key.startsWith("app.search.cache.")
                || key.startsWith("app.catalog.cache.")
                || key.startsWith("app.credential.cache.")
                || key.startsWith("app.credential.lease-cache.")
                || key.startsWith("app.magnet.cache.");
    }

    private static List<String> restartServices(String key) {
        List<String> services = new ArrayList<>();
        addServiceIfMatches(services, key, "app.ops.", "ircs-ops-service");
        addServiceIfMatches(services, key, "app.gateway.", "ircs-api-gateway");
        addServiceIfMatches(services, key, "app.task.", "ircs-task-service");
        addServiceIfMatches(services, key, "app.search.", "ircs-search-service");
        addServiceIfMatches(services, key, "app.content.", "ircs-content-service");
        addServiceIfMatches(services, key, "app.metadata.", "ircs-metadata-worker");
        addServiceIfMatches(services, key, "app.storage.", "ircs-storage-service", "ircs-identity-service", "ircs-portal-service", "ircs-search-service");
        addServiceIfMatches(services, key, "app.identity.", "ircs-identity-service");
        addServiceIfMatches(services, key, "app.interaction.", "ircs-interaction-service");
        addServiceIfMatches(services, key, "app.aggregation.", "ircs-aggregation-worker");
        addServiceIfMatches(services, key, "app.scraper.", "ircs-scraper-service");
        addServiceIfMatches(services, key, "app.magnet.", "ircs-magnet-service");
        addServiceIfMatches(services, key, "app.credential.", "ircs-credential-service");
        addServiceIfMatches(services, key, "app.catalog.", "ircs-catalog-service");
        addServiceIfMatches(services, key, "app.portal.", "ircs-portal-service");
        addServiceIfMatches(services, key, "app.normalization.", "ircs-normalization-worker");
        addServiceIfMatches(services, key, "app.notification.", "ircs-notification-worker");
        addServiceIfMatches(services, key, "app.mail.", "ircs-notification-worker", "ircs-identity-service");
        addServiceIfMatches(services, key, "member.", "ircs-identity-service", "ircs-interaction-service");
        addServiceIfMatches(services, key, "security.", "ircs-identity-service", "ircs-api-gateway");
        addServiceIfMatches(services, key, "normalization.", "ircs-normalization-worker");
        addServiceIfMatches(services, key, "global.traffic.", "ircs-scraper-service", "ircs-metadata-worker", "ircs-magnet-service", "ircs-storage-service");
        if (key.startsWith("app.system-config.") || key.startsWith("app.runtime-config.")) {
            services.addAll(List.of(
                    "ircs-config-service",
                    "ircs-ops-service",
                    "ircs-portal-service",
                    "ircs-metadata-worker",
                    "ircs-normalization-worker",
                    "ircs-scraper-service",
                    "ircs-search-service",
                    "ircs-storage-service",
                    "ircs-aggregation-worker",
                    "ircs-notification-worker",
                    "ircs-content-service",
                    "ircs-identity-service",
                    "ircs-interaction-service"));
        }
        if (key.startsWith("app.log-retention.")) {
            services.addAll(List.of("ircs-search-service"));
        }
        return services.stream().distinct().toList();
    }

    private static void addServiceIfMatches(List<String> services, String key, String prefix, String... names) {
        if (key.startsWith(prefix)) {
            services.addAll(List.of(names));
        }
    }

    private static String llmPrompt(String expert, String validList) {
        return """
        You are a %s data cleaning expert.
        Task:
        - Map each raw item to one item from the valid %s.
        - If no confident match exists, return `"standard": null` and `"isNoise": true`.
        Rules:
        1. The `raw` field must be an exact verbatim copy of the input item.
        2. Do not normalize Traditional Chinese, case, spacing, punctuation, or symbols in `raw`.
        3. Respond with JSON only. Do not wrap the response in Markdown.
        Valid Standard Items:
        {standardItems}
        Input Raw Items:
        {rawItems}
        Return a JSON array with this schema:
        [{"raw":"string","standard":"string|null","isNoise":boolean}]
        """.formatted(expert, validList).trim();
    }

    public record DefaultConfig(
            String key,
            String defaultValue,
            String description,
            boolean upgradeLegacyFalse,
            String upgradeLegacyValue) {
        public DefaultConfig(String key, String defaultValue, String description) {
            this(key, defaultValue, description, false, null);
        }
    }

    public record ResolvedDefault(
            String key,
            String value,
            String description,
            boolean upgradeLegacyFalse,
            String upgradeLegacyValue) {
        public ResolvedDefault(String key, String value, String description) {
            this(key, value, description, false, null);
        }

        public ResolvedDefault(String key, String value, String description, boolean upgradeLegacyFalse) {
            this(key, value, description, upgradeLegacyFalse, null);
        }
    }

    public record ConfigMetadata(
            ActivationMode activationMode,
            List<String> restartServices) {
        public ConfigMetadata {
            restartServices = restartServices == null ? List.of() : List.copyOf(restartServices);
        }

        static ConfigMetadata hot() {
            return new ConfigMetadata(ActivationMode.HOT, List.of());
        }

        static ConfigMetadata restartRequired(List<String> restartServices) {
            return new ConfigMetadata(ActivationMode.RESTART_REQUIRED, restartServices);
        }
    }

    public enum ActivationMode {
        HOT,
        RESTART_REQUIRED
    }
}
