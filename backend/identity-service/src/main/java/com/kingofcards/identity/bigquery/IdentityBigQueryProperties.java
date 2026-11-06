package com.kingofcards.identity.bigquery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("identity.bigquery")
public record IdentityBigQueryProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("king-of-cards-509610") String projectId,
        @DefaultValue("shared_rds_data") String dataset,
        @DefaultValue("asia-south1") String location) {
    public void validate() {
        if (!"king-of-cards-509610".equals(projectId) || !"shared_rds_data".equals(dataset)
                || !"asia-south1".equalsIgnoreCase(location))
            throw new IllegalArgumentException("Identity BigQuery target must match the approved project, dataset and location.");
    }
    public String table(String name) {
        validate();
        if (!name.equals("identity_user") && !name.equals("identity_refresh_session"))
            throw new IllegalArgumentException("Identity table is not allowlisted.");
        return "`" + projectId + "." + dataset + "." + name + "`";
    }
}
