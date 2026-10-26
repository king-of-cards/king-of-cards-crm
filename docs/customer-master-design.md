# Customer Master Design

**Worktree:** C:\crm\king-of-cards-crm-backend-worktree  
**Branch:** feature/koc-crm-backend  
**Status:** Provisional design; approval required before implementation  
**Data policy:** Synthetic customer and lead data only in development and test.

## 1. Existing documentation and CRM structure

Reviewed docs/planned-beta-modules.md, docs/crm-requirements.md, docs/api-contracts.md, and the CRM module/POM/configuration. Repository intent is a shared customer master deduplicated by mobile and service, with service-specific leads. The Phase 1 drafts recommend a shared customer plus service associations but explicitly leave field, uniqueness, and association rules unresolved. This design is a proposal, not a confirmed rule.

CRM currently has CrmServiceApplication, HealthController, and package-info placeholders for entity, dto, repository, service, and exception. There are no business entities, endpoints, validation, exception handling, or CRM security. DEV uses its own PostgreSQL database; Flyway is enabled and JPA uses ddl-auto: validate. The V1 migration executes SELECT 1 only. Do not edit V1; after approval, add a new versioned migration.

## 2. Proposed profile fields

Keep the first profile small. Candidate fields:

| Field | Proposed meaning | Status |
|---|---|---|
| id | CRM-owned UUID | Proposed identifier convention |
| displayName | Name shown to CRM users | Candidate required; limits TBD |
| mobileInput | Original phone input | Retain only if audit/display needs it |
| mobileNormalized | Canonical phone value for exact lookup | Candidate key; format/country rule TBD |
| email | Optional contact email | Include only if needed |
| createdAt, updatedAt | UTC timestamps | Recommended |
| version | Optimistic-lock counter | Recommended to prevent lost updates |

Do not add address, date of birth, gender, notes, consent fields, or other PII without a stated requirement. Decide whether audit history is needed before retaining free text.

## 3. Mobile normalization and duplicate detection

### Normalization proposal

If approved: trim whitespace; parse using an agreed phone library and country/default-region policy; store a canonical international representation in mobileNormalized; reject values that cannot be parsed. Do not guess country from digit count. Keep the original value only if needed. Use the same normalizer for create, update, and exact search.

A default country/region is not documented. E.164-style storage is a proposal. Approve accepted formats, region selection, extension handling, and whether mobile may be absent or shared.

### Duplicate proposal

There is a conflict to resolve between a shared customer and “deduplicate by mobile number and service.”

**Recommended interpretation:** one customer per normalized mobile globally, with service associations stored separately. Add UNIQUE(customer.mobile_normalized) only if approved. This supports one profile and multiple service-specific leads.

**Alternative:** permit separate customer rows for one mobile when service differs. That is not the same as a shared profile and would change the model.

On create/update, normalize, search for a duplicate, and return a conflict rather than silently merging or creating another profile. Expose a candidate ID only if caller permissions allow it; otherwise return a generic conflict. Define merge/manual review separately. A database uniqueness constraint is needed for concurrent requests, but its key waits for approval.

## 4. Customer-to-service relationship

Proposed many-to-many model:

- One customer may relate to many service_catalog rows.
- One service may relate to many customers.
- Each future lead references one shared customer and one specific service. A customer interested in two services can therefore have two leads and one profile.

Candidate link table: customer_service(customer_id, service_id, linked_at, linked_by_subject_id). Do not silently replace links during a profile PATCH. Decide whether links are created at customer intake or automatically when a service-specific lead is created; decide whether removal is allowed or links are historical. No service catalog values are documented, so do not invent seed rows.

## 5. Validation, search, lifecycle, and access

### Create/update validation

Candidate rules: displayName required and bounded; mobile required only if selected as identity key and must parse under approved region rules; email format only if retained; every service ID must exist and be eligible; reject repeated service IDs. PATCH rejects an empty body, changes only supplied fields, and re-checks duplicates for mobile changes. Define whether null clears optional values and how optimistic concurrency is represented.

### Search and pagination

Proposed GET filters: page (zero-based), size, allowlisted sort, q, serviceId. q may search name and exact normalized mobile; partial mobile search needs approval because it exposes PII. Define defaults/max size, stable tie-break ordering, response masking, and vendor scoping. Apply row-level visibility to results, counts, and duplicate checks.

### Status and lifecycle

No customer status or deletion rule is documented. Recommendation for the smallest slice: omit status and hard delete. If needed, approve active/archived/merged meanings first. Merge requires a survivor/redirect and audit policy.

### Access control

Current Identity permits all requests; CRM has no security setup. Proposed permissions are CUSTOMER_READ, CUSTOMER_CREATE, CUSTOMER_UPDATE, not implemented. Before exposing customer PII, approve JWT issuance/validation, role actions, vendor/customer scope, and whether mobile/email are masked. Enforce access in the API, not only in the frontend or Gateway.

## 6. Provisional PostgreSQL design

Logical proposal only; not executable SQL. UUID strategy, types, nullability, sizes, timestamps, and constraints require approval/review.

### customer

| Column | Proposed type | Key/rule |
|---|---|---|
| id | uuid | PK, not null |
| display_name | varchar(200) | Required candidate |
| mobile_input | varchar(64) | Nullable candidate |
| mobile_normalized | varchar(16) | Required candidate if mobile is identity key |
| email | varchar(254) | Nullable, only if approved |
| created_at, updated_at | timestamptz | Not null, UTC |
| version | bigint | Not null, optimistic-lock proposal |

Candidate constraints: PK(id); format check only after format is selected; UNIQUE(mobile_normalized) only if global uniqueness is approved. Do not make display_name unique. Selected phone format may require another type/length.

### service_catalog

| Column | Proposed type | Key/rule |
|---|---|---|
| id | uuid | PK |
| code | varchar(64) | Required candidate |
| name | varchar(160) | Required candidate |
| description | text | Nullable |
| active | boolean | Candidate if catalog lifecycle is approved |
| created_at, updated_at | timestamptz | UTC |

Candidate constraint: UNIQUE(code) after code case/normalization is decided. No seed data until supplied. Decide rename, deactivate, and delete behavior for referenced services.

### customer_service

| Column | Proposed type | Key/rule |
|---|---|---|
| customer_id | uuid | PK part, FK customer(id), not null |
| service_id | uuid | PK part, FK service_catalog(id), not null |
| linked_at | timestamptz | Not null |
| linked_by_subject_id | uuid | Nullable external Identity subject; no cross-database FK |

Composite PK(customer_id, service_id) prevents duplicate links. ON DELETE RESTRICT is a proposal. If links need history, use association ID and ended_at rather than deleting.

Future lead integrity: lead(customer_id, service_id) may reference the composite customer_service key if every lead requires a linked service; alternatively enforce in the application transaction. Decide whether creating a lead itself creates the association. Identity user IDs are external values, not PostgreSQL cross-database FKs.

### Index proposals

- Unique B-tree on customer.mobile_normalized only if global uniqueness is approved; otherwise a non-unique lookup index.
- Index lower(display_name) only if case-insensitive name search is approved; consider pg_trgm only after workload evidence and extension approval.
- Unique service_catalog.code per chosen normalization; index active only if filtering needs it.
- customer_service PK supports lookup by customer; add (service_id, customer_id) for reverse lookup.
- Add created_at indexes only if query patterns justify them.

## 7. Proposed API contracts

All customer endpoints are proposals, not implemented. Gateway /api/crm/** strips /api/crm, so CRM receives /customers. Use synthetic examples. Candidate response fields and masking require approval.

### POST /api/crm/customers

Purpose: create one profile and optionally attach initial services.

Request example:
{
  "displayName": "Synthetic Customer A",
  "mobile": "+14155550123",
  "email": "customer-a@example.test",
  "serviceIds": ["a4c6e152-8bce-45af-8628-15d9fc2dfaa1"]
}

Candidate fields: displayName required; mobile required if chosen as key; email optional; serviceIds optional. Do not accept ID, normalized mobile, timestamps, or version from clients.

Success: 201 Created; Location: /api/crm/customers/{id}. Response:
{
  "id": "4f159746-7899-4f45-b0d0-31af6e54d011",
  "displayName": "Synthetic Customer A",
  "mobile": "+14155550123",
  "email": "customer-a@example.test",
  "services": [{"id":"a4c6e152-8bce-45af-8628-15d9fc2dfaa1","code":"APPROVED_CODE","name":"Approved service"}],
  "createdAt": "2026-01-02T03:04:05Z",
  "updatedAt": "2026-01-02T03:04:05Z"
}

Validate body, approved required fields/limits, phone, email if retained, service existence, and repeated service IDs. Duplicate proposal: 409 with CUSTOMER_DUPLICATE or CUSTOMER_POSSIBLE_DUPLICATE; no silent merge. Include existing ID only if caller may read it. Errors: 400 invalid body/fields; 401 unauthenticated; 403 unauthorized; 404 unknown/ineligible service; 409 duplicate/concurrent uniqueness race; 500 generic error without stack trace. Permission: CUSTOMER_CREATE plus any approved association permission.

### GET /api/crm/customers

Purpose: search/page visible customer summaries.

Request example: /api/crm/customers?page=0&size=25&sort=displayName,asc&q=Synthetic&serviceId={uuid}

Response example:
{
  "items": [{
    "id": "4f159746-7899-4f45-b0d0-31af6e54d011",
    "displayName": "Synthetic Customer A",
    "mobile": "+1415******23",
    "serviceIds": ["a4c6e152-8bce-45af-8628-15d9fc2dfaa1"],
    "createdAt": "2026-01-02T03:04:05Z"
  }],
  "page": 0, "size": 25, "totalElements": 1, "totalPages": 1
}

Masking is illustrative only. Validate page >= 0, bounded positive size, allowlisted sort, bounded q, and UUID serviceId. Define defaults, maximum, stable ID tie-break, exact/partial search. Errors: 400 invalid query; 401; 403; 500. Permission: CUSTOMER_READ and server-side row scope, including counts.

### GET /api/crm/customers/{id}

Purpose: read a visible profile and approved service links. Request: UUID path value. Response: same profile fields as POST. Validate UUID. Errors: 400 invalid ID; 401; 403; 404 missing or concealed out-of-scope; 500. Permission: CUSTOMER_READ plus row scope.

### PATCH /api/crm/customers/{id}

Purpose: update profile fields without implicitly replacing service links.

Request example:
{
  "displayName": "Synthetic Customer A Updated",
  "email": "updated@example.test",
  "version": 0
}

Candidate mutable fields: displayName, mobile, email. Omitted means unchanged; decide whether null clears optional values. Do not accept id, timestamps, or normalized value. Exclude serviceIds until add/remove semantics are explicit.

Success: 200 with updated profile and updatedAt; version if optimistic locking is selected. Reject empty patch; validate supplied fields; normalize/check duplicate on mobile change. Errors: 400 invalid/empty; 401; 403; 404; 409 duplicate/stale version; 500. Never include another customer's PII in conflict details. Permission: CUSTOMER_UPDATE and row scope.

### Validation error example

{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "code": "VALIDATION_FAILED",
  "fieldErrors": [{"field":"mobile","code":"INVALID_PHONE","message":"Enter a phone number in an approved format."}],
  "traceId": "synthetic-trace-id"
}

ProblemDetail is proposed. Do not log raw submitted PII.

## 8. Java implementation file plan

The first slice needs these CRM files, subject to approval:

Domain/persistence:
- backend/crm-service/src/main/java/com/kingofcards/crm/entity/Customer.java
- backend/crm-service/src/main/java/com/kingofcards/crm/entity/ServiceCatalogEntry.java
- backend/crm-service/src/main/java/com/kingofcards/crm/entity/CustomerServiceAssociation.java
- backend/crm-service/src/main/java/com/kingofcards/crm/repository/CustomerRepository.java
- backend/crm-service/src/main/java/com/kingofcards/crm/repository/ServiceCatalogRepository.java
- backend/crm-service/src/main/java/com/kingofcards/crm/repository/CustomerServiceRepository.java
- backend/crm-service/src/main/resources/db/migration/V2__customer_master.sql

API/logic:
- backend/crm-service/src/main/java/com/kingofcards/crm/dto/CreateCustomerRequest.java
- backend/crm-service/src/main/java/com/kingofcards/crm/dto/UpdateCustomerRequest.java
- backend/crm-service/src/main/java/com/kingofcards/crm/dto/CustomerResponse.java
- backend/crm-service/src/main/java/com/kingofcards/crm/dto/CustomerSummaryResponse.java
- backend/crm-service/src/main/java/com/kingofcards/crm/dto/CustomerSearchCriteria.java
- backend/crm-service/src/main/java/com/kingofcards/crm/service/CustomerService.java
- backend/crm-service/src/main/java/com/kingofcards/crm/service/CustomerServiceImpl.java
- backend/crm-service/src/main/java/com/kingofcards/crm/service/MobileNumberNormalizer.java
- backend/crm-service/src/main/java/com/kingofcards/crm/controller/CustomerController.java
- backend/crm-service/src/main/java/com/kingofcards/crm/exception/CustomerNotFoundException.java
- backend/crm-service/src/main/java/com/kingofcards/crm/exception/DuplicateCustomerException.java
- backend/crm-service/src/main/java/com/kingofcards/crm/exception/ApiExceptionHandler.java

A phone library and validation dependency may require crm-service/pom.xml changes after normalization/validation is approved. If catalog CRUD is deferred, only approve/seed supplied service values through an approved migration. Do not invent services.

Access prerequisite: choose JWT validation ownership, then add CRM security support/configuration and caller authorization before exposing PII. Exact files depend on whether each service validates JWT or trusts an authenticated Gateway identity. Current permit-all setup is not adequate for customer data.

## 9. Smallest safe sequence and tests

1. Approve profile fields, mobile/country policy, duplicate rule, service-link ownership, lifecycle, PII/access policy, and API conventions.
2. Implement a normalizer and unit tests for valid/invalid values under the selected region rules.
3. Add V2 and test it against an isolated PostgreSQL test database; exercise constraints/indexes, Flyway, and Hibernate validate.
4. Implement entities/repositories; test create/read/update, service links, uniqueness, and concurrent duplicate race.
5. Implement DTOs/mapper/service; test duplicate conflicts, PATCH null/omission semantics, and approved association behavior.
6. Implement controller/search; test 201/200/400/401/403/404/409, page ordering, masks, row scope, and no PII leakage.
7. Run full Maven verify. Do not bypass Flyway or Hibernate validation and do not run destructive tests on the DEV database.

Proposed tests: MobileNumberNormalizerTest, CustomerServiceTest, CustomerRepositoryIntegrationTest, CustomerControllerTest, CustomerSchemaMigrationTest. Use synthetic data only.

## 10. Business decisions requiring approval

1. Does normalized mobile identify one customer globally, or can the same mobile have separate profiles per service?
2. Accepted country/phone formats; required/optional mobile; shared family numbers.
3. Initial profile fields; whether original mobile is retained; email inclusion.
4. List response masking and exact/partial mobile search policy.
5. How customer-service links are created, removed, and retained; should lead creation create a link?
6. Real service catalog values/codes; whether products differ; who administers the catalog.
7. Is customer status needed? Define archive, merge, duplicate review, and deletion.
8. Roles that may create/read/update/search and vendor scope across a customer’s service leads.
9. Mutable fields, PATCH null semantics, optimistic concurrency.
10. Contact-data consent, retention, audit, and privacy requirements.
11. UUID choice, pagination defaults/maximum, error format, and duplicate-conflict disclosure.

No schema, migration, Java, database, service, or frontend was changed by this design.
