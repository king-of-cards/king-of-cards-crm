-- Production real-account transaction protocol. Runtime activation is separately gated.
-- Identifiers come only from the approved production Auth target allowlist.
-- No permanent DDL, no row deletion, no exception handler that could swallow a rollback.
DECLARE v_now TIMESTAMP;
DECLARE v_expected INT64 DEFAULT 0;
DECLARE v_status STRING DEFAULT 'INVALID';
DECLARE v_owner STRING;
DECLARE v_family STRING;
DECLARE v_user STRUCT<id STRING, name STRING, email STRING, password_hash STRING,
  role STRING, enabled BOOL, created_at TIMESTAMP, updated_at TIMESTAMP>;

ASSERT CURRENT_TIMESTAMP() < @request_deadline AS 'IDENTITY_REQUEST_EXPIRED';
ASSERT @operation IN ('BOOTSTRAP', 'CREATE_USER', 'UPDATE_ACCOUNT', 'CREATE_SESSION',
  'ROTATE', 'REVOKE_TOKEN', 'REVOKE_FAMILY') AS 'IDENTITY_OPERATION_INVALID';

BEGIN TRANSACTION;
-- BigQuery documents DELETE WHERE FALSE as table-lock acquisition in transactions:
-- https://docs.cloud.google.com/bigquery/docs/transactions#transaction_concurrency
-- This application still requires isolated real-engine verification before production use.
-- EVERY writer must participate. A plain INSERT from outside this protocol bypasses it.
DELETE FROM {{USERS}} WHERE FALSE;
ASSERT @@row_count = 0 AS 'IDENTITY_LOCK_FAILED';
DELETE FROM {{SESSIONS}} WHERE FALSE;
ASSERT @@row_count = 0 AS 'IDENTITY_LOCK_FAILED';
SET v_now = CURRENT_TIMESTAMP();

-- Fail closed on pre-existing corruption rather than select an arbitrary identity.
-- Global checks are deliberately conservative; cost/contention require engine/load tests.
ASSERT NOT EXISTS (SELECT id FROM {{USERS}} GROUP BY id HAVING COUNT(*) > 1)
  AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT email FROM {{USERS}} GROUP BY email HAVING COUNT(*) > 1)
  AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT id FROM {{SESSIONS}} GROUP BY id HAVING COUNT(*) > 1)
  AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT token_hash FROM {{SESSIONS}} GROUP BY token_hash HAVING COUNT(*) > 1)
  AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT token_family_id FROM {{SESSIONS}}
  GROUP BY token_family_id HAVING COUNT(DISTINCT user_id) > 1) AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT 1 FROM {{SESSIONS}} s WHERE NOT EXISTS
  (SELECT 1 FROM {{USERS}} u WHERE u.id = s.user_id)) AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT 1 FROM {{USERS}} WHERE
  NOT REGEXP_CONTAINS(email, r'^[^\s@]+@[^\s@]+\.[^\s@]+$') OR email != LOWER(TRIM(email)) OR LENGTH(email) > 254
  OR role NOT IN ('CRM_ADMIN', 'CRM_VENDOR')
  OR NOT REGEXP_CONTAINS(id, r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
  OR NOT REGEXP_CONTAINS(password_hash, r'^\$2[aby]\$(0[4-9]|[12][0-9]|3[01])\$[./A-Za-z0-9]{53}$')
  OR (name IS NOT NULL AND (LENGTH(TRIM(name)) = 0 OR LENGTH(name) > 120 OR name != TRIM(name)))
  OR updated_at < created_at) AS 'IDENTITY_INTEGRITY';
ASSERT NOT EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE
  NOT REGEXP_CONTAINS(id, r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
  OR NOT REGEXP_CONTAINS(token_family_id, r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
  OR NOT REGEXP_CONTAINS(token_hash, r'^[a-f0-9]{64}$')
  OR NOT REGEXP_CONTAINS(csrf_token_hash, r'^[a-f0-9]{64}$')
  OR expires_at <= created_at OR (used_at IS NOT NULL AND revoked_at IS NULL)
  OR used_at < created_at OR revoked_at < created_at) AS 'IDENTITY_INTEGRITY';

IF @operation IN ('BOOTSTRAP', 'CREATE_USER') THEN
  IF @operation = 'BOOTSTRAP' AND EXISTS (SELECT 1 FROM {{USERS}}) THEN
    SET v_status = 'BOOTSTRAP_NOT_EMPTY';
  ELSEIF EXISTS (SELECT 1 FROM {{USERS}} WHERE email = @email) THEN
    SET v_status = 'DUPLICATE_EMAIL';
  ELSEIF EXISTS (SELECT 1 FROM {{USERS}} WHERE id = @id) THEN
    SET v_status = 'ID_COLLISION';
  ELSE
    ASSERT @operation != 'BOOTSTRAP' OR @role = 'CRM_ADMIN' AS 'IDENTITY_BOOTSTRAP_ROLE';
    INSERT INTO {{USERS}} (id, name, email, password_hash, role, enabled, created_at, updated_at)
      VALUES (@id, IF(@name_present, @name, NULL), @email, @password_hash, @role, @enabled, v_now, v_now);
    ASSERT @@row_count = 1 AS 'IDENTITY_AFFECTED_ROWS';

    SET v_user = (SELECT AS STRUCT id, name, email, password_hash, role, enabled, created_at, updated_at
      FROM {{USERS}} WHERE id = @id);
    SET v_status = 'CREATED';
  END IF;

ELSEIF @operation = 'UPDATE_ACCOUNT' THEN
  IF NOT EXISTS (SELECT 1 FROM {{USERS}} WHERE id = @id) THEN
    SET v_status = 'NOT_FOUND';
  ELSEIF NOT EXISTS (SELECT 1 FROM {{USERS}} WHERE id = @id AND updated_at = @expected_updated_at) THEN
    SET v_status = 'STALE_ACCOUNT';
  ELSEIF EXISTS (SELECT 1 FROM {{USERS}} WHERE email = @email AND id != @id) THEN
    SET v_status = 'DUPLICATE_EMAIL';
  ELSE
    SET v_user = (SELECT AS STRUCT id, name, email, password_hash, role, enabled, created_at, updated_at
      FROM {{USERS}} WHERE id = @id);
    -- Security-relevant changes revoke every refresh family for this account.
    IF NOT @enabled OR @email != v_user.email OR @password_hash != v_user.password_hash OR @role != v_user.role THEN
      SET v_expected = (SELECT COUNT(*) FROM {{SESSIONS}} WHERE user_id = @id AND revoked_at IS NULL);
      UPDATE {{SESSIONS}} SET revoked_at = v_now WHERE user_id = @id AND revoked_at IS NULL;
      ASSERT @@row_count = v_expected AS 'IDENTITY_AFFECTED_ROWS';
    END IF;
    UPDATE {{USERS}} SET name = IF(@name_present, @name, NULL), email = @email, password_hash = @password_hash,
      role = @role, enabled = @enabled,
      updated_at = GREATEST(v_now, TIMESTAMP_ADD(updated_at, INTERVAL 1 MICROSECOND))
      WHERE id = @id AND updated_at = @expected_updated_at;
    ASSERT @@row_count = 1 AS 'IDENTITY_AFFECTED_ROWS';
    SET v_user = (SELECT AS STRUCT id, name, email, password_hash, role, enabled, created_at, updated_at
      FROM {{USERS}} WHERE id = @id);
    SET v_status = 'UPDATED';
  END IF;

ELSEIF @operation = 'CREATE_SESSION' THEN
  IF NOT EXISTS (SELECT 1 FROM {{USERS}} WHERE id = @id AND enabled = TRUE
      AND email = @email AND password_hash = @password_hash AND role = @role
      AND updated_at = @expected_updated_at) THEN
    SET v_status = 'INVALID';
  ELSE
    ASSERT @ttl_micros > 0 AS 'IDENTITY_EXPIRY_INVALID';
    ASSERT NOT EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE id = @new_id
      OR token_hash = @new_token_hash OR token_family_id = @family_id) AS 'IDENTITY_SESSION_COLLISION';
    INSERT INTO {{SESSIONS}} (id, user_id, token_hash, token_family_id, expires_at,
        used_at, revoked_at, csrf_token_hash, created_at)
      VALUES (@new_id, @id, @new_token_hash, @family_id,
        TIMESTAMP_ADD(v_now, INTERVAL @ttl_micros MICROSECOND), NULL, NULL, @new_csrf_hash, v_now);
    ASSERT @@row_count = 1 AS 'IDENTITY_AFFECTED_ROWS';
    SET v_user = (SELECT AS STRUCT id, name, email, password_hash, role, enabled, created_at, updated_at
      FROM {{USERS}} WHERE id = @id);
    SET v_status = 'CREATED';
  END IF;

ELSEIF @operation = 'REVOKE_FAMILY' THEN
  IF EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE token_family_id = @family_id AND user_id = @id) THEN
    SET v_expected = (SELECT COUNT(*) FROM {{SESSIONS}} WHERE token_family_id = @family_id AND revoked_at IS NULL);
    UPDATE {{SESSIONS}} SET revoked_at = v_now WHERE token_family_id = @family_id AND revoked_at IS NULL;
    ASSERT @@row_count = v_expected AS 'IDENTITY_AFFECTED_ROWS';
    SET v_status = 'REVOKED';
  ELSE
    SET v_status = 'NOT_FOUND';
  END IF;

ELSEIF @operation IN ('ROTATE', 'REVOKE_TOKEN') THEN
  IF EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE token_hash = @token_hash) THEN
    SET v_owner = (SELECT user_id FROM {{SESSIONS}} WHERE token_hash = @token_hash);
    SET v_family = (SELECT token_family_id FROM {{SESSIONS}} WHERE token_hash = @token_hash);
    IF (SELECT csrf_token_hash FROM {{SESSIONS}} WHERE token_hash = @token_hash) != @csrf_hash THEN
      SET v_status = 'CSRF_REJECTED';
    ELSEIF @operation = 'REVOKE_TOKEN' THEN
      SET v_expected = (SELECT COUNT(*) FROM {{SESSIONS}} WHERE token_family_id = v_family AND revoked_at IS NULL);
      UPDATE {{SESSIONS}} SET revoked_at = v_now WHERE token_family_id = v_family AND revoked_at IS NULL;
      ASSERT @@row_count = v_expected AS 'IDENTITY_AFFECTED_ROWS';
      SET v_status = 'REVOKED';
    ELSEIF EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE token_hash = @token_hash
        AND (used_at IS NOT NULL OR revoked_at IS NOT NULL)) THEN
      SET v_expected = (SELECT COUNT(*) FROM {{SESSIONS}} WHERE token_family_id = v_family AND revoked_at IS NULL);
      UPDATE {{SESSIONS}} SET revoked_at = v_now WHERE token_family_id = v_family AND revoked_at IS NULL;
      ASSERT @@row_count = v_expected AS 'IDENTITY_AFFECTED_ROWS';
      SET v_status = 'REUSED';
    ELSEIF EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE token_hash = @token_hash AND expires_at <= v_now)
        OR NOT EXISTS (SELECT 1 FROM {{USERS}} WHERE id = v_owner AND enabled = TRUE) THEN
      SET v_expected = (SELECT COUNT(*) FROM {{SESSIONS}} WHERE token_family_id = v_family AND revoked_at IS NULL);
      UPDATE {{SESSIONS}} SET revoked_at = v_now WHERE token_family_id = v_family AND revoked_at IS NULL;
      ASSERT @@row_count = v_expected AS 'IDENTITY_AFFECTED_ROWS';
      SET v_status = 'INVALID';
    ELSE
      ASSERT @ttl_micros > 0 AS 'IDENTITY_EXPIRY_INVALID';
      ASSERT NOT EXISTS (SELECT 1 FROM {{SESSIONS}} WHERE id = @new_id OR token_hash = @new_token_hash)
        AS 'IDENTITY_SESSION_COLLISION';
      UPDATE {{SESSIONS}} SET used_at = v_now, revoked_at = v_now WHERE token_hash = @token_hash
        AND used_at IS NULL AND revoked_at IS NULL AND expires_at > v_now;
      ASSERT @@row_count = 1 AS 'IDENTITY_AFFECTED_ROWS';
      INSERT INTO {{SESSIONS}} (id, user_id, token_hash, token_family_id, expires_at,
          used_at, revoked_at, csrf_token_hash, created_at)
        VALUES (@new_id, v_owner, @new_token_hash, v_family,
          TIMESTAMP_ADD(v_now, INTERVAL @ttl_micros MICROSECOND), NULL, NULL, @new_csrf_hash, v_now);
      ASSERT @@row_count = 1 AS 'IDENTITY_AFFECTED_ROWS';
      SET v_user = (SELECT AS STRUCT id, name, email, password_hash, role, enabled, created_at, updated_at
        FROM {{USERS}} WHERE id = v_owner);
      SET v_status = 'SUCCESS';
    END IF;
  END IF;
END IF;

COMMIT TRANSACTION;
-- Denials are returned AFTER commit; throwing to signal reuse here would be unsafe.
SELECT v_status AS status, v_user.id AS id, v_user.name AS name, v_user.email AS email,
  v_user.password_hash AS password_hash, v_user.role AS role, v_user.enabled AS enabled,
  v_user.created_at AS created_at, v_user.updated_at AS updated_at;
