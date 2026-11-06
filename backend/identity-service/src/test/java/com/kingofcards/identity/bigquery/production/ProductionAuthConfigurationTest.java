package com.kingofcards.identity.bigquery.production;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.*;
import com.kingofcards.identity.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionAuthConfigurationTest {
    private ApplicationContextRunner context(BigQuery client){
        return new ApplicationContextRunner().withUserConfiguration(ProductionAuthConfiguration.class)
            .withBean(BigQuery.class,()->client)
            .withBean(BigQueryIdentityStore.class,()->mock(BigQueryIdentityStore.class))
            .withBean(IdentityBigQueryProperties.class,()->new IdentityBigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1"));
    }
    @Test void environmentFlagsAloneCannotEnableProductionMutations(){
        var client=mock(BigQuery.class);
        context(client).withPropertyValues("identity.bigquery.enabled=true","identity.bigquery.production-mutations-enabled=true")
            .run(ctx->{assertNotNull(ctx.getStartupFailure());verifyNoInteractions(client);});
    }
    @Test void verifiedCleanStoreWiresOnePrimaryRepositoryForUsersAndSessions()throws Exception{
        var client=mock(BigQuery.class);var row=mock(FieldValueList.class);var result=mock(TableResult.class);
        when(row.get("synthetic_users")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"0"));
        when(row.get("orphan_sessions")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"0"));
        when(result.iterateAll()).thenReturn(List.of(row));when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenReturn(result);
        context(client).withPropertyValues("identity.bigquery.enabled=true","identity.bigquery.production-mutations-enabled=true",
            "identity.bigquery.verification-evidence=../docs/identity-production-verification-evidence.json")
            .run(ctx->{
                assertNull(ctx.getStartupFailure());
                assertInstanceOf(ProductionIdentityStore.class,ctx.getBean(IdentityUserRepository.class));
                assertSame(ctx.getBean(IdentityUserRepository.class),ctx.getBean(RefreshTokenSessionRepository.class));
                verify(client,never()).create(any(JobInfo.class));
            });
    }
}
