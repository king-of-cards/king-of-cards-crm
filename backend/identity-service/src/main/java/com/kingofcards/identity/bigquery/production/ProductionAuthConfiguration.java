package com.kingofcards.identity.bigquery.production;

import java.nio.file.Path;
import java.time.Clock;
import com.google.cloud.bigquery.BigQuery;
import com.kingofcards.identity.bigquery.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="identity.bigquery",name={"enabled","production-mutations-enabled"},havingValue="true")
public class ProductionAuthConfiguration {
    @Bean @Primary
    ProductionIdentityStore productionIdentityStore(BigQueryIdentityStore reads,BigQuery client,
            IdentityBigQueryProperties properties,@Value("${identity.bigquery.verification-evidence:}") String evidence) {
        if(evidence.isBlank())throw new IllegalStateException("Reviewed production authentication evidence must be supplied explicitly.");
        ProductionActivationGate.verifyEvidence(Path.of(evidence));
        ProductionActivationGate.verifyNoSyntheticOrOrphanRecords(client,properties);
        return new ProductionIdentityStore(reads,new ProductionAuthAdapter(client),Clock.systemUTC());
    }
}
