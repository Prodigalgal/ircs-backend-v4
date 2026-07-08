# IRCS V3 Microservice Target

This repository is the target microservice workspace for IRCS.

The V1 backend remains the migration source. V3 is intentionally structured by
runtime ownership instead of the old package tree:

- HTTP domain services own public/admin API routes.
- Worker services own RabbitMQ queues and scheduled background work.
- Shared modules contain only stable contracts and cross-cutting helpers.
- K8S edge routing preserves `/api/v1/**`, `/api/portal/**`, and `/media/**`.

Start with:

```powershell
.\gradlew projects
.\gradlew build
```

Primary design documents:

- [Target boundaries](docs/architecture/001-target-microservice-boundaries.md)
- [Cutover plan](docs/architecture/002-cutover-plan.md)
- [Migrator extraction](docs/migration/003-migrator-extraction.md)
