# King of Cards — Cross-Selling CRM Beta

Backend foundation for a development beta of the cross-selling CRM. The first milestone contains service infrastructure only; customer, lead, referral, coupon, commission, and dashboard business workflows are planned but not implemented. Use synthetic data only.

## Requirements

- Java 21
- Maven 3.9.x
- PostgreSQL 15 or newer

## Backend services

| Service | Port | Purpose |
| --- | ---: | --- |
| Discovery Server | 8761 | Eureka registry and dashboard at `http://localhost:8761` |
| API Gateway | 8080 | Discovery-aware routes and React development CORS |
| Identity Service | 8081 | Security/JWT foundation and health endpoint |
| CRM Service | 8082 | JPA, Flyway, OpenFeign foundation and health endpoint |
| React Frontend | 5173 | Vite development server; developed independently in VS Code |

Gateway prefixes `/api/identity/**` and `/api/crm/**` are stripped and forwarded to the corresponding service. Each service can also be reached directly on its local port.

## Local backend setup

The local PostgreSQL DEV databases are `koc_identity_dev` and `koc_crm_dev`. Configure their local credentials as environment variables; details are in [the PostgreSQL DEV setup notes](infra/postgres/README.md). For Spring Tool Suite import, the active `dev` profile, environment variable names, startup order, and health URLs, see [the backend development guide](backend/README.md).

The Maven Wrapper is included. From `C:\crm\king-of-cards-crm-backend-worktree\backend`, run:

```powershell
.\mvnw.cmd clean verify
```

## Frontend (Visual Studio Code)

Open `C:\crm\king-of-cards-crm\frontend` as a separate VS Code folder. Follow [the frontend README](frontend/README.md) to install dependencies and start Vite. The frontend reads `VITE_API_BASE_URL` from `frontend/.env` and defaults to `http://localhost:8080`. Its Service status page calls the existing Identity and CRM health routes through the gateway.

## Spring Tool Suite (STS)

Import `C:\crm\king-of-cards-crm-backend-worktree\backend` using **File → Import → Maven → Existing Maven Projects**, select Java 21, and follow [the backend STS guide](backend/README.md) for DEV environment variables, profiles, and service launch order.

## Planned beta modules

See [the planned beta module notes](docs/planned-beta-modules.md). These modules are documentation only in this foundation milestone.
