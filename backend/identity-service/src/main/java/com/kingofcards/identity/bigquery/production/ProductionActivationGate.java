package com.kingofcards.identity.bigquery.production;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;

/** Explicit operator activation additionally requires reviewed engine evidence,
 * identical verified bytecode/SQL, and removal of known synthetic credentials.
 * No environment boolean alone can open the mutation gate.
 */
public final class ProductionActivationGate {
    private static final String APPROVED_EVIDENCE_SHA256 = "8bbd916ae7287419a72ab291ddcbf46c2780a9ad9d86e835c285652f1a93360b";
    private ProductionActivationGate() {}
    public static void verifyEvidence(Path file) {
        try {
            if(!Files.isRegularFile(file) || Files.size(file)>1_000_000)
                throw new IllegalStateException("Reviewed production verification evidence is required.");
            byte[] bytes=Files.readAllBytes(file);
            if(!digest(bytes).equals(APPROVED_EVIDENCE_SHA256))
                throw new IllegalStateException("Production verification evidence is not approved.");
            var proof=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(!proof.get("allTwelveScenariosPassed").getAsBoolean() || !proof.get("productionContractPassed").getAsBoolean())
                throw new IllegalStateException("Required authentication verification is incomplete.");
            for(var entry:proof.getAsJsonObject("classDigests").entrySet()){
                try(var input=ProductionActivationGate.class.getResourceAsStream("/"+entry.getKey())){
                    if(input==null || !digest(input.readAllBytes()).equals(entry.getValue().getAsString()))
                        throw new IllegalStateException("Authentication bytecode differs from verified build.");
                }
            }
            try(var input=ProductionActivationGate.class.getResourceAsStream("/bigquery/auth-production-transaction.sql")){
                if(input==null || !digest(input.readAllBytes()).equals(proof.get("sqlDigest").getAsString()))
                    throw new IllegalStateException("Authentication SQL differs from verified build.");
            }
        } catch(IllegalStateException exception){throw exception;}
        catch(Exception exception){throw new IllegalStateException("Production verification evidence cannot be validated.");}
    }
    public static void verifyNoSyntheticOrOrphanRecords(BigQuery client,IdentityBigQueryProperties properties) {
        properties.validate();
        String sql="SELECT (SELECT COUNT(*) FROM "+properties.table("identity_user")
            +" WHERE ENDS_WITH(email, '@example.test')) AS synthetic_users, (SELECT COUNT(*) FROM "
            +properties.table("identity_refresh_session")+" s LEFT JOIN "+properties.table("identity_user")
            +" u ON u.id=s.user_id WHERE u.id IS NULL) AS orphan_sessions";
        try {
            var query=QueryJobConfiguration.newBuilder(sql).setUseLegacySql(false).setUseQueryCache(false)
                .setMaximumBytesBilled(100_000_000L).setJobTimeoutMs(20_000L).build();
            var id=JobId.newBuilder().setProject(properties.projectId()).setLocation(properties.location())
                .setJob("koc_auth_activation_check_"+UUID.randomUUID().toString().replace("-","")).build();
            var rows=client.query(query,id).iterateAll().iterator();
            if(!rows.hasNext())throw new IdentityPersistenceException("IDENTITY_ACTIVATION_CHECK_FAILED");
            var row=rows.next();
            if(rows.hasNext() || row.get("synthetic_users").getLongValue()!=0 || row.get("orphan_sessions").getLongValue()!=0)
                throw new IdentityPersistenceException("IDENTITY_SYNTHETIC_CLEANUP_OR_INTEGRITY_REVIEW_REQUIRED");
        } catch(InterruptedException exception){Thread.currentThread().interrupt();throw new IdentityPersistenceException("IDENTITY_ACTIVATION_CHECK_INTERRUPTED");}
        catch(IdentityPersistenceException exception){throw exception;}
        catch(RuntimeException exception){throw new IdentityPersistenceException("IDENTITY_ACTIVATION_CHECK_FAILED");}
    }
    private static String digest(byte[] bytes)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
