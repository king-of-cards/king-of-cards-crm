package com.kingofcards.identity.service;

import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.repository.IdentityUserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Account administration, deliberately separate from assignment eligibility. Read-only. */
@Service
@PreAuthorize("hasRole('CRM_ADMIN')")
public class VendorAccountDirectoryService {
    private final ObjectProvider<BigQuery> clients;
    private final IdentityBigQueryProperties properties;
    private final IdentityUserRepository users;

    public VendorAccountDirectoryService(ObjectProvider<BigQuery> clients,
            IdentityBigQueryProperties properties, IdentityUserRepository users) {
        this.clients = clients; this.properties = properties; this.users = users;
    }

    public VendorPageResponse list(int page, int size) {
        return listRole(page, size, "CRM_VENDOR");
    }
    public VendorPageResponse listAdministrators(int page, int size) {
        return listRole(page, size, "CRM_ADMIN");
    }
    private VendorPageResponse listRole(int page, int size, String accountRole) {
        if (page < 0 || page > 5000 || size < 1 || size > 100)
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                "Page must be between 0 and 5000; size must be between 1 and 100.");
        if (!properties.enabled()) throw new IdentityPersistenceException("IDENTITY_BIGQUERY_DISABLED");
        properties.validate();
        BigQuery client = clients.getIfAvailable();
        if (client == null) throw new IdentityPersistenceException("IDENTITY_BIGQUERY_DISABLED");
        String table = properties.table("identity_user");
        var role = QueryParameterValue.string(accountRole);
        try {
            long count = query(client, QueryJobConfiguration.newBuilder(
                "SELECT COUNT(*) AS total FROM " + table + " WHERE role = @role")
                .addNamedParameter("role", role)).iterateAll().iterator().next().get("total").getLongValue();
            var rows = query(client, QueryJobConfiguration.newBuilder(
                "SELECT id, name, enabled FROM " + table
                + " WHERE role = @role ORDER BY id ASC LIMIT @size OFFSET @offset")
                .addNamedParameter("role", role).addNamedParameter("size", QueryParameterValue.int64(size))
                .addNamedParameter("offset", QueryParameterValue.int64((long) page * size)));
            var items = new ArrayList<VendorResponse>();
            for (var row : rows.iterateAll()) items.add(new VendorResponse(
                UUID.fromString(row.get("id").getStringValue()),
                row.get("name").isNull() ? null : row.get("name").getStringValue(),
                row.get("enabled").getBooleanValue(), List.of(accountRole)));
            return new VendorPageResponse(items, page, size, count,
                Math.toIntExact((count + size - 1) / size));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IdentityPersistenceException("IDENTITY_VENDOR_DIRECTORY_UNAVAILABLE");
        } catch (RuntimeException exception) {
            throw new IdentityPersistenceException("IDENTITY_VENDOR_DIRECTORY_UNAVAILABLE");
        }
    }

    public VendorResponse get(UUID subjectId) {
        return getRole(subjectId, "CRM_VENDOR");
    }
    public VendorResponse getAdministrator(UUID subjectId) {
        return getRole(subjectId, "CRM_ADMIN");
    }
    private VendorResponse getRole(UUID subjectId, String accountRole) {
        var user = users.findById(subjectId).filter(candidate -> candidate.getRoles().size() == 1
            && candidate.getRoles().stream().allMatch(role -> accountRole.equals(role.getCode())))
            .orElseThrow(() -> new VendorDirectoryException(HttpStatus.NOT_FOUND,
                "CRM_VENDOR".equals(accountRole)?"VENDOR_NOT_FOUND":"ADMIN_NOT_FOUND", "Account with the requested role was not found."));
        return new VendorResponse(user.getId(), user.getDisplayName(), user.isEnabled(), List.of(accountRole));
    }

    private TableResult query(BigQuery client, QueryJobConfiguration.Builder builder) throws InterruptedException {
        return client.query(builder.setUseLegacySql(false).setMaximumBytesBilled(100_000_000L)
            .setJobTimeoutMs(20_000L).build(), JobId.newBuilder().setProject(properties.projectId())
            .setLocation(properties.location()).setJob("koc_vendor_directory_" + UUID.randomUUID().toString().replace("-", "")).build());
    }
}
