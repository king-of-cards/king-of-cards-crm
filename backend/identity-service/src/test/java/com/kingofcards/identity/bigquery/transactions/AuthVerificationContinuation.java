package com.kingofcards.identity.bigquery.transactions;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import com.google.cloud.bigquery.*;

/** Test-only continuation of the reviewed, partially completed synthetic run. No cleanup. */
final class AuthVerificationContinuation {
    static final String RUN = "4cd03a9f-db94-4150-8b5c-3ddc4c3c8fd8";
    static final String MANIFEST_SHA256 = "272f3b272250ce48653e2ff89214a4f6450da2c364f3ddd8a674e6af956f6a87";
    static final int PREVIOUS_SUBMISSIONS = 59; // original run, continuation and inventories

    static JsonObject manifest(Path path, String run) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        if (!RUN.equals(run) || !MANIFEST_SHA256.equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))))
            throw new IllegalArgumentException("Continuation requires the exact reviewed inventory.");
        var result = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!run.equals(result.get("runId").getAsString()) || result.getAsJsonArray("users").size()!=9
                || result.getAsJsonArray("sessions").size()!=10)
            throw new IllegalArgumentException("Unexpected continuation manifest shape.");
        return result;
    }

    static void verify(BigQuery client, JsonObject manifest) throws Exception {
        for (boolean user : List.of(true, false)) {
            var records = manifest.getAsJsonArray(user ? "users" : "sessions");
            var parameters = new HashMap<String, QueryParameterValue>();
            var predicates = new ArrayList<String>();
            int index=0;
            for (var item : records) {
                var record=item.getAsJsonObject();
                parameters.put("id"+index,QueryParameterValue.string(record.get("id").getAsString()));
                parameters.put("fp"+index,QueryParameterValue.string(record.get("rowFingerprint").getAsString()));
                predicates.add("(id=@id"+index+" AND fingerprint=@fp"+index+")"); index++;
            }
            String columns=user ? "u.id,u.name,u.email,u.password_hash,u.role,u.enabled,u.created_at,u.updated_at"
                : "u.id,u.user_id,u.token_hash,u.token_family_id,u.expires_at,u.used_at,u.revoked_at,u.csrf_token_hash,u.created_at";
            String table=user ? IsolatedAuthTarget.users() : IsolatedAuthTarget.sessions();
            String sql="SELECT COUNT(*) AS total, COUNTIF("+String.join(" OR ",predicates)+") AS matched FROM "
                +"(SELECT u.id,TO_HEX(SHA256(TO_JSON_STRING(STRUCT("+columns+")))) AS fingerprint FROM "+table+" u)";
            var query=QueryJobConfiguration.newBuilder(sql).setNamedParameters(parameters).setUseLegacySql(false)
                .setUseQueryCache(false).setMaximumBytesBilled(100_000_000L).setJobTimeoutMs(20_000L).build();
            var id=JobId.newBuilder().setProject(IsolatedAuthTarget.PROJECT).setLocation(IsolatedAuthTarget.LOCATION)
                .setJob("koc_auth_continuation_preflight_"+UUID.randomUUID().toString().replace("-","")).build();
            var row=client.query(query,id).iterateAll().iterator().next();
            if(row.get("total").getLongValue()!=records.size() || row.get("matched").getLongValue()!=records.size())
                throw new IllegalStateException("Auth records changed: continuation is forbidden.");
            System.out.println("Continuation exact manifest verified: "+(user?"users":"sessions")+"="+records.size()+" job="+id.getJob());
        }
    }

    static void requireScenario(int order) {
        if(order<7 || order>12)throw new IllegalStateException("Continuation forbids completed/bootstrap scenarios.");
    }
}
