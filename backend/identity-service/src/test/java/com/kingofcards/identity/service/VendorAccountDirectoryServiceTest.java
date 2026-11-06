package com.kingofcards.identity.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.repository.IdentityUserRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class VendorAccountDirectoryServiceTest {
    BigQuery client = mock(BigQuery.class);
    @SuppressWarnings("unchecked") ObjectProvider<BigQuery> clients = mock(ObjectProvider.class);
    IdentityUserRepository users = mock(IdentityUserRepository.class);
    VendorAccountDirectoryService service;
    @BeforeEach void setup() {
        when(clients.getIfAvailable()).thenReturn(client);
        service = new VendorAccountDirectoryService(clients,
            new IdentityBigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1"),users);
    }

    @Test void managementListsBlockedAndUnnamedAccountsWithoutChangingAssignmentEligibility() throws Exception {
        UUID id = UUID.randomUUID();
        var total = mock(FieldValueList.class); var row = mock(FieldValueList.class);
        when(total.get("total")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"101"));
        when(row.get("id")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,id.toString()));
        when(row.get("name")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,null));
        when(row.get("enabled")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"false"));
        var countResult = mock(TableResult.class); var pageResult = mock(TableResult.class);
        when(countResult.iterateAll()).thenReturn(List.of(total)); when(pageResult.iterateAll()).thenReturn(List.of(row));
        when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenReturn(countResult,pageResult);
        var result = service.list(1,100);
        assertEquals(101,result.totalElements()); assertEquals(2,result.totalPages());
        assertFalse(result.items().getFirst().enabled()); assertNull(result.items().getFirst().displayName());
        assertEquals(id,result.items().getFirst().subjectId());
        var queries = ArgumentCaptor.forClass(QueryJobConfiguration.class);
        var jobs = ArgumentCaptor.forClass(JobId.class);
        verify(client,times(2)).query(queries.capture(),jobs.capture());
        for(var query:queries.getAllValues()) {
            assertTrue(query.getQuery().contains("`king-of-cards-509610.shared_rds_data.identity_user`"));
            assertTrue(query.getQuery().contains("role = @role"));
            assertFalse(query.getQuery().contains("enabled ="));
            assertEquals("CRM_VENDOR",query.getNamedParameters().get("role").getValue());
            assertEquals(100_000_000L,query.getMaximumBytesBilled());
        }
        var page = queries.getAllValues().get(1);
        assertTrue(page.getQuery().contains("ORDER BY id ASC LIMIT @size OFFSET @offset"));
        assertEquals("100",page.getNamedParameters().get("offset").getValue());
        assertTrue(jobs.getAllValues().stream().allMatch(job -> "asia-south1".equals(job.getLocation())));
        verifyNoInteractions(users);
    }

    @Test void detailsIncludeBlockedVendorButRejectOtherRolesAndUnknownIds() {
        UUID id = UUID.randomUUID();
        var vendor = IdentityUser.restore(id,"synthetic@example.test",null,"synthetic-hash","CRM_VENDOR",false,
            java.time.Instant.now(),java.time.Instant.now());
        when(users.findById(id)).thenReturn(Optional.of(vendor));
        assertFalse(service.get(id).enabled()); assertNull(service.get(id).displayName());
        var admin = IdentityUser.restore(id,"synthetic@example.test","Admin","synthetic-hash","CRM_ADMIN",true,
            vendor.getCreatedAt(),vendor.getUpdatedAt());
        when(users.findById(id)).thenReturn(Optional.of(admin));
        assertThrows(VendorDirectoryException.class,()->service.get(id));
        when(users.findById(id)).thenReturn(Optional.empty());
        assertThrows(VendorDirectoryException.class,()->service.get(id));
    }

    @Test void paginationRejectsUnboundedQueriesBeforeAccess() {
        for(int[] pair:List.of(new int[]{-1,20},new int[]{5001,20},new int[]{0,0},new int[]{0,101}))
            assertThrows(VendorDirectoryException.class,()->service.list(pair[0],pair[1]));
        verifyNoInteractions(client,clients,users);
    }
    @Test void disabledReadsFailClosed() {
        var disabled = new VendorAccountDirectoryService(clients,
            new IdentityBigQueryProperties(false,"king-of-cards-509610","shared_rds_data","asia-south1"),users);
        assertEquals("IDENTITY_BIGQUERY_DISABLED",assertThrows(IdentityPersistenceException.class,()->disabled.list(0,20)).code());
        verifyNoInteractions(client);
    }
    @Test void sdkFailuresAreSanitized() throws Exception {
        when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenThrow(new BigQueryException(403,"sensitive SDK message"));
        var exception=assertThrows(IdentityPersistenceException.class,()->service.list(0,20));
        assertEquals("IDENTITY_VENDOR_DIRECTORY_UNAVAILABLE",exception.code());
        assertFalse(exception.getMessage().contains("sensitive"));
    }
}
