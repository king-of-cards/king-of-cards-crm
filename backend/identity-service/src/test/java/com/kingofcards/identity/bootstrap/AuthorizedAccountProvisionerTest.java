package com.kingofcards.identity.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.kingofcards.identity.bigquery.BigQueryIdentityStore;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import com.kingofcards.identity.repository.IdentityUserRepository;

class AuthorizedAccountProvisionerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    private static final String HASH = "$2b$12$" + "a".repeat(53);
    @Test void preservesIdNormalizesEmailAndUsesSingleAtomicContractForBothRoles() {
        for (String role : new String[]{"CRM_ADMIN", "CRM_VENDOR"}) {
            var users=mock(IdentityUserRepository.class);
            var id=UUID.randomUUID();
            assertEquals(id,new AuthorizedAccountProvisioner(users,clock).provision(id,"  Account Name  "," Operator@Example.test ",HASH,role,false));
            var account=ArgumentCaptor.forClass(IdentityUser.class);
            verify(users).createUnique(account.capture()); verifyNoMoreInteractions(users);
            var saved=account.getValue();
            assertEquals(id,saved.getId()); assertEquals("Account Name",saved.getDisplayName());
            assertEquals("operator@example.test",saved.getEmail()); assertEquals(HASH,saved.getPasswordHash());
            assertFalse(saved.isEnabled()); assertEquals(clock.instant(),saved.getCreatedAt());
            assertEquals(saved.getCreatedAt(),saved.getUpdatedAt());
            assertEquals(role,saved.getRoles().iterator().next().getCode());
            assertFalse(saved.toString().contains(HASH));
        }
    }
    @Test void invalidAndLegacyRolesOrPlaintextFailBeforePersistence() {
        var users=mock(IdentityUserRepository.class); var provisioner=new AuthorizedAccountProvisioner(users,clock);
        for (String role : new String[]{null,"VENDOR","CRM_MANAGER","CRM_AGENT","ROOT"})
            assertThrows(IllegalArgumentException.class,()->provisioner.provision(UUID.randomUUID(),"Name","a@example.test",HASH,role,true));
        assertThrows(IllegalArgumentException.class,()->provisioner.provision(UUID.randomUUID(),"Name","a@example.test","plaintext-is-not-a-hash","CRM_ADMIN",true));
        assertThrows(IllegalArgumentException.class,()->provisioner.provision(UUID.randomUUID()," ","a@example.test",HASH,"CRM_ADMIN",true));
        verifyNoInteractions(users);
    }
    @Test void duplicateOrUnknownOutcomeIsNeverRetriedOrReportedAsSuccess() {
        for (String code : new String[]{"IDENTITY_DUPLICATE_EMAIL","IDENTITY_TRANSACTION_OUTCOME_UNKNOWN"}) {
            var users=mock(IdentityUserRepository.class);
            doThrow(new IdentityPersistenceException(code)).when(users).createUnique(any());
            assertThrows(IdentityPersistenceException.class,()->new AuthorizedAccountProvisioner(users,clock)
                .provision(UUID.randomUUID(),"Name","a@example.test",HASH,"CRM_ADMIN",true));
            verify(users,times(1)).createUnique(any()); verifyNoMoreInteractions(users);
        }
    }
    @Test void actualProductionStoreRemainsUnconditionallyGatedWithoutAdcOrSdkCalls() {
        var store=new BigQueryIdentityStore(()->{throw new AssertionError("No client may be acquired");},
            new IdentityBigQueryProperties(false,"king-of-cards-509610","shared_rds_data","asia-south1"));
        var failure=assertThrows(IdentityPersistenceException.class,()->new AuthorizedAccountProvisioner(store,clock)
            .provision(UUID.randomUUID(),"Name","a@example.test",HASH,"CRM_ADMIN",true));
        assertEquals("IDENTITY_MUTATIONS_UNVERIFIED",failure.code());
    }
}
