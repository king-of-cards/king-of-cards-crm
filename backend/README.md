# Backend development in STS

The canonical backend is this existing Maven multi-module project. Import
`backend/pom.xml` and the four service modules into Spring Tool Suite; keep the
backend here rather than moving it into the React folder. Use Java 21.

For the current Windows/STS launch process, saved configurations, service order,
port checks and verified runtime status, follow
[the local startup guide](docs/windows-local-startup.md). Historical build
artifacts under `local-dev/` are evidence/logs, not active application sources.

## Services

| Module | Port | Role |
| --- | ---: | --- |
| `discovery-server` | 8761 | Eureka registry |
| `api-gateway` | 8080 | Frontend API gateway |
| `identity-service` | 8081 | Authentication and identity APIs |
| `crm-service` | 8082 | Lead and CRM APIs |

Start only a service whose port is free. The canonical launcher checks ports and
does not stop existing processes. Do not start a duplicate service instance.

## Storage and local configuration

**Google BigQuery is the only application database.** Identity and CRM use the
existing BigQuery project, dataset, tables and records. PostgreSQL/JPA/Flyway
configuration and SQL migration files still present in the repository are
historical artifacts and are not part of the current runtime. Do not provide
PostgreSQL credentials, start a PostgreSQL service, or run those migrations for
this application.

BigQuery project/dataset/location and external credential references are
validated by the launch configurations. Credential files stay outside the
repository. Keep Identity production mutations, CRM writes, directional writes
and schema initialization disabled unless the separate operation has been
explicitly approved. Read-only startup validation does not imply write approval.

The exact local environment, health URLs and security configuration are in
[the Windows/STS startup guide](docs/windows-local-startup.md). BigQuery schema
and cutover boundaries are described in [the cutover gates](docs/backend-bigquery-cutover-gates.md)
and [the directional lead dashboard guide](docs/directional-leads-dashboard.md).

## Build and verify

From this directory, the Maven wrapper can build the existing backend modules:

```powershell
.\mvnw.cmd -o -B verify
```

Use the full startup guide before launching or changing a running service. For
the existing React/Vite application and browser-facing local checks, see
[`../frontend/LOCAL-FRONTEND-TESTING.md`](../frontend/LOCAL-FRONTEND-TESTING.md).
