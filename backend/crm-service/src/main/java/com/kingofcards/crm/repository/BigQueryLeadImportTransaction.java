package com.kingofcards.crm.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.dto.CreateLeadRequest;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.service.AtomicLeadImportStore;
import org.springframework.http.HttpStatus;

/** Offline-verifiable engine adapter, intentionally NOT a Spring bean. It must NOT replace
 * UnverifiedLeadImportStore until vendor validation, all-writer serialization and live-engine
 * evidence are approved. No flags or automatic fallback activate this implementation.
 */
public final class BigQueryLeadImportTransaction implements AtomicLeadImportStore {
    private final BigQuery client;
    private final BigQueryProperties properties;
    private static final long BUDGET=536_870_912L;
    public BigQueryLeadImportTransaction(BigQuery client,BigQueryProperties properties) {
        this.client=Objects.requireNonNull(client);this.properties=Objects.requireNonNull(properties);
        properties.validateForSchemaInitialization();
    }
    @Override public Result importBatch(UUID operation,UUID actor,String fingerprint,List<CreateLeadRequest> rows) {
        if(!properties.enabled() || !properties.writesEnabled())throw failure("LEAD_WRITES_DISABLED");
        if(operation==null || actor==null || fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")
                || rows==null || rows.isEmpty() || rows.size()>200)throw LeadApiException.badRequest("Invalid bounded import command.");
        rows=List.copyOf(rows);
        var query=plan(actor,fingerprint,rows);
        var id=JobId.newBuilder().setProject(properties.projectId()).setLocation(properties.location())
            .setJob("koc_lead_import_v1_"+actor.toString().replace("-","")+"_"+operation.toString().replace("-","")).build();
        try {
            // Recover the SAME operation before considering submission. Never invent another ID.
            Job job=client.getJob(id);
            if(job==null){
                try { job=client.create(JobInfo.newBuilder(query).setJobId(id).build()); }
                catch(BigQueryException submission) {
                    // Includes duplicate IDs, network failures, and ambiguous submission outcomes.
                    job=client.getJob(id);if(job==null)throw failure("BULK_COMMIT_UNCERTAIN");
                }
            }
            binding(job,id,query);
            for(int poll=0;poll<100 && (job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE);poll++) {
                Thread.sleep(500);
                job=client.getJob(id);
                if(job==null)throw failure("BULK_COMMIT_UNCERTAIN");
                binding(job,id,query);
            }
            if(job==null)throw failure("BULK_COMMIT_UNCERTAIN");
            binding(job,id,query);
            if(job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE)throw failure("BULK_COMMIT_UNCERTAIN");
            if(job.getStatus().getError()!=null) {
                String reason=job.getStatus().getError().getReason();
                String message=job.getStatus().getError().getMessage();
                if("concurrentUpdate".equals(reason) || (message!=null && message.contains("Transaction is aborted due to concurrent update")))throw failure("BULK_TRANSACTION_CONFLICT_RECONCILE_REQUIRED");
                throw failure("BULK_JOB_FAILED_REVIEW_REQUIRED");
            }
            var results=job.getQueryResults().iterateAll().iterator();
            if(!results.hasNext())throw failure("BULK_COMMIT_UNCERTAIN");
            var receipt=results.next();
            if(results.hasNext() || !fingerprint.equals(receipt.get("fingerprint").getStringValue()))throw failure("BULK_COMMIT_UNCERTAIN");
            int imported=Math.toIntExact(receipt.get("imported").getLongValue());
            int duplicates=Math.toIntExact(receipt.get("duplicates").getLongValue());
            int total=Math.toIntExact(receipt.get("total").getLongValue());
            if(total!=rows.size())throw failure("BULK_COMMIT_UNCERTAIN");
            return new Result(total,imported,duplicates);
        } catch(InterruptedException exception) { Thread.currentThread().interrupt();throw failure("BULK_COMMIT_UNCERTAIN"); }
        catch(LeadApiException exception){throw exception;}
        catch(RuntimeException exception){throw failure("BULK_COMMIT_UNCERTAIN");}
    }
    private QueryJobConfiguration plan(UUID actor,String fingerprint,List<CreateLeadRequest> rows) {
        String table="`"+properties.projectId()+"."+properties.dataset()+"."+properties.leadsTable()+"`";
        var query=QueryJobConfiguration.newBuilder("SELECT 1");var incoming=new ArrayList<String>();
        query.addNamedParameter("actor",QueryParameterValue.string(actor.toString()))
            .addNamedParameter("fingerprint",QueryParameterValue.string(fingerprint));
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);
            if(row==null || row.name()==null || row.name().isBlank() || row.name().length()>120
                    || row.phone()==null || !row.phone().matches("\\+[1-9][0-9]{7,14}")
                    || row.service()==null || row.service().isBlank() || row.service().length()>80
                    || row.stage()==null || row.source()==null || row.source().isBlank() || row.source().length()>120
                    || row.vendorSubjectId()==null)throw LeadApiException.badRequest("Invalid import row.");
            String canonical=List.of(row.name(),row.phone(),row.service(),row.stage().name(),row.source(),row.vendorSubjectId().toString())
                .stream().map(value->value.length()+":"+value).collect(java.util.stream.Collectors.joining());
            String id=deterministicId(actor+":"+canonical).toString();
            String suffix="_"+i;
            query.addNamedParameter("id"+suffix,QueryParameterValue.string(id))
                .addNamedParameter("name"+suffix,QueryParameterValue.string(row.name()))
                .addNamedParameter("phone"+suffix,QueryParameterValue.string(row.phone()))
                .addNamedParameter("service"+suffix,QueryParameterValue.string(row.service()))
                .addNamedParameter("stage"+suffix,QueryParameterValue.string(row.stage().name()))
                .addNamedParameter("source"+suffix,QueryParameterValue.string(row.source()))
                .addNamedParameter("vendor"+suffix,QueryParameterValue.string(row.vendorSubjectId().toString()));
            incoming.add("SELECT @id"+suffix+" AS lead_id, @name"+suffix+" AS customer_name, @phone"+suffix
                +" AS phone, @service"+suffix+" AS service, @stage"+suffix+" AS stage, @source"+suffix
                +" AS source, @vendor"+suffix+" AS vendor_subject_id");
        }
        String same="e.customer_name=i.customer_name AND e.phone=i.phone AND e.service=i.service"
            +" AND e.stage=i.stage AND e.source=i.source AND e.vendor_subject_id=i.vendor_subject_id";
        String sql="DECLARE inserted INT64 DEFAULT 0;\nBEGIN TRANSACTION;\nDELETE FROM "+table+" WHERE FALSE;\n"
            +"ASSERT @@row_count=0 AS 'IMPORT_LOCK_FAILED';\n"
            +"CREATE TEMP TABLE incoming AS "+String.join(" UNION ALL ",incoming)+";\n"
            +"ASSERT NOT EXISTS (SELECT lead_id FROM incoming GROUP BY lead_id HAVING COUNT(*)>1) AS 'DUPLICATE_UPLOAD_ROW';\n"
            +"ASSERT NOT EXISTS (SELECT lead_id FROM "+table+" GROUP BY lead_id HAVING COUNT(*)>1) AS 'EXISTING_ID_CONFLICT';\n"
            +"ASSERT NOT EXISTS (SELECT 1 FROM incoming i JOIN "+table+" e USING(lead_id) WHERE NOT ("+same+")) AS 'IMPORT_ID_CONFLICT';\n"
            +"INSERT INTO "+table+" (lead_id,customer_name,phone,service,stage,source,vendor_subject_id,created_by_subject_id,created_at,updated_at)\n"
            +"SELECT i.lead_id,i.customer_name,i.phone,i.service,i.stage,i.source,i.vendor_subject_id,@actor,CURRENT_TIMESTAMP(),CURRENT_TIMESTAMP() FROM incoming i\n"
            +"WHERE NOT EXISTS (SELECT 1 FROM "+table+" e WHERE "+same+");\n"
            +"SET inserted=@@row_count;\nCOMMIT TRANSACTION;\n"
            +"SELECT @fingerprint AS fingerprint, inserted AS imported, (SELECT COUNT(*) FROM incoming)-inserted AS duplicates, (SELECT COUNT(*) FROM incoming) AS total;";
        // BigQuery forbids create_disposition for multi-statement scripts. The fixed SQL
        // contains only CREATE TEMP TABLE; there is no permanent DDL or dynamic SQL.
        return query.setQuery(sql).setUseLegacySql(false).setUseQueryCache(false)
            .setMaximumBytesBilled(BUDGET).setJobTimeoutMs(45_000L).build();
    }
    private static void binding(Job job,JobId id,QueryJobConfiguration expected) {
        if(job==null || !id.equals(job.getJobId()) || !(job.getConfiguration() instanceof QueryJobConfiguration actual)
                || !expected.getQuery().equals(actual.getQuery()) || !expected.getNamedParameters().equals(actual.getNamedParameters())
                || !Objects.equals(expected.getMaximumBytesBilled(),actual.getMaximumBytesBilled())
                || !Objects.equals(expected.getJobTimeoutMs(),actual.getJobTimeoutMs())
                || !Objects.equals(expected.useLegacySql(),actual.useLegacySql())
                || !Objects.equals(expected.useQueryCache(),actual.useQueryCache())
                || expected.getCreateDisposition()!=actual.getCreateDisposition())throw failure("BULK_OPERATION_BINDING_MISMATCH");
    }
    private static UUID deterministicId(String value) {
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var buffer=java.nio.ByteBuffer.wrap(bytes);long high=buffer.getLong(),low=buffer.getLong();
            return new UUID((high & 0xffffffffffff0fffL)|0x0000000000008000L,(low & 0x3fffffffffffffffL)|0x8000000000000000L);
        }catch(java.security.NoSuchAlgorithmException exception){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static LeadApiException failure(String code){return new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE,code,"Import is not confirmed. Reconcile the original operation; do not blindly resubmit.");}
}
