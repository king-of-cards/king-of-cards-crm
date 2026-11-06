package com.kingofcards.crm.service;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.kingofcards.crm.config.BigQueryProperties;

/** Dataset metadata only: no table listing, data reads, queries or writes. */
public final class BigQueryMetadataVerificationService {
    private final BigQuery bigQuery;
    private final BigQueryProperties properties;

    public BigQueryMetadataVerificationService(BigQuery bigQuery, BigQueryProperties properties) {
        this.bigQuery = bigQuery;
        this.properties = properties;
    }

    public DatasetMetadata verifyDataset() {
        if (!properties.enabled() || !properties.metadataVerificationEnabled()) {
            throw new IllegalStateException("BigQuery dataset metadata verification is disabled.");
        }
        properties.validateForConnectivity();
        Dataset dataset;
        try {
            dataset = bigQuery.getDataset(DatasetId.of(properties.projectId(), properties.dataset()));
        } catch (BigQueryException exception) {
            // Do not expose provider messages, credential details or upstream response bodies.
            throw new IllegalStateException("BigQuery dataset metadata verification failed (HTTP "
                + exception.getCode() + ").");
        }
        if (dataset == null) {
            throw new IllegalStateException("Configured BigQuery dataset was not found; no resources were created.");
        }
        if (!properties.location().equalsIgnoreCase(dataset.getLocation())) {
            throw new IllegalStateException("BigQuery dataset location does not match crm.bigquery.location.");
        }
        return new DatasetMetadata(properties.projectId(), properties.dataset(), dataset.getLocation());
    }

    public record DatasetMetadata(String projectId, String dataset, String location) {}
}
