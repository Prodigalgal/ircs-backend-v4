# Ops Alert Self-Healing Five-Phase Goal

## Goal

完成 IRCS 自愈能力专项的五期目标：新增 `ops-alert-service` 作为告警与自愈控制面，升级 `notification-worker` 为通知通道执行器，逐步落地低风险自动恢复、服务级自愈、以及前端告警与自愈中心。

## Phase 1: Alert Control Plane

- 新增 `ircs-ops-alert-service`。
- 建立 `AlertEvent`、`Incident`、`HealingAction` 三类核心契约。
- 告警事件按 fingerprint 聚合为 Incident。
- 自愈策略第一阶段只生成 dry-run 动作，不修改真实运行态。
- 所有数据落库，支持后续前端查询、审计和回放。

## Phase 2: Notification Channel Executor

- 将 `notification-worker` 从邮件发送器升级为通知通道执行器。
- 支持 `NotificationCommand`、模板渲染、通道选择、频率限制、重试与投递历史。
- 优先保留当前邮件能力，再扩展 webhook、内部站内消息等通道。
- 通知投递结果回写，供 `ops-alert-service` 和前端告警中心展示。

## Phase 3: Low-Risk Auto-Healing

- 对 stale runtime queue、dashboard topic 降级、少量 Rabbit DLQ transient 消息提供自动恢复 playbook。
- 默认保留 dry-run 和手动触发能力，通过配置逐步放开自动执行。
- 每个 playbook 必须具备前置条件、冷却时间、最大次数、执行结果和恢复验证。

## Phase 4: Service-Level Self-Healing

- 接入 `ops-service` 已有 service restart 能力。
- 服务重启只允许白名单、冷却时间、最大尝试次数和 RBAC 校验通过后执行。
- 自愈动作必须记录执行证据和恢复验证，不允许无审计的重启。
- 默认生产仍可通过配置保持 dry-run。

## Phase 5: Alert & Self-Healing Center

- 前端新增告警与自愈中心。
- 展示 Incident、AlertEvent、HealingAction、通知投递历史和恢复证据。
- 支持策略开关、dry-run、手动 playbook、分组筛选和详情追踪。
- 保持局部失败局部降级，不让告警中心拖慢仪表盘主路径。

## Acceptance Criteria

- `ops-alert-service` 可构建、可测试、可镜像构建。
- Liquibase changelog 可追踪所有新增表与索引。
- 第一阶段事件接入、Incident 聚合、dry-run 自愈建议有测试覆盖。
- 后续每期必须保留兼容性说明、验证命令、部署状态和回滚点。
- 不引入 `@Autowired`，生产 Bean 使用构造器注入。
