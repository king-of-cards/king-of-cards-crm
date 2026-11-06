package com.kingofcards.identity.bigquery.transactions;

import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;

/** Reuse this EXACT immutable plan on retry; never reconstruct its timestamps or parameters.
 * No default record toString: parameters contain password/token hashes and personal data.
 */
public final class AuthTransactionPlan {
    private final JobId jobId;
    private final Instant deadline;
    private final QueryJobConfiguration query;
    AuthTransactionPlan(UUID operationId, Instant deadline, QueryJobConfiguration query) {
        if (operationId==null || deadline==null || query==null || !AuthTransactionPlanner.trustedSql().equals(query.getQuery()))
            throw new IllegalArgumentException("Only the fixed isolated Auth transaction script is permitted.");
        if (!Boolean.FALSE.equals(query.useLegacySql()) || !Boolean.FALSE.equals(query.useQueryCache())
                || query.getMaximumBytesBilled()==null || !Long.valueOf(45_000L).equals(query.getJobTimeoutMs()))
            throw new IllegalArgumentException("Explicit isolated query controls are required.");
        AuthTransactionPlanner.validateByteBudget(query.getMaximumBytesBilled());
        var parameters=query.getNamedParameters();
        var action=parameters.get("operation");
        if (action==null || !Set.of("BOOTSTRAP","CREATE_USER","UPDATE_ACCOUNT","CREATE_SESSION","ROTATE",
                "REVOKE_TOKEN","REVOKE_FAMILY","VENDOR_INVITE","VENDOR_SETUP").contains(action.getValue())
                || parameters.values().stream().anyMatch(value->value==null || value.getValue()==null))
            throw new IllegalArgumentException("Invalid isolated operation parameters.");
        long micros=Math.addExact(Math.multiplyExact(deadline.getEpochSecond(),1_000_000L),deadline.getNano()/1000);
        if (!QueryParameterValue.timestamp(micros).equals(parameters.get("request_deadline")))
            throw new IllegalArgumentException("Plan deadline must match the bound SQL deadline.");
        this.jobId = JobId.newBuilder().setProject(IsolatedAuthTarget.PROJECT)
            .setLocation(IsolatedAuthTarget.LOCATION).setJob("koc_auth_verify_" + operationId.toString().replace("-", "")).build();
        this.deadline = deadline;
        this.query = query;
    }
    public JobId jobId() { return jobId; }
    public Instant deadline() { return deadline; }
    QueryJobConfiguration query() { return query; }
    @Override public String toString() { return "AuthTransactionPlan[jobId=" + jobId.getJob() + ", parameters=REDACTED]"; }
}
