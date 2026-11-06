package com.kingofcards.identity.bigquery.transactions;

import java.util.*;
import java.time.Duration;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.cloud.bigquery.*;
import com.google.cloud.http.HttpTransportOptions;

/** Explicit read-only diagnostic. No query jobs, table rows, DDL, DML or IAM writes. */
public final class IsolatedBigQueryReadinessCheck {
    static final List<String> TABLE_PERMISSIONS = List.of("bigquery.tables.get", "bigquery.tables.getData", "bigquery.tables.updateData");
    private IsolatedBigQueryReadinessCheck() {}
    public static void main(String[] args) {
        if (!Arrays.equals(args, new String[] {"--read-only-metadata"}))
            throw new IllegalArgumentException("The explicit read-only metadata option is required.");
        String stage = "ADC";
        try {
            var credentials = GoogleCredentials.getApplicationDefault()
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
            var client = BigQueryOptions.newBuilder().setProjectId(IsolatedAuthTarget.PROJECT)
                .setLocation(IsolatedAuthTarget.LOCATION).setCredentials(credentials)
                .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(10_000).setReadTimeout(20_000).build())
                .setRetrySettings(BigQueryOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(1)
                    .setTotalTimeoutDuration(Duration.ofSeconds(30)).build()).build().getService();
            stage = "DATASET_TABLE_METADATA";
            for (String line : metadata(client)) System.out.println(line);
            // testIamPermissions is a read-only policy probe, NOT an authorization guarantee.
            stage = "PROJECT_IAM_PROBE";
            var factory = new NetHttpTransport().createRequestFactory(new HttpCredentialsAdapter(credentials));
            var request = factory.buildPostRequest(new GenericUrl("https://cloudresourcemanager.googleapis.com/v1/projects/"
                + IsolatedAuthTarget.PROJECT + ":testIamPermissions"), new JsonHttpContent(GsonFactory.getDefaultInstance(),
                    Map.of("permissions", List.of("bigquery.jobs.create", "bigquery.tables.create"))));
            request.setConnectTimeout(10_000).setReadTimeout(20_000).setLoggingEnabled(false).setCurlLoggingEnabled(false);
            request.setFollowRedirects(false);
            var response = request.execute();
            try {
                var result = GsonFactory.getDefaultInstance().fromString(response.parseAsString(), Map.class);
                System.out.println("Project jobs.create reported: "
                    + (result.get("permissions") instanceof List<?> permissions && permissions.contains("bigquery.jobs.create")));
                System.out.println("Project-level tables.create reported: "
                    + (result.get("permissions") instanceof List<?> permissions && permissions.contains("bigquery.tables.create")));
            } finally { response.disconnect(); }
        } catch (Exception exception) {
            // Do not surface SDK/HTTP exception contents, credential paths or tokens.
            int status = exception instanceof BigQueryException bq ? bq.getCode()
                : exception instanceof HttpResponseException http ? http.getStatusCode() : 0;
            String reason = "UNCLASSIFIED";
            if (exception instanceof HttpResponseException http && http.getContent() != null) {
                try { reason = safeReason(GsonFactory.getDefaultInstance().fromString(http.getContent(), Map.class)); }
                catch (Exception ignored) { /* Never print response contents. */ }
            }
            System.err.println("Read-only readiness check incomplete: stage=" + stage + ", HTTP=" + status
                + ", reason=" + reason + "; no changes attempted.");
            System.exit(2);
        }
    }
    static String safeReason(Object value) {
        Set<String> allowed = Set.of("SERVICE_DISABLED", "IAM_PERMISSION_DENIED", "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
            "BILLING_DISABLED", "ORG_RESTRICTION_VIOLATION");
        if (value instanceof Map<?, ?> map) {
            if (map.get("reason") instanceof String reason && allowed.contains(reason)) return reason;
            for (var nested : map.values()) { String found = safeReason(nested); if (!found.equals("UNCLASSIFIED")) return found; }
        } else if (value instanceof List<?> list) {
            for (var nested : list) { String found = safeReason(nested); if (!found.equals("UNCLASSIFIED")) return found; }
        }
        return "UNCLASSIFIED";
    }
    static List<String> metadata(BigQuery client) {
        var lines = new ArrayList<String>();
        var dataset = client.getDataset(DatasetId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET));
        if (dataset == null) return List.of("Dataset: MISSING_OR_NOT_VISIBLE");
        lines.add("Dataset: FOUND");
        lines.add("Dataset location matches asia-south1: " + IsolatedAuthTarget.LOCATION.equalsIgnoreCase(dataset.getLocation()));
        for (String name : List.of(IsolatedAuthTarget.USERS, IsolatedAuthTarget.SESSIONS)) {
            var id = TableId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET, name);
            var table = client.getTable(id);
            if (table == null) {
                lines.add(name + ": MISSING_OR_NOT_VISIBLE; effective table IAM pending provisioning");
            } else {
                lines.add(name + ": EXISTS; stop before DDL and inspect ownership/schema");
                var permissions = client.testIamPermissions(id, TABLE_PERMISSIONS);
                for (String permission : TABLE_PERMISSIONS)
                    lines.add(name + ": " + permission + " reported=" + permissions.contains(permission));
            }
        }
        return List.copyOf(lines);
    }
}
