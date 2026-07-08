# IRCS V3 K8S skeleton

The files here are target deployment scaffolds, not final production secrets.

- `service-inventory.yaml` lists every target service and its route/queue role.
- `templates/app-deployment.yaml` is the standard app Deployment template.
- `edge-routing.yaml` preserves the old frontend paths while routing to new services.
- `dev/notification-worker-dev.yaml` is a conflict-safe dev workload scaffold.
- `dev/search-service-dev.yaml` runs the search sync service with runtime work queue workers enabled.
- `dev/storage-service-dev.yaml` runs the storage image-delete worker with queue listeners enabled.
- `dev/content-service-dev.yaml` runs the content image-unlink worker with queue listeners enabled.
- `dev/metadata-worker-dev.yaml` runs the metadata result collector, dispatcher, and TMDB provider worker with queue listeners enabled; TMDB credentials are leased from credential-service.
- `dev/credential-service-dev.yaml` runs the credential metadata API with secret payloads redacted from public responses and an internal provider lease API for trusted services.
- `dev/catalog-service-dev.yaml` runs the read-only catalog dictionary and data-source API with V1-compatible aliases.
- `dev/config-service-dev.yaml` runs the read-only system config API with sensitive value redaction.
- `dev/task-service-dev.yaml` runs the read-only collection task list/detail API with proxy password redaction.
- `dev/ingestion-worker-dev.yaml` runs the video ingest worker with ingest and playlist queue listeners enabled.
- `dev/normalization-worker-dev.yaml` runs the normalize worker with the normalize queue listener enabled.
- `dev/aggregation-worker-dev.yaml` runs the aggregation runtime work queue worker with raw/unified search sync publishing enabled.
- `dev/magnet-service-dev.yaml` runs the read-only magnet provider/cache API.
- `dev/identity-service-dev.yaml` runs the member auth/profile API. It is deployed and Ready.
- `dev/scraper-service-dev.yaml` runs the manual scrape API and publishes `q.ingest.video`. It is deployed and Ready.
- `dev/portal-service-dev.yaml` runs the portal read composition API. It is deployed and Ready.
- `dev/interaction-service-dev.yaml` runs the feedback/history/favorites read API. It is deployed and Ready.
- `dev/ops-service-dev.yaml` runs the dashboard read API and maintenance session compatibility API. It is deployed and Ready.
- `dev/resource-quota-dev.yaml` defines the low-profile dev quota, currently with `pods=24`.
- `dev/migrator-job-dev.yaml` runs the explicit schema migration Job.
- `dev/postgres-dev.yaml` runs namespace-local PostgreSQL for dev data reads.
- `dev/rabbitmq-dev.yaml` runs namespace-local RabbitMQ for dev queue consumers.
- `dev/elasticsearch-dev.yaml` runs namespace-local Elasticsearch for search-service work.
- `dev/valkey-dev.yaml` runs namespace-local Valkey for metadata pending-set coordination.

Worker-only dev manifests intentionally do not declare ClusterIP Services after
the Service quota pruning pass:

```text
dev/notification-worker-dev.yaml
dev/metadata-worker-dev.yaml
dev/ingestion-worker-dev.yaml
dev/normalization-worker-dev.yaml
dev/aggregation-worker-dev.yaml
```

Use `kubectl port-forward deployment/<worker> -n ircs-dev` if actuator access is
needed for these workers.

The dev manifests intentionally run the middlewares with a small resource
profile. They are for migration and integration testing, not production sizing.

```text
postgres:       request 100m/256Mi, limit 500m/512Mi, pvc 2Gi
rabbitmq:       request 100m/256Mi, limit 500m/768Mi, pvc 1Gi
elasticsearch:  request 100m/1Gi,   limit 1000m/2Gi, heap 512m, pvc 5Gi
valkey:         request 50m/128Mi,  limit 250m/256Mi, maxmemory 96mb, no pvc
java services:  request 25m/128Mi,  limit 250m/512Mi
migrator job:   request 50m/128Mi,  limit 500m/512Mi
search indices: number_of_replicas=0 for single-node dev
```

Single-replica dev Java Deployments use `Recreate` to avoid a temporary surge
Pod on the small single-node cluster.

V1 environment key names are recorded in:

```text
docs/k8s/v1-config-environment-map.md
```

Dev manifests should reuse the key names, but internal hosts must point to
`ircs-dev` services, not `ircs-system` services.

Image build:

```powershell
.\scripts\k8s\sync-image-pull-secret.ps1
kubectl apply -f deploy\k8s\dev\postgres-dev.yaml
kubectl apply -f deploy\k8s\dev\rabbitmq-dev.yaml
kubectl apply -f deploy\k8s\dev\elasticsearch-dev.yaml
kubectl apply -f deploy\k8s\dev\valkey-dev.yaml
kubectl rollout status deployment/valkey -n ircs-dev --timeout=180s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':platform:ircs-migrator' -ImageName ircs-migrator -Tag dev -Architecture arm64 -Os linux
kubectl delete job/ircs-migrator -n ircs-dev --ignore-not-found=true
kubectl apply -f deploy\k8s\dev\migrator-job-dev.yaml
kubectl wait --for=condition=complete job/ircs-migrator -n ircs-dev --timeout=300s
.\scripts\docker\push-service-image-jib.ps1 -Service ircs-notification-worker -Tag dev -Architecture arm64 -Os linux
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-search-service' -ImageName ircs-search-service -Tag dev -Architecture arm64 -Os linux
kubectl rollout restart deployment/ircs-search-service -n ircs-dev
kubectl rollout status deployment/ircs-search-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-storage-service' -ImageName ircs-storage-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\storage-service-dev.yaml
kubectl rollout status deployment/ircs-storage-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-content-service' -ImageName ircs-content-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\content-service-dev.yaml
kubectl rollout status deployment/ircs-content-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-metadata-worker' -ImageName ircs-metadata-worker -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\metadata-worker-dev.yaml
kubectl rollout status deployment/ircs-metadata-worker -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-credential-service' -ImageName ircs-credential-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\credential-service-dev.yaml
kubectl rollout status deployment/ircs-credential-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-catalog-service' -ImageName ircs-catalog-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\catalog-service-dev.yaml
kubectl rollout status deployment/ircs-catalog-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-config-service' -ImageName ircs-config-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\config-service-dev.yaml
kubectl rollout status deployment/ircs-config-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-task-service' -ImageName ircs-task-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\task-service-dev.yaml
kubectl rollout status deployment/ircs-task-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-ingestion-worker' -ImageName ircs-ingestion-worker -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\ingestion-worker-dev.yaml
kubectl rollout status deployment/ircs-ingestion-worker -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-normalization-worker' -ImageName ircs-normalization-worker -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\normalization-worker-dev.yaml
kubectl rollout status deployment/ircs-normalization-worker -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-aggregation-worker' -ImageName ircs-aggregation-worker -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\aggregation-worker-dev.yaml
kubectl rollout status deployment/ircs-aggregation-worker -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-magnet-service' -ImageName ircs-magnet-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\magnet-service-dev.yaml
kubectl rollout status deployment/ircs-magnet-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-identity-service' -ImageName ircs-identity-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\identity-service-dev.yaml
kubectl rollout status deployment/ircs-identity-service -n ircs-dev --timeout=240s
kubectl delete service ircs-notification-worker ircs-metadata-worker ircs-ingestion-worker ircs-normalization-worker ircs-aggregation-worker -n ircs-dev --ignore-not-found=true
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-scraper-service' -ImageName ircs-scraper-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\scraper-service-dev.yaml
kubectl rollout status deployment/ircs-scraper-service -n ircs-dev --timeout=240s
kubectl apply --dry-run=server -f deploy\k8s\dev\resource-quota-dev.yaml
kubectl apply -f deploy\k8s\dev\resource-quota-dev.yaml
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-portal-service' -ImageName ircs-portal-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\portal-service-dev.yaml
kubectl rollout status deployment/ircs-portal-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-interaction-service' -ImageName ircs-interaction-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\interaction-service-dev.yaml
kubectl rollout status deployment/ircs-interaction-service -n ircs-dev --timeout=240s
.\scripts\docker\push-project-image-jib.ps1 -ProjectPath ':services:ircs-ops-service' -ImageName ircs-ops-service -Tag dev -Architecture arm64 -Os linux
kubectl apply -f deploy\k8s\dev\ops-service-dev.yaml
kubectl rollout status deployment/ircs-ops-service -n ircs-dev --timeout=240s
```

The cluster node is ARM64. Do not push default x86/amd64 images to tags used by
`ircs-dev`.

The V1 Liquibase init-container pattern should be replaced by a single
`ircs-migrator` Job before rolling out service Deployments.

Current dev storage-service scope:

```text
owned:     q.storage.image_delete
deferred:  q.storage.image
paired:    q.storage.image_unlink is handled by ircs-content-service
R2:        disabled by default in dev
storage:   emptyDir /app/storage, no extra PVC
```

Current dev content-service scope:

```text
owned:     q.storage.image_unlink
publishes: image.event.unlinked to q.storage.image_delete
deferred:  content HTTP APIs and full raw/unified video ownership
```

Current dev metadata-worker scope:

```text
owned:     q.enrich.metadata, q.enrich.metadata.result, q.fetch.metadata.tmdb
publishes: search.sync.raw to runtime work queue
valkey:    pending-set coordination available
credentials: leases TMDB api_key values from ircs-credential-service internal API
tmdb:      worker enabled, dispatcher fan-out disabled in dev
deferred:  q.fetch.metadata.douban, q.fetch.metadata.rt, full external-provider fan-out
```

Current dev credential-service scope:

```text
owned:     /api/v1/credentials redacted metadata API and /internal/credentials/providers/{provider}/leases
database:  sys_credentials
secrets:   public response exposes payloadKeys and fingerprintSuffix only; internal lease response can return scalar secretPayload values
internal:  /internal/credentials/** requires X-IRCS-SERVICE-ID, X-IRCS-SERVICE-TOKEN, and X-IRCS-SERVICE-SCOPES
deferred:  credential write APIs, rotation, audit, NetworkPolicy hardening
```

Current dev catalog-service scope:

```text
owned:     read-only standard/raw category, genre, area, language, and data-source APIs
paths:     /api/v1/catalog/* plus V1 aliases under /api/v1/*
database:  current catalog and data_sources tables as migration bridge
deferred:  catalog write APIs, normalization maintenance, audit, physical DB split
```

Current dev config-service scope:

```text
owned:     read-only /api/v1/configs and /api/v1/configs/{key}
database:  system_configs as migration bridge
secrets:   sensitive values are redacted as value=null, sensitive=true
deferred:  config write APIs, connectivity tests, admin auth model, audit
```

Current dev task-service scope:

```text
owned:     read-only /api/v1/collection-tasks and /api/v1/collection-tasks/{id}
database:  collection_tasks and data_sources as migration bridge
secrets:   proxyPassword is never selected from PostgreSQL and is returned as null in detail responses
deferred:  create/update/delete, start/resume/pause/stop, logs, scheduler/watchdog, queue publishing
```

Current dev ingestion-worker scope:

```text
owned:     q.ingest.video, q.process.playlist_sync
publishes: q.normalize.video
database:  raw_videos, playlists, episodes, source_domains as migration bridge
deferred:  scraper HTTP fetch, normalization consumption, raw search sync, content-service internal source-domain API
```

Current dev normalization-worker scope:

```text
owned:     q.normalize.video
publishes: runtime-work:search.sync.raw, runtime-work:aggregation.raw-video
database:  raw_videos normalization status and lightweight field normalization as migration bridge
smoke:     q.normalize.video -> raw_videos READY -> runtime-work:search.sync.raw + runtime-work:aggregation.raw-video
deferred:  full V1 normalization handler pipeline, category/genre/person relation writes, FAILED recovery scheduler
```

Current dev aggregation-worker scope:

```text
owned:     runtime-work:aggregation.raw-video
publishes: runtime-work:search.sync.raw, runtime-work:search.sync.unified
database:  raw_videos aggregation status, raw_video_unified_video binding, basic unified_videos fields as migration bridge
smoke:     raw_videos READY/PENDING -> BOUND -> unified_videos/raw_video_unified_video -> runtime work queue -> ES
deferred:  full V1 graph clustering, relation-table aggregation, cover image merge, victim merge cleanup, interaction migration
```

Current dev magnet-service scope:

```text
owned:     GET /api/v1/magnet-providers, GET /api/v1/magnet-providers/{id}, GET /api/v1/magnets/unified/{id}
database:  magnet_providers and approved magnet_links as migration bridge
smoke:     provider list/detail and approved-only unified magnet link filtering
deferred:  external provider search, magnet_search_jobs writes, magnet_provider_runs writes, provider admin APIs, portal cache eviction
```

Current dev identity-service scope:

```text
owned:     GET/POST /api/portal/auth/** and basic /api/portal/profile/**
database:  members as migration bridge
cache:     Valkey auth/Pow/status keys
publishes: q.notification.mail when mail flows are enabled
smoke:     PoW -> register -> login -> profile get/update -> check-in -> password change -> reset-password -> relogin -> BANNED rejection -> cleanup
deferred:  /api/v1/auth/** admin auth, /api/v1/members/** admin member management, profile avatar upload
```

Current dev scraper-service scope:

```text
owned:     POST /api/v1/scraper/manual/init, GET /api/v1/scraper/manual/stream/{sessionId}
publishes: q.ingest.video
database:  data_sources read-only migration bridge
session:   single-replica in-memory session map for MVP
smoke:     directItems -> SSE CARD(PUBLISHED) -> q.ingest.video -> raw_videos/playlists/episodes
deferred:  Valkey-backed sessions, admin auth, collection task lifecycle, real external data source smoke, trend sync
```

Current dev portal-service scope:

```text
owned:     GET /api/portal/metadata, GET /api/portal/home
database:  shared PostgreSQL read model as migration bridge
cache:     Valkey available through dev config
smoke:     metadata keys and home composition keys over ClusterIP
deferred:  full portal page set, cache warmers, edge routing, physical read-model split
```

Current dev interaction-service scope:

```text
owned:     GET /api/portal/feedback/wall, GET /api/portal/interaction/history, GET /api/portal/interaction/favorites
database:  messages, members, watch history/favorites tables as migration bridge
auth:      ROLE_MEMBER JWT for member-scoped routes
smoke:     public feedback filtering plus authenticated history/favorites reads over ClusterIP
deferred:  q.interaction.watch_progress consumer, write APIs, admin moderation APIs, physical DB split
```

Current dev ops-service scope:

```text
owned:     GET /api/v1/dashboard/statistics, GET /api/v1/dashboard/trend,
           GET /api/v1/dashboard/distributions, GET /api/v1/dashboard/coverage,
           GET /api/v1/dashboard/efficiency, GET /api/v1/dashboard/metrics,
           GET /api/v1/dashboard/source-quality, POST /api/v1/dashboard/refresh,
           GET /api/v1/ops/maintenance/active, GET /api/v1/ops/maintenance/stream/{sessionId}
database:  shared PostgreSQL dashboard read model as migration bridge
metrics:   namespace-local RabbitMQ queue properties and Valkey INFO
session:   single-replica in-memory maintenance session map for MVP
smoke:     dashboard read endpoints plus expired maintenance stream over ClusterIP
deferred:  request audit collection, DLQ retry/discard, traffic reset, destructive maintenance commands, physical DB split
```
