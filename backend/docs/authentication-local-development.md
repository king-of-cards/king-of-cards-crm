# Local Authentication Setup

This guide configures development authentication only. It does not create or target production databases.

## Keys

Generate a local 3072-bit RSA keypair once by running:

    powershell -ExecutionPolicy Bypass -File .\tools\Generate-KocJwtDevKey.ps1

The script stores PKCS#8 private and public PEM files under %LOCALAPPDATA%\KingOfCardsCRM\keys, restricts the directory to the current Windows user, refuses to overwrite an existing keypair, and does not display key contents. Keep both files outside Git. Identity reads the private key; CRM obtains the public key from Identity's /auth/jwks endpoint.

## Identity and CRM environment

In each service's STS Run Configuration, set the dev Spring profile and configure:

- JWT_ISSUER=http://localhost:8081
- JWT_AUDIENCE=koc-crm-api
- JWT_PRIVATE_KEY_LOCATION=%LOCALAPPDATA%\KingOfCardsCRM\keys\jwt-private.pem (Identity only)
- JWT_JWKS_URI=http://localhost:8081/auth/jwks (CRM only)
- IDENTITY_ACCESS_TOKEN_TTL=15m
- IDENTITY_REFRESH_TOKEN_TTL=7d (development default; override as needed)
- IDENTITY_COOKIE_SECURE=false (local HTTP only)
- IDENTITY_COOKIE_SAME_SITE=Lax
- CORS_ALLOWED_ORIGIN=http://localhost:5173

Also set existing database variables: IDENTITY_DB_USERNAME and IDENTITY_DB_PASSWORD for Identity, and CRM_DB_USERNAME and CRM_DB_PASSWORD for CRM. Do not add passwords or private key contents to .launch files or this repository. Use the existing secure local launcher for database password prompts.

The Gateway allows only the configured exact CORS origin, with credentials enabled and explicit Authorization, Content-Type, and X-XSRF-TOKEN headers. Identity sets an HttpOnly KOC_REFRESH cookie scoped to /api/identity/auth; the readable XSRF-TOKEN cookie is paired with the X-XSRF-TOKEN request header and is validated against its hash stored for that refresh session.

The existing React client must send credentialed login/refresh/logout requests, read the XSRF-TOKEN cookie, and echo it in X-XSRF-TOKEN for refresh/logout. No frontend files were changed by this backend phase. The running microservices must be restarted in an approved maintenance window before they load this implementation; this task did not restart them.

## One-time first administrator

The helper starts a one-shot non-web Identity context against the Identity DEV database. Flyway applies V2 before the command provisions the account, then the process exits. Run:

    powershell -ExecutionPolicy Bypass -File .\tools\Bootstrap-KocAdmin.ps1

The helper prompts for the existing Identity DEV database password and the new administrator password without echoing them. It stores both only in the child process environment, never in a launch file or command-line argument, and clears the process values when the bootstrap run ends. The command refuses to bootstrap if any user already exists and exits after creating the first CRM_ADMIN. Do not run it against production without a separately reviewed provisioning procedure.

No default account or password is created.

## API calls

- POST http://localhost:8080/api/identity/auth/login accepts JSON with email and password; it returns a short-lived access token and sets refresh/CSRF cookies.
- GET http://localhost:8080/api/identity/auth/me requires an Authorization: Bearer header.
- POST http://localhost:8080/api/identity/auth/refresh requires the refresh cookie plus the CSRF cookie echoed in X-XSRF-TOKEN; it rotates the refresh token.
- POST http://localhost:8080/api/identity/auth/logout requires the same CSRF pair and revokes the session family.
- GET http://localhost:8080/api/identity/auth/jwks returns public signing keys only.

Access tokens already issued remain valid until their short expiry after logout; refresh sessions are revoked server-side. The configured 7-day refresh lifetime is a development default and can be overridden with IDENTITY_REFRESH_TOKEN_TTL.