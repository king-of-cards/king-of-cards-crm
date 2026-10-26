# Proposed API Contracts

**Status:** Draft for review; no business endpoint below is implemented or approved.  
**Gateway:** http://localhost:8080  
**Identity prefix:** /api/identity; Gateway strips the prefix before forwarding.  
**CRM prefix:** /api/crm; Gateway strips the prefix before forwarding.  
**Data:** Synthetic records only in development/test.

## 1. Conventions (proposed)

- JSON uses camelCase. Service identifiers are proposed UUID strings; confirm before implementation.
- Timestamps use ISO-8601 with offset and are normalized/stored in UTC.
- Lists use page (zero-based), size, optional sort; defaults/maximum size require approval.
- Proposed errors use application/problem+json and Spring ProblemDetail, with optional code, fieldErrors, traceId. Existing health responses are simpler.
- Never expose password hashes, signing keys, database credentials, or stack traces.
- Permissions and fields below are candidates, not approved policy.

## 2. Authentication

**Implemented backend contract.** Identity owns accounts and signs RS256 access tokens. The API is exposed through the Gateway under /api/identity; Gateway strips that prefix before forwarding. Email is trimmed and normalized case-insensitively. Roles are CRM_ADMIN, CRM_MANAGER, CRM_AGENT, and VENDOR; role-to-business-permission mapping remains unapproved.

### POST /api/identity/auth/login

Request: JSON object with email (valid email, maximum 254 characters) and password (non-empty, maximum BCrypt input length).  
Response: accessToken; tokenType (Bearer); expiresIn (seconds); user containing id, email, and roles. The refresh token is set only as an HttpOnly cookie; the CSRF token is set as a non-HttpOnly cookie. Neither token is returned in the response body except the access token.  
Permission: public; exact-origin CORS and request Origin validation apply.  
Errors: 400 invalid request; 401 generic invalid-credentials response for unknown, disabled, or incorrect-password accounts; 403 disallowed origin.

### GET /api/identity/auth/me

Requires a valid bearer access token. Returns id, email, and roles.  
Errors: 401 missing, invalid, expired, wrong-issuer, or wrong-audience token; 403 when access is denied.

### POST /api/identity/auth/refresh

Requires the HttpOnly KOC_REFRESH cookie, XSRF-TOKEN cookie, and matching X-XSRF-TOKEN header. Rotates the refresh token and its associated CSRF token, returning a new access token. Refresh token values are stored only as hashes. Reuse of a rotated token revokes the token family.  
Errors: 401 invalid, expired, revoked, disabled-user, or reused refresh session; 403 invalid CSRF pair or origin.

### POST /api/identity/auth/logout

Requires the refresh cookie and matching CSRF cookie/header pair when a session exists. Revokes the session family and clears both cookies. Existing access tokens remain valid only until their short expiry.  
Errors: 403 invalid CSRF pair or origin.

### GET /api/identity/auth/jwks

Public endpoint returning public signing keys only. CRM uses the JWKS URI to validate signatures and also validates issuer, audience, and time claims.

Login does not create users. A one-time operator bootstrap creates the first CRM_ADMIN; no default account is present. Refresh-token lifetime defaults to seven days in the dev profile and is configurable.
## 3. Customer management

Profile fields/dedup constraints are unspecified; fields are candidates. Apply approved visibility rules to all reads.

### GET /api/crm/customers

**Purpose:** Search/page visible customers.  
**Request query:** page; size; sort; q (approved name/mobile search); serviceId.  
**Response:** items of id, displayName, mobile (masking policy TBD), serviceIds, createdAt, updatedAt; page, size, totalElements, totalPages.  
**Validation:** Page non-negative, size bounded/positive, sort supported; query normalization/length TBD.  
**Permission:** CUSTOMER_READ with server-side row scope.  
**Errors:** 400 invalid query; 401; 403; 500.

### POST /api/crm/customers

**Purpose:** Create customer and optionally associate services.  
**Request:** displayName (required candidate); mobile (required candidate); email (optional); serviceIds (optional array); notes (optional, subject to PII policy).  
**Response:** id; displayName; mobile; email; serviceIds; createdAt; updatedAt.  
**Validation:** Required fields/lengths TBD; mobile format requires country/format decision; email format if retained; service IDs must exist/active if active state is adopted.  
**Permission:** CUSTOMER_CREATE.  
**Errors:** 400 invalid fields; 401; 403; 404 unknown service; 409 duplicate under approved rule; 500.

### GET /api/crm/customers/{customerId}

**Purpose:** Read one visible profile.  
**Request:** customerId UUID path parameter.  
**Response:** id; displayName; mobile; email; serviceIds; createdAt; updatedAt.  
**Validation:** UUID syntax.  
**Permission:** CUSTOMER_READ.  
**Errors:** 400 invalid ID; 401; 403; 404 missing or concealed out-of-scope; 500.

### PATCH /api/crm/customers/{customerId}

**Purpose:** Update approved mutable fields.  
**Request:** optional displayName; mobile; email; serviceIds; notes; version for optimistic concurrency if selected.  
**Response:** updated customer representation; updatedAt; version if used.  
**Validation:** Reject empty patch; validate changed fields; re-check duplicate after mobile/service changes; decide whether serviceIds replaces or incrementally updates relationships.  
**Permission:** CUSTOMER_UPDATE.  
**Errors:** 400 invalid/empty patch; 401; 403; 404; 409 duplicate/concurrent update; 500.

## 4. Service catalog

Service-specific leads are documented, but catalog contents, ownership, and lifecycle are not.

### GET /api/crm/services

**Purpose:** List active services.  
**Request query:** page; size; active; paging may be omitted if the catalog is small.  
**Response:** items with id; code; name; description; active; optional page metadata.  
**Validation:** Supported filters and paging bounds.  
**Permission:** SERVICE_READ; public-read policy TBD.  
**Errors:** 400 invalid query; 401; 403; 500.

### GET /api/crm/services/{serviceId}

**Purpose:** Read one catalog entry.  
**Request:** serviceId UUID.  
**Response:** id; code; name; description; active.  
**Validation:** UUID syntax.  
**Permission:** SERVICE_READ.  
**Errors:** 400; 401; 403; 404; 500.

### POST /api/crm/services

**Purpose:** Add approved catalog entry.  
**Request:** code; name; description; active (default TBD).  
**Response:** id; code; name; description; active; createdAt.  
**Validation:** code/name required candidates; length, characters, normalization, mutability TBD.  
**Permission:** SERVICE_ADMIN.  
**Errors:** 400; 401; 403; 409 duplicate code if uniqueness approved; 500.

### PATCH /api/crm/services/{serviceId}

**Purpose:** Update allowed catalog fields or deactivate.  
**Request:** name; description; active.  
**Response:** id; code; name; description; active; updatedAt.  
**Validation:** Reject empty patch; referenced-item rename/deactivation policy TBD.  
**Permission:** SERVICE_ADMIN.  
**Errors:** 400; 401; 403; 404; 409 in-use conflict if policy requires; 500.

## 5. Leads

Documented intent confirms service-specific leads and gives online ads and showroom walk-ins as source examples. Fields, lifecycle, and duplicate rules are unspecified.

### GET /api/crm/leads

**Purpose:** Search/page leads visible to caller.  
**Request query:** page; size; sort; customerId; serviceId; sourceCode; stageCode; assignedVendorId; createdFrom; createdTo.  
**Response:** items with id; customerId; serviceId; sourceCode; stageCode; currentVendorId; createdAt; updatedAt; page metadata.  
**Validation:** UUID/date syntax; createdFrom <= createdTo; allowed filter/sort vocabulary TBD.  
**Permission:** LEAD_READ with vendor/team row scope.  
**Errors:** 400; 401; 403; 500.

### POST /api/crm/leads

**Purpose:** Create lead for approved customer/service/source.  
**Request:** customerId; serviceId; sourceCode; summary; initialStageCode only if client-selected initial stage is approved.  
**Response:** id; customerId; serviceId; sourceCode; stageCode; createdBySubjectId; createdAt; updatedAt.  
**Validation:** Required relationships and initial-stage policy TBD; references must exist; active service/source and duplicate policies need approval.  
**Permission:** LEAD_CREATE.  
**Errors:** 400; 401; 403; 404 unknown customer/service/source; 409 duplicate/disallowed stage; 500.

### GET /api/crm/leads/{leadId}

**Purpose:** Read lead and approved summary details.  
**Request:** leadId UUID.  
**Response:** lead summary plus approved assignment/follow-up summaries; no customer PII outside caller scope.  
**Validation:** UUID syntax.  
**Permission:** LEAD_READ with row scope.  
**Errors:** 400; 401; 403; 404; 500.

### PATCH /api/crm/leads/{leadId}

**Purpose:** Update allowed details without bypassing lifecycle rules.  
**Request:** summary; customerId; serviceId; sourceCode (all proposed; relationship mutability TBD).  
**Response:** updated lead representation; updatedAt.  
**Validation:** Reject empty patch; validate references and state-dependent mutability after approval.  
**Permission:** LEAD_UPDATE.  
**Errors:** 400; 401; 403; 404; 409 state/concurrency conflict; 500.

### POST /api/crm/leads/{leadId}/stage-transitions

**Purpose:** Request a stage change through an explicit operation for policy enforcement/audit.  
**Request:** targetStageCode; reason (optional candidate).  
**Response:** leadId; previousStageCode; currentStageCode; changedBySubjectId; changedAt.  
**Validation:** Stage exists; transition matrix/reason rules need approval.  
**Permission:** LEAD_STAGE_CHANGE.  
**Errors:** 400; 401; 403; 404 lead/stage; 409 disallowed transition/concurrent update; 500.

## 6. Vendor assignment

Assignment and vendor-scoped access are documented; vendor identity/cardinality are not.

### POST /api/crm/leads/{leadId}/assignments

**Purpose:** Assign/reassign to vendor user or organization according to approved model.  
**Request:** vendorSubjectId; reason (optional candidate).  
**Response:** assignmentId; leadId; vendorSubjectId; assignedBySubjectId; assignedAt; endedAt if history retained.  
**Validation:** Valid IDs; target eligibility; reassignment behavior and one-active-assignment policy TBD.  
**Permission:** LEAD_ASSIGN.  
**Errors:** 400; 401; 403; 404 lead/vendor; 409 already assigned/disallowed reassignment; 500.

### GET /api/crm/leads/{leadId}/assignments

**Purpose:** Read current and/or historical assignments.  
**Request:** leadId UUID; optional page/size.  
**Response:** items with assignmentId; vendorSubjectId; assignedBySubjectId; assignedAt; endedAt; reason; current indicator only if defined.  
**Validation:** UUID and pagination.  
**Permission:** LEAD_ASSIGNMENT_READ; vendor visibility TBD.  
**Errors:** 400; 401; 403; 404; 500.

## 7. Follow-ups

Scheduling is documented; status, reminders, time zone, and ownership defaults are not.

### GET /api/crm/follow-ups

**Purpose:** List visible follow-ups, optionally filtered by lead/owner/status/time range.  
**Request query:** page; size; leadId; ownerSubjectId; status; dueFrom; dueTo.  
**Response:** items with id; leadId; ownerSubjectId; dueAt; status; note; completedAt; page metadata.  
**Validation:** Valid IDs/status/date range and supported paging/sort.  
**Permission:** FOLLOW_UP_READ with vendor/team scope.  
**Errors:** 400; 401; 403; 500.

### POST /api/crm/leads/{leadId}/follow-ups

**Purpose:** Schedule follow-up for lead.  
**Request:** dueAt (required candidate); ownerSubjectId (optional candidate); note (optional candidate).  
**Response:** id; leadId; ownerSubjectId; dueAt; status; note; createdBySubjectId; createdAt.  
**Validation:** Valid lead; dueAt includes offset; past-time allowance, owner default, note constraints TBD.  
**Permission:** FOLLOW_UP_CREATE.  
**Errors:** 400; 401; 403; 404 lead/owner; 409 state conflict; 500.

### PATCH /api/crm/follow-ups/{followUpId}

**Purpose:** Reschedule/update; complete/cancel only if approved.  
**Request:** dueAt; ownerSubjectId; note; status.  
**Response:** id; leadId; ownerSubjectId; dueAt; status; completedAt; updatedAt.  
**Validation:** Reject empty patch; status transitions/completion metadata TBD; validate owner scope and dueAt.  
**Permission:** FOLLOW_UP_UPDATE.  
**Errors:** 400; 401; 403; 404; 409 disallowed transition/concurrent update; 500.

## 8. Authorization and error conventions

Candidate permissions, not final: CUSTOMER_READ/CREATE/UPDATE, SERVICE_READ/ADMIN, LEAD_READ/CREATE/UPDATE/STAGE_CHANGE/ASSIGN/ASSIGNMENT_READ, FOLLOW_UP_READ/CREATE/UPDATE. Role mapping and vendor row scope require approval.

Suggested status mapping: 400 malformed/invalid; 401 unauthenticated; 403 unauthorized; 404 missing or intentionally concealed out-of-scope resource; 409 duplicate/transition/optimistic-lock conflict; 500 unexpected error. Decide whether validation uses 400 or 422 and which conflict details can be disclosed.

## 9. Existing health contracts

Only documented current service contracts:
- GET /api/identity/health -> Identity GET /health
- GET /api/crm/health -> CRM GET /health

Both return service and status. Actuator health is directly exposed by each service. All business contracts above are proposals pending approval.
