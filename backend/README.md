# Backend development in STS

## Import

1. In Spring Tool Suite, choose **File → Import → Maven → Existing Maven Projects**.
2. Select `C:\crm\king-of-cards-crm-backend-worktree\backend` and import the parent `pom.xml`.
3. Configure JDK 21 under **Window → Preferences → Java → Installed JREs**.
4. Run `mvnw.cmd clean verify` from this `backend/` directory to build and test all four services.

## Configure Identity and CRM launch configurations

Create Spring Boot launch configurations for `IdentityServiceApplication` and `CrmServiceApplication`. In each launch configuration, set the active profile to `dev` (or add `SPRING_PROFILES_ACTIVE=dev` under **Environment**).

Set these environment variables in the Identity launch configuration:

- `SPRING_PROFILES_ACTIVE=dev`
- `POSTGRES_HOST=localhost`
- `POSTGRES_PORT=5432`
- `IDENTITY_DB_USERNAME=koc_identity_dev_app`
- `IDENTITY_DB_PASSWORD` = the local password configured for that PostgreSQL role

Set these in the CRM launch configuration:

- `SPRING_PROFILES_ACTIVE=dev`
- `POSTGRES_HOST=localhost`
- `POSTGRES_PORT=5432`
- `CRM_DB_USERNAME=koc_crm_dev_app`
- `CRM_DB_PASSWORD` = the local password configured for that PostgreSQL role

Enter passwords only in the local STS launch environment. Do not put them in source files, logs, or Git. `.env.example` contains blank password values; Spring Boot does not load `.env` automatically.

## Start order

Run these four Spring Boot applications in separate STS launch sessions, in order:

1. `DiscoveryServerApplication` — port 8761
2. `IdentityServiceApplication` — port 8081, active profile `dev`
3. `CrmServiceApplication` — port 8082, active profile `dev`
4. `ApiGatewayApplication` — port 8080

Identity and CRM run Flyway against their respective DEV database at startup. Their current baseline migrations execute `SELECT 1` only; Hibernate uses `ddl-auto: validate`. No business tables are created.

## Verify

- Eureka dashboard: `http://localhost:8761`
- Direct service health: `http://localhost:8081/health` and `http://localhost:8082/health`
- Database-aware health: `http://localhost:8081/actuator/health` and `http://localhost:8082/actuator/health` should report `UP` when datasource connections and Flyway initialization succeed.
- Through the gateway: `http://localhost:8080/api/identity/health` and `http://localhost:8080/api/crm/health`

To check PostgreSQL connectivity independently, see `../infra/postgres/README.md`. The DEV datasource settings are in each service's `src/main/resources/application-dev.yml`; base `application.yml` retains service ports, names, and Eureka configuration. Production datasource configuration is intentionally absent until production details are provided.