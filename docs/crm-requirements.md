# CRM Requirements and Workflow Draft

**Product:** King of Cards - Cross-Selling CRM Beta  
**Status:** Phase 1 proposal; approval required before implementation  
**Data policy:** Synthetic customer and lead data only in development and test.

This draft separates repository-documented intent from proposals. The project README says the current milestone is infrastructure only. The planned module note records intended beta areas, not a detailed approved functional specification.

## 1. Confirmed and documented intent

Project constraints stated in the README:
- Build a development beta for the King of Cards Cross-Selling CRM.
- Use synthetic customer and lead data only.
- Current backend milestone provides infrastructure; business workflows are not implemented.
- Identity and CRM use separate PostgreSQL databases; frontend uses the API Gateway.

Modules stated in docs/planned-beta-modules.md:
- Shared customer master; deduplicate using mobile number and service.
- Lead intake with online ads and showroom walk-ins as source examples; leads are service-specific.
- Vendor assignment and vendor-scoped access.
- Lead stages and scheduled follow-ups.
- Referral attribution and coupon usage.
- Commission attribution and status.
- Role-based dashboards for CRM and vendor roles.

These are documented intentions. Detailed behavior, scope, and acceptance criteria remain unconfirmed.

## 2. Current implementation boundary

Backend code implements health endpoints, Eureka, Gateway routing, DEV datasource configuration, Flyway enablement, and JPA schema validation. There are no business entities, DTOs, repositories, services, business controllers, domain tables, login endpoint, user store, or role enforcement.

Identity has an open Spring Security filter chain. Its optional JWT configuration can validate a token when configured, but does not issue tokens or authenticate users. CRM and Gateway do not enforce authorization. No OpenAPI specification or approved CRM API contract exists.

## 3. Roles, permissions, and login decisions

The planned modules mention CRM and vendor roles, but define no role names or action matrix. Candidate roles below are examples only:

| Candidate role | Possible scope to confirm |
|---|---|
| CRM administrator | Manage users, roles, catalog, and all CRM records |
| CRM manager | Manage CRM-wide customers, leads, and assignments |
| CRM agent | Work records within an approved team scope |
| Vendor user | View/update records assigned to that vendor |
| Read-only viewer | View approved records or dashboards |

Confirm whether a vendor is an internal user, external organization, or both; who may reassign leads, view contact details, change stages, manage catalog values, and view commissions.

Confirm login identifier, provisioning, password policy, lockout/rate limits, JWT issuer and signing-key ownership, token lifetime/refresh/logout, required claims, and whether Gateway-only JWT validation is sufficient. Passwords must be one-way hashed, never stored as plaintext.

## 4. Proposed customer workflow

**Proposal only; approval required.**
1. An authorized user submits a customer profile.
2. Mobile is normalized using an approved country/format rule.
3. The service checks for a possible duplicate using the approved mobile/service rule.
4. Create a non-duplicate; return or queue a possible duplicate according to a chosen review rule.
5. Authorized users search, view, and update permitted fields.
6. If service relationships are approved, associate multiple services to a shared customer instead of duplicating the customer.

**Decisions:** profile fields; mobile normalization; uniqueness by mobile, mobile plus service, or another key; merge/manual review; service-link lifecycle; consent/privacy; deletion policy.

## 5. Proposed lead creation and assignment workflow

**Proposal only; approval required.**
1. An authorized user creates a lead with a source and service.
2. Link an existing customer or create one during intake according to an approved rule.
3. Set an initial stage only after the stage/default policy is decided.
4. A permitted user assigns the lead to a vendor user or organization.
5. Apply vendor-scoped access to reads and updates.
6. Handle reassignment and prior-assignment visibility according to an approved history rule.

**Decisions:** required fields; lead-before-customer; source vocabulary; stages/transitions; initial stage; duplicate handling; assignment cardinality/history; visibility of unassigned leads.

## 6. Proposed follow-up workflow

**Proposal only; approval required.**
1. An authorized user schedules a follow-up for a lead with a due time and owner.
2. Show it in approved owner/team queues.
3. The owner completes, reschedules, or cancels using approved statuses.
4. Add overdue reporting/reminders only after channels and escalation rules are selected.

**Decisions:** time zone; due-time precision; statuses; owner defaults; reminder channels; recurrence/escalation; reschedule audit; whether a follow-up can exist without a lead.

## 7. Cross-selling workflow

The product name and service-specific leads indicate a cross-selling context. No eligibility, recommendation, offer, or conversion rule is documented.

**Proposal requiring approval:** represent a potential cross-sell as an opportunity linked to a customer and target service, with an owner and lifecycle. Do not implement matching logic, eligibility checks, offer states, or automatic lead creation until rules are defined.

**Decisions:** recommendation source; manual vs rule-generated offers; service exclusions; consent; opportunity states/ownership; conversion to lead/deal; success measures.

## 8. Referrals, coupons, commissions

These are named as planned modules only. Decide whether they belong in beta and define:
- Referral identity, attribution window, duplicates/self-referrals, reversals.
- Coupon owner, eligibility, validity, redemption limits, stacking, refunds, attribution.
- Commission basis/rate, qualifying event, beneficiary, currency, adjustments, cancellation, approval, payout states.

Keep optional until approved; do not include in the first customer/lead slice.

## 9. Dashboards and reporting

Only role-based CRM and vendor dashboards are documented. Metrics, calculations, filters, freshness, exports, and access matrix are unspecified. Candidate metrics for discussion include leads by stage/source/service/owner, upcoming/overdue follow-ups, conversions, and commissions; these are not approved KPIs. Decide live vs cached data, date/time zone, and export needs.

## 10. Provisional database design

Logical proposal, not a migration specification. UUID identifiers are suggested for service-owned records and external identity references; confirm before adoption. Fields, types, retention, constraints, and visibility need review.

### Identity database candidates

| Table | Candidate fields and keys | Relationships/notes |
|---|---|---|
| identity_user | id UUID PK; login_name; password_hash; active; created_at; updated_at | Unique login waits for identifier and case-normalization decision |
| identity_role | role_code PK; display_name | Role values unapproved |
| identity_user_role | user_id FK; role_code FK; PK (user_id, role_code) | Many-to-many role assignments |

### CRM database candidates

| Table | Candidate fields and keys | Relationships/constraints |
|---|---|---|
| customer | id UUID PK; display_name; mobile_input; mobile_normalized; email; created_at; updated_at; version | No unique mobile until normalization/deduplication is approved |
| service_catalog | id UUID PK; code; name; description; active; created_at; updated_at | Unique code proposed; taxonomy unresolved |
| customer_service | customer_id FK; service_id FK; linked_at; PK (customer_id, service_id) | Customer-to-services association; lifecycle and dedup use need approval |
| lead_source | code PK; display_name; active | Online ads/showroom walk-ins are examples; confirm list/custom values |
| lead_stage | code PK; display_name; sort_order; active | Names, transitions, terminal behavior unresolved |
| lead | id UUID PK; customer_id FK; service_id FK; source_code FK; stage_code FK; created_by_subject_id; summary; created_at; updated_at; version | Identity subject is external; no cross-database FK |
| lead_assignment | id UUID PK; lead_id FK; vendor_subject_id; assigned_by_subject_id; assigned_at; ended_at; reason | External identity IDs; assignment cardinality unresolved |
| follow_up | id UUID PK; lead_id FK; owner_subject_id; due_at; status; note; created_by_subject_id; completed_at; created_at; updated_at | Status and owner defaults unresolved |

Optional after scope approval: cross_sell_opportunity; activity_event; referral; coupon_redemption; commission; deal/sale. Each depends on business rules not yet documented.

### Constraint and index proposals

- UUID primary keys; composite keys for identity_user_role and customer_service.
- Index lead by customer_id, service_id, stage_code, source_code, created_at.
- Index assignment by lead_id and vendor_subject_id; a one-active-assignment partial unique index only if approved.
- Index follow_up by owner_subject_id, status, due_at, and by lead_id.
- Index customer.mobile_normalized for lookup; uniqueness waits for dedup policy.
- Unique service_catalog.code and normalized identity login are proposals contingent on normalization/lifecycle rules.
- Add nullability, reference, and state constraints only after fields and rules are agreed.

## 11. Decisions before coding

1. First-beta scope vs deferred modules.
2. Roles, actions, data scopes, vendor identity model.
3. Login identifier, provisioning, JWT issuer/claims and validation boundary.
4. Customer fields, mobile normalization, deduplication, merge behavior.
5. Service catalog and whether products differ from services.
6. Lead fields and duplicate rules.
7. Source values, stages, transitions, initial stage.
8. Assignment cardinality and audit/reassignment rules.
9. Follow-up status, time zone, reminders, escalation.
10. Cross-sell opportunity and conversion definitions.
11. Scope/rules for deals, activity history, referrals, coupons, commissions.
12. Dashboard KPIs, filters, freshness, export.
13. PII consent, retention, deletion.
14. API pagination, sorting, filtering, errors, versioning.

## 12. Smallest first feature recommendation

After approving customer fields, mobile normalization/deduplication, access policy, and API conventions, implement a synthetic-data customer master slice: create, list/search, read detail, update. Keep leads, cross-selling, and optional modules out. This exercises a migration, entity/repository/service/controller layers, validation, authorization, and PostgreSQL-backed tests.
