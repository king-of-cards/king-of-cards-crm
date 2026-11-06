package com.kingofcards.crm.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import com.google.cloud.bigquery.*;
import org.springframework.http.HttpStatus;
import com.kingofcards.crm.bigquery.CrmLeadSchema;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.*;

/** No JPA, DDL, table repair or implicit table creation. Every row operation is a bounded SQL job. */
public final class BigQueryLeadRepository implements LeadRepository {
    private static final String COLUMNS = "lead_id, customer_name, phone, service, stage, source, "
        + "vendor_subject_id, created_by_subject_id, created_at, updated_at";
    private final Supplier<BigQuery> clients;
    private final BigQueryProperties properties;

    public BigQueryLeadRepository(Supplier<BigQuery> clients, BigQueryProperties properties) {
        this.clients = clients; this.properties = properties;
    }

    @Override
    public List<CrmLeadRecord> search(LeadSearch search, UUID ownerSubjectId) {
        BigQuery client = ready(false);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM " + table() + " WHERE TRUE");
        var query = QueryJobConfiguration.newBuilder(sql.toString());
        if (ownerSubjectId != null) {
            sql.append(" AND vendor_subject_id = @owner");
            query.addNamedParameter("owner", QueryParameterValue.string(ownerSubjectId.toString()));
        }
        if (search.vendorSubjectId() != null) {
            sql.append(" AND vendor_subject_id = @vendor");
            query.addNamedParameter("vendor", QueryParameterValue.string(search.vendorSubjectId().toString()));
        }
        if (search.q() != null) {
            sql.append(" AND (STRPOS(LOWER(customer_name), LOWER(@q)) > 0"
                + " OR (@phone_search <> '' AND STRPOS(phone, @phone_search) > 0))");
            query.addNamedParameter("q", QueryParameterValue.string(search.q()));
            query.addNamedParameter("phone_search", QueryParameterValue.string(search.q().replaceAll("[^0-9]", "")));
        }
        if (search.service() != null) {
            sql.append(" AND service = @service");
            query.addNamedParameter("service", QueryParameterValue.string(search.service()));
        }
        if (search.source() != null) {
            sql.append(" AND source = @source");
            query.addNamedParameter("source", QueryParameterValue.string(search.source()));
        }
        if (search.stage() != null) {
            sql.append(" AND stage = @stage");
            query.addNamedParameter("stage", QueryParameterValue.string(search.stage().name()));
        }
        sql.append(" ORDER BY created_at DESC, lead_id ASC LIMIT @limit OFFSET @offset");
        query.setQuery(sql.toString()).addNamedParameter("limit", QueryParameterValue.int64(search.size() + 1L))
            .addNamedParameter("offset", QueryParameterValue.int64((long) search.page() * search.size()));
        return rows(execute(client, query), ownerSubjectId, search.size()+1, true);
    }

    @Override
    public Optional<CrmLeadRecord> find(UUID id, UUID ownerSubjectId) {
        BigQuery client = ready(false);
        String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE lead_id = @id";
        var query = QueryJobConfiguration.newBuilder(sql).addNamedParameter("id", QueryParameterValue.string(id.toString()));
        if (ownerSubjectId != null) {
            sql += " AND vendor_subject_id = @owner";
            query.addNamedParameter("owner", QueryParameterValue.string(ownerSubjectId.toString()));
        }
        query.setQuery(sql + " LIMIT 2");
        var rows = rows(execute(client, query), ownerSubjectId, 2, false);
        if (rows.size() > 1) throw new LeadApiException(HttpStatus.CONFLICT, "LEAD_ID_NOT_UNIQUE", "Lead ID requires data reconciliation.");
        if (!rows.isEmpty() && !rows.getFirst().leadId().equals(id)) throw LeadApiException.unavailable();
        return rows.stream().findFirst();
    }

    @Override
    public void assertWritesEnabled() {
        if (!properties.enabled() || !properties.writesEnabled()) {
            throw new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE, "LEAD_WRITES_DISABLED", "Lead creation is disabled.");
        }
    }

    @Override
    public void insert(CrmLeadRecord lead) {
        BigQuery client = ready(true);
        var query = QueryJobConfiguration.newBuilder("INSERT INTO " + table() + " (" + COLUMNS + ") VALUES "
            + "(@id, @name, @phone, @service, @stage, @source, @vendor, @creator, @created, @updated)")
            .addNamedParameter("id", QueryParameterValue.string(lead.leadId().toString()))
            .addNamedParameter("name", QueryParameterValue.string(lead.customerName()))
            .addNamedParameter("phone", QueryParameterValue.string(lead.phone()))
            .addNamedParameter("service", QueryParameterValue.string(lead.service()))
            .addNamedParameter("stage", QueryParameterValue.string(lead.stage().name()))
            .addNamedParameter("source", QueryParameterValue.string(lead.source()))
            .addNamedParameter("vendor", QueryParameterValue.string(lead.vendorSubjectId().toString()))
            .addNamedParameter("creator", QueryParameterValue.string(lead.createdBySubjectId().toString()))
            .addNamedParameter("created", QueryParameterValue.timestamp(micros(lead.createdAt())))
            .addNamedParameter("updated", QueryParameterValue.timestamp(micros(lead.updatedAt())));
        execute(client, query, true);
    }

    private BigQuery ready(boolean write) {
        if (write) assertWritesEnabled();
        if (!properties.enabled()) throw LeadApiException.unavailable();
        properties.validateForSchemaInitialization(); // same exact project/dataset/table/location allowlist
        BigQuery client = clients.get();
        if (client == null) throw LeadApiException.unavailable();
        try {
            Dataset dataset = client.getDataset(DatasetId.of(properties.projectId(), properties.dataset()));
            if (dataset == null) throw LeadApiException.unavailable();
            if (!properties.location().equalsIgnoreCase(dataset.getLocation())) {
                throw new LeadApiException(HttpStatus.CONFLICT, "BIGQUERY_LOCATION_MISMATCH", "Configured dataset location does not match.");
            }
            Table table = client.getTable(TableId.of(properties.projectId(), properties.dataset(), properties.leadsTable()));
            if (table == null) throw LeadApiException.unavailable();
            var mismatches = CrmLeadSchema.mismatches(table.getDefinition());
            if (!mismatches.isEmpty()) throw new LeadApiException(HttpStatus.CONFLICT, "BIGQUERY_SCHEMA_MISMATCH",
                "Configured crm_leads schema is incompatible: " + mismatches);
        } catch (BigQueryException exception) { throw LeadApiException.unavailable(); }
        return client;
    }

    private TableResult execute(BigQuery client, QueryJobConfiguration.Builder query) {
        return execute(client,query,false);
    }
    private TableResult execute(BigQuery client, QueryJobConfiguration.Builder query, boolean insert) {
        var configuration = query.setUseLegacySql(false).setMaximumBytesBilled(100_000_000L)
            .setJobTimeoutMs(20_000L).setCreateDisposition(JobInfo.CreateDisposition.CREATE_NEVER).build();
        var job = JobId.newBuilder().setProject(properties.projectId()).setLocation(properties.location())
            .setJob(UUID.randomUUID().toString()).build();
        try {
            TableResult result = client.query(configuration, job);
            if (result == null) throw LeadApiException.unavailable();
            if (insert) {
                Job receipt=client.getJob(job);
                if (receipt==null || !job.equals(receipt.getJobId()) || receipt.getStatus()==null
                        || receipt.getStatus().getState()!=JobStatus.State.DONE || receipt.getStatus().getError()!=null
                        || !(receipt.getConfiguration() instanceof QueryJobConfiguration committed)
                        || !configuration.getQuery().equals(committed.getQuery())
                        || !configuration.getNamedParameters().equals(committed.getNamedParameters())
                        || !(receipt.getStatistics() instanceof JobStatistics.QueryStatistics statistics)
                        || !Long.valueOf(1).equals(statistics.getNumDmlAffectedRows())) throw LeadApiException.unavailable();
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw LeadApiException.unavailable();
        } catch (LeadApiException exception) { throw exception; }
        catch (RuntimeException exception) { throw LeadApiException.unavailable(); }
    }

    private List<CrmLeadRecord> rows(TableResult result, UUID owner, int maximum, boolean uniqueIds) {
        var records = new ArrayList<CrmLeadRecord>();
        var ids=new java.util.HashSet<UUID>();
        try {
            for (FieldValueList row : result.iterateAll()) {
                if (records.size()>=maximum) throw LeadApiException.unavailable();
                var lead = new CrmLeadRecord(canonicalId(row.get("lead_id").getStringValue()),
                    row.get("customer_name").getStringValue(), row.get("phone").getStringValue(),
                    row.get("service").getStringValue(), LeadStage.valueOf(row.get("stage").getStringValue()),
                    row.get("source").getStringValue(), canonicalId(row.get("vendor_subject_id").getStringValue()),
                    canonicalId(row.get("created_by_subject_id").getStringValue()),
                    row.get("created_at").getTimestampInstant(), row.get("updated_at").getTimestampInstant());
                if (owner != null && !owner.equals(lead.vendorSubjectId())) throw LeadApiException.unavailable();
                if (uniqueIds && !ids.add(lead.leadId())) throw LeadApiException.unavailable();
                if (lead.updatedAt().isBefore(lead.createdAt())) throw LeadApiException.unavailable();
                records.add(lead);
            }
        } catch (BigQueryException | IllegalArgumentException | NullPointerException exception) {
            throw LeadApiException.unavailable();
        }
        return List.copyOf(records);
    }
    private static UUID canonicalId(String raw) {
        UUID value=UUID.fromString(raw);
        if (!value.toString().equals(raw)) throw new IllegalArgumentException("Invalid stored identifier.");
        return value;
    }

    private String table() { return "`" + properties.projectId() + "." + properties.dataset() + "." + properties.leadsTable() + "`"; }
    private static long micros(Instant time) { return Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000L), time.getNano() / 1000); }
}
