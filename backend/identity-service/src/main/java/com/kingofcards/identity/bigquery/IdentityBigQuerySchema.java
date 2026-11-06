package com.kingofcards.identity.bigquery;

import java.util.Map;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import static com.google.cloud.bigquery.StandardSQLTypeName.*;

/** Expected externally managed schemas; never issues DDL. */
public final class IdentityBigQuerySchema {
    private IdentityBigQuerySchema() {}
    public static Map<String, Schema> tables() {
        return Map.of("identity_user", Schema.of(required("id", STRING), nullable("name", STRING),
            required("email", STRING), required("password_hash", STRING), required("role", STRING),
            required("enabled", BOOL), required("created_at", TIMESTAMP), required("updated_at", TIMESTAMP)),
            "identity_refresh_session", Schema.of(required("id", STRING), required("user_id", STRING),
            required("token_hash", STRING), required("token_family_id", STRING), required("expires_at", TIMESTAMP),
            nullable("used_at", TIMESTAMP), nullable("revoked_at", TIMESTAMP),
            required("csrf_token_hash", STRING), required("created_at", TIMESTAMP)));
    }
    private static Field required(String name, StandardSQLTypeName type) {
        return Field.newBuilder(name, type).setMode(Field.Mode.REQUIRED).build();
    }
    private static Field nullable(String name, StandardSQLTypeName type) {
        return Field.newBuilder(name, type).setMode(Field.Mode.NULLABLE).build();
    }
}
