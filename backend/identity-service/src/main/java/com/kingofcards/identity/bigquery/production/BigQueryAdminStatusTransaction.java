package com.kingofcards.identity.bigquery.production;

import java.util.*;
import java.time.Instant;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

/** New protocol, deliberately NOT a Spring bean and NOT part of the approved Auth adapter.
 * Requires separate real-engine evidence before any production wiring. The caller must retain
 * operation IDs across uncertain outcomes; this class never generates a replacement operation.
 */
public final class BigQueryAdminStatusTransaction {
    private final BigQuery client;
    private final IdentityBigQueryProperties properties;
    public BigQueryAdminStatusTransaction(BigQuery client,IdentityBigQueryProperties properties) {
        this.client=Objects.requireNonNull(client);this.properties=Objects.requireNonNull(properties);properties.validate();
    }
    public void setEnabled(UUID operation,UUID actor,UUID target,boolean enabled,Instant deadline) {
        if(!properties.enabled())throw new IdentityPersistenceException("IDENTITY_BIGQUERY_DISABLED");
        Objects.requireNonNull(operation);Objects.requireNonNull(actor);Objects.requireNonNull(target);Objects.requireNonNull(deadline);
        if(deadline.isAfter(Instant.now().plusSeconds(120)))throw new IllegalArgumentException("Admin operation deadline exceeds two minutes.");
        if(actor.equals(target) && !enabled)throw new AccessDeniedException("You cannot block your own account.");
        var query=QueryJobConfiguration.newBuilder(sql()).setUseLegacySql(false).setUseQueryCache(false)
            .setMaximumBytesBilled(536_870_912L).setJobTimeoutMs(45_000L)
            .addNamedParameter("actor",QueryParameterValue.string(actor.toString()))
            .addNamedParameter("target",QueryParameterValue.string(target.toString()))
            .addNamedParameter("deadline",QueryParameterValue.timestamp(Math.addExact(Math.multiplyExact(deadline.getEpochSecond(),1_000_000L),deadline.getNano()/1000)))
            .addNamedParameter("enabled",QueryParameterValue.bool(enabled)).build();
        var id=JobId.newBuilder().setProject(properties.projectId()).setLocation(properties.location())
            .setJob("koc_admin_status_v1_"+operation.toString().replace("-","")).build();
        try {
            Job job=client.getJob(id);
            if(job==null)try{if(!deadline.isAfter(Instant.now()))throw unknown();job=client.create(JobInfo.newBuilder(query).setJobId(id).build());}
                catch(BigQueryException submission){job=client.getJob(id);if(job==null)throw unknown();}
            bind(job,id,query);
            for(int poll=0;poll<100 && (job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE);poll++){
                Thread.sleep(500);job=client.getJob(id);if(job==null)throw unknown();bind(job,id,query);
            }
            if(job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE || job.getStatus().getError()!=null)throw unknown();
            var rows=job.getQueryResults().iterateAll().iterator();if(!rows.hasNext())throw unknown();var row=rows.next();
            if(rows.hasNext() || !actor.toString().equals(row.get("actor").getStringValue())
                    || !target.toString().equals(row.get("target").getStringValue()) || enabled!=row.get("enabled").getBooleanValue())throw unknown();
            switch(row.get("status").getStringValue()) {
                case "UPDATED" -> { }
                case "LAST_ACTIVE_ADMIN" -> throw new IdentityPersistenceException("LAST_ACTIVE_ADMIN");
                case "ACTOR_FORBIDDEN", "SELF_BLOCK" -> throw new AccessDeniedException("Account status change is not authorized.");
                case "NOT_FOUND" -> throw new VendorDirectoryException(HttpStatus.NOT_FOUND,"ADMIN_NOT_FOUND","Administrator was not found.");
                default -> throw unknown();
            }
        } catch(InterruptedException exception){Thread.currentThread().interrupt();throw unknown();}
        catch(IdentityPersistenceException | AccessDeniedException | VendorDirectoryException exception){throw exception;}
        catch(RuntimeException exception){throw unknown();}
    }
    private String sql() {
        String users=properties.table("identity_user"),sessions=properties.table("identity_refresh_session");
        return "DECLARE outcome STRING DEFAULT 'INVALID'; DECLARE expected_sessions INT64 DEFAULT 0; DECLARE now_ts TIMESTAMP;\n"
            +"ASSERT CURRENT_TIMESTAMP()<@deadline AS 'ADMIN_REQUEST_EXPIRED';\n"
            +"BEGIN TRANSACTION;\nDELETE FROM "+users+" WHERE FALSE; ASSERT @@row_count=0 AS 'ADMIN_LOCK_FAILED';\n"
            +"DELETE FROM "+sessions+" WHERE FALSE; ASSERT @@row_count=0 AS 'ADMIN_LOCK_FAILED';\nSET now_ts=CURRENT_TIMESTAMP();\n"
            +"ASSERT NOT EXISTS(SELECT id FROM "+users+" GROUP BY id HAVING COUNT(*)>1) AS 'ADMIN_ID_INTEGRITY';\n"
            +"ASSERT NOT EXISTS(SELECT id FROM "+sessions+" GROUP BY id HAVING COUNT(*)>1) AS 'SESSION_ID_INTEGRITY';\n"
            +"IF NOT EXISTS(SELECT 1 FROM "+users+" WHERE id=@actor AND role='CRM_ADMIN' AND enabled=TRUE) THEN SET outcome='ACTOR_FORBIDDEN';\n"
            +"ELSEIF NOT EXISTS(SELECT 1 FROM "+users+" WHERE id=@target AND role='CRM_ADMIN') THEN SET outcome='NOT_FOUND';\n"
            +"ELSEIF NOT @enabled AND @actor=@target THEN SET outcome='SELF_BLOCK';\n"
            +"ELSEIF NOT @enabled AND EXISTS(SELECT 1 FROM "+users+" WHERE id=@target AND enabled=TRUE)\n"
            +" AND (SELECT COUNT(*) FROM "+users+" WHERE role='CRM_ADMIN' AND enabled=TRUE)<=1 THEN SET outcome='LAST_ACTIVE_ADMIN';\n"
            +"ELSE\nSET expected_sessions=(SELECT COUNT(*) FROM "+sessions+" WHERE user_id=@target AND revoked_at IS NULL);\n"
            +"UPDATE "+sessions+" SET revoked_at=now_ts WHERE user_id=@target AND revoked_at IS NULL;\n"
            +"ASSERT @@row_count=expected_sessions AS 'ADMIN_SESSION_ROWS';\n"
            +"UPDATE "+users+" SET enabled=@enabled,updated_at=GREATEST(now_ts,TIMESTAMP_ADD(updated_at,INTERVAL 1 MICROSECOND)) WHERE id=@target AND role='CRM_ADMIN';\n"
            +"ASSERT @@row_count=1 AS 'ADMIN_ACCOUNT_ROWS'; SET outcome='UPDATED';\nEND IF;\n"
            +"COMMIT TRANSACTION;\nSELECT outcome AS status,@actor AS actor,@target AS target,@enabled AS enabled;";
    }
    private static void bind(Job job,JobId id,QueryJobConfiguration query) {
        if(job==null || !id.equals(job.getJobId()) || !(job.getConfiguration() instanceof QueryJobConfiguration actual)
                || !query.getQuery().equals(actual.getQuery()) || !query.getNamedParameters().equals(actual.getNamedParameters())
                || !Objects.equals(query.getMaximumBytesBilled(),actual.getMaximumBytesBilled())
                || !Objects.equals(query.getJobTimeoutMs(),actual.getJobTimeoutMs())
                || !Objects.equals(query.useQueryCache(),actual.useQueryCache()) || !Objects.equals(query.useLegacySql(),actual.useLegacySql())
                || actual.getDestinationTable()!=null || actual.getCreateDisposition()!=null || actual.getWriteDisposition()!=null)throw unknown();
    }
    private static IdentityPersistenceException unknown(){return new IdentityPersistenceException("ADMIN_STATUS_COMMIT_UNCERTAIN_RECONCILE_REQUIRED");}
}
