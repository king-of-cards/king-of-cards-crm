# King of Cards Cross-Selling CRM

This repository contains the existing Spring Boot microservices and React/Vite
frontend for the King of Cards CRM. Continue development in this project; the
backend remains a Maven multi-module project for Spring Tool Suite (STS), and
the frontend remains an independent React application for VS Code.

## Technology and data

- Java 21, Spring Boot, Spring Cloud Gateway, Netflix Eureka, Spring Security
  and JWT
- React and Vite
- **Google BigQuery is the only application database.** Identity and CRM use
  the existing BigQuery project, dataset and tables. Do not add PostgreSQL,
  MySQL or another runtime database, and do not replace or recreate existing
  BigQuery resources.
- BigQuery writes and schema changes are separately gated. Keep the committed
  defaults disabled; use the current local startup guide for approved read-only
  checks and test modes.

Legacy SQL/JPA/Flyway files and PostgreSQL setup notes are retained historical
artifacts. They do not define the current application storage architecture and
must not be used to start or migrate the application.

## Existing services

| Service | Port | Purpose |
| --- | ---: | --- |
| Discovery Server | 8761 | Eureka registry |
| API Gateway | 8080 | Routes frontend API requests to services |
| Identity Service | 8081 | Authentication, JWT and account APIs |
| CRM Service | 8082 | CRM and lead APIs; BigQuery-backed storage |
| React Frontend | 5173 | Existing Vite application |

Gateway paths `/api/identity/**` and `/api/crm/**` route to their existing
services. The frontend uses the Gateway at `http://localhost:8080`.

## Development

Keep the backend under `backend/` and import its existing Maven modules into
STS. Use JDK 21 and the saved launch configurations. Startup order, environment
settings, occupied-port checks and safe verification steps are documented in
[the Windows/STS startup guide](backend/docs/windows-local-startup.md).

Open `frontend/` separately in VS Code. Its local testing instructions are in
[the frontend guide](frontend/LOCAL-FRONTEND-TESTING.md). If Vite is already
serving port 5173, reuse that instance rather than starting another.

The current frontend/backend contracts and feature boundaries are documented in
[the directional lead dashboard guide](backend/docs/directional-leads-dashboard.md),
[the CRM lead API guide](backend/docs/crm-lead-api.md), and
[the frontend integration guide](frontend/LATEST-INTEGRATION.md).

Do not start account bootstrap, enable authentication mutations, clean existing
Identity records, run DDL/DML, or provision real accounts without the separate
approval and safeguards described in the production authentication and
BigQuery cutover documents.
