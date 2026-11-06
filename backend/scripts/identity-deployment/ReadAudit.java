package com.kingofcards.identity.maintenance;

import com.google.cloud.bigquery.*;
import com.google.auth.oauth2.*;
import java.util.*;

/** Only metadata GETs and one fixed aggregate SELECT. Never retrieves auth-result rows. */
public final class ReadAudit {
    public static Properties inspect(BigQuery client,String expectedPrincipal)throws Exception {
        Safety.require(client.getOptions().getCredentials() instanceof ServiceAccountCredentials,"Unsupported principal");
        var account=(ServiceAccountCredentials)client.getOptions().getCredentials();
        Safety.require(Safety.textHash(account.getClientEmail()).equals(expectedPrincipal),"Principal differs from approved launcher");
        var dataset=client.getDataset(DatasetId.of("king-of-cards-509610","shared_rds_data"));
        Safety.require(dataset!=null&&"asia-south1".equalsIgnoreCase(dataset.getLocation()),"Dataset/region differs");
        var result=new Properties();result.setProperty("principalVerified","true");
        for(String table:List.of("identity_user","identity_refresh_session")){
            var metadata=client.getTable(TableId.of("king-of-cards-509610","shared_rds_data",table));
            Safety.require(metadata!=null&&metadata.getDefinition().getSchema()!=null,"Identity schema unavailable");
            result.setProperty(table.equals("identity_user")?"usersSchema":"sessionsSchema",Safety.textHash(metadata.getDefinition().getSchema().toString()));
        }
        String u="`king-of-cards-509610.shared_rds_data.identity_user`",s="`king-of-cards-509610.shared_rds_data.identity_refresh_session`";
        String sql="SELECT (SELECT COUNT(*) FROM "+u+") accounts,(SELECT COUNT(*) FROM "+s+") sessions,"
            +"(SELECT COUNT(*) FROM "+s+" s LEFT JOIN "+u+" u ON u.id=s.user_id WHERE u.id IS NULL) orphans,"
            +"(SELECT TO_HEX(SHA256(COALESCE(STRING_AGG(h,'' ORDER BY h),''))) FROM (SELECT TO_HEX(SHA256(TO_JSON_STRING(t))) h FROM "+u+" t)) accountDigest,"
            +"(SELECT TO_HEX(SHA256(COALESCE(STRING_AGG(h,'' ORDER BY h),''))) FROM (SELECT TO_HEX(SHA256(TO_JSON_STRING(t))) h FROM "+s+" t)) sessionDigest";
        var query=QueryJobConfiguration.newBuilder(sql).setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(100_000_000L).setJobTimeoutMs(20_000L).build();
        var id=JobId.newBuilder().setProject("king-of-cards-509610").setLocation("asia-south1").setJob("koc_identity_read_audit_"+UUID.randomUUID().toString().replace("-","")).build();
        var rows=client.query(query,id).iterateAll().iterator();Safety.require(rows.hasNext(),"Aggregate missing");var row=rows.next();Safety.require(!rows.hasNext(),"Aggregate is not scalar");
        for(String n:List.of("accounts","sessions","orphans","accountDigest","sessionDigest"))result.setProperty(n,row.get(n).getStringValue());
        Safety.require("0".equals(result.getProperty("orphans")),"Orphan sessions require review");
        // Omit allUsers(): SDK 2.51's no-arg allUsers() means TRUE. API default is
        // false; only this configured principal's jobs are requested, never listAll.
        var page=client.listJobs(BigQuery.JobListOption.pageSize(1000),BigQuery.JobListOption.stateFilter(JobStatus.State.PENDING,JobStatus.State.RUNNING));
        int pages=0,active=0;var seen=new HashSet<String>();
        while(true){pages++;for(Job job:page.getValues())if(job.getJobId().getJob().startsWith("koc_auth_runtime_")){Safety.require(seen.add(job.getJobId().getJob()),"Repeated auth job metadata");active++;}
            if(!page.hasNextPage())break;Safety.require(pages<10,"Active job pagination incomplete");page=page.getNextPage();}
        result.setProperty("activeAuthJobs",Integer.toString(active));result.setProperty("ownJobPagesComplete","true");result.setProperty("ownJobPages",Integer.toString(pages));
        // Narrow request-deadline window only; do not scan unrelated CRM history.
        var recent=client.listJobs(BigQuery.JobListOption.minCreationTime(System.currentTimeMillis()-360_000L),BigQuery.JobListOption.pageSize(1000));
        int recentPages=0,parents=0,nonterminal=0;seen.clear();String trusted;
        try(var zip=new java.util.zip.ZipFile(Safety.ROOT.resolve("identity-service/target-final-integration-verify-20261106/identity-service-0.1.0-SNAPSHOT.jar").toFile());var in=zip.getInputStream(zip.getEntry("BOOT-INF/classes/bigquery/auth-production-transaction.sql"))){trusted=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("{{USERS}}",u).replace("{{SESSIONS}}",s);}
        while(true){
            recentPages++;for(Job reference:recent.getValues())if(reference.getJobId().getJob().startsWith("koc_auth_runtime_")){
                Safety.require(seen.add(reference.getJobId().getJob())&&parents<100,"Auth window incomplete/over safety limit");
                var job=client.getJob(reference.getJobId());Safety.require(job!=null&&job.getStatus()!=null&&"asia-south1".equalsIgnoreCase(job.getJobId().getLocation()),"Auth job metadata missing/wrong region");
                Safety.require(job.getConfiguration() instanceof QueryJobConfiguration&&trusted.equals(((QueryJobConfiguration)job.getConfiguration()).getQuery()),"Auth job SQL differs from pinned protocol");
                boolean done=job.getStatus().getState()==JobStatus.State.DONE;if(!done)nonterminal++;
                result.setProperty("recentAuth."+parents+".jobId",job.getJobId().getJob());
                result.setProperty("recentAuth."+parents+".state",!done?"NONTERMINAL":job.getStatus().getError()==null?"DONE_NO_JOB_ERROR":"DONE_WITH_ERROR_REVIEW_REQUIRED");parents++;
            }
            if(!recent.hasNextPage())break;Safety.require(recentPages<10,"Recent own-job pagination incomplete");recent=recent.getNextPage();
        }
        result.setProperty("recentAuthPagesComplete","true");result.setProperty("recentAuthParents",Integer.toString(parents));result.setProperty("recentAuthNonterminal",Integer.toString(nonterminal));return result;
    }
}
