# 成人文本识别服务化与免费模型接入

## 背景

线上发现 `other` 分类中存在无番号但标题、简介明显成人化的视频。旧版 `AdultContentAssessor` 主要依赖成人资源站标记、少量成人关键词和番号格式，无法覆盖“自拍、探花、泄密、显性中文描述”等无番号内容。

## 决策

- 新增内部服务 `ircs-content-safety-service`，提供 `POST /internal/v1/content-safety/adult-assessments:batch`。
- 第一阶段服务使用 Java/Spring Boot/Jib，与现有 CI/CD 保持一致。
- 免费方案优先：`sensitive-word` 作为可解释词证据层，`uget/sexual_content_dection` 作为后续可配置 remote model 推理端。
- aggregation-worker 不直接绑定模型实现，只依赖 `AdultAssessmentEvaluator`；content-safety 服务失败或超时时 fallback 到本地规则。
- `adultAssessment` 仍由统一 `AdultAssessment` 输出，所有规则、词库、模型判断都转为 `signals`，便于后台解释和人工纠正。

## 接口契约

- 请求：`AdultAssessmentBatchRequest`
  - `items[].id`
  - `title / aliasTitle / subtitle / description / remarks`
  - `categoryCode / categoryName`
  - `actorNames / directorNames / genreCodes`
  - `sources[]` 中包含资源站名称、成人站标记、源站分类、域名和截断后的 rawMetadata。
- 响应：`AdultAssessmentBatchResponse`
  - `engineVersion`
  - `items[].level`
  - `items[].adultRestricted`
  - `items[].confidence`
  - `items[].signals`
  - `items[].model`

## 运行边界

- 不上传图片、视频或用户数据，只处理视频元数据文本。
- 模型调用默认关闭：`APP_CONTENT_SAFETY_ADULT_MODEL_ENABLED=false`。
- 模型服务配置后通过 `APP_CONTENT_SAFETY_ADULT_MODEL_ENDPOINT` 调用，超时 fallback。
- 输入文本默认截断到 4096 字符。
- 服务仅内部访问，不配置公网路由。

## 后续

- 增加 Python/Transformers `uget/sexual_content_dection` 推理端或 sidecar。
- 基于线上泄漏样本、正常样本、难负样本做离线评测，确定阈值。
- 成人评估全量重跑后执行 Unified ES 重刷，确认匿名门户不再暴露成人限制内容。
