package com.kingofcards.identity.bigquery.production;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import com.kingofcards.identity.bigquery.BigQueryIdentityStore;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductionIdentityStoreTest {
    ProductionAuthAdapter transactions;
    ProductionIdentityStore store;
    IdentityUser user=IdentityUser.restore(UUID.randomUUID(),"admin@example.org","Admin",ProductionFixture.BCRYPT,
        "CRM_ADMIN",true,ProductionFixture.NOW,ProductionFixture.NOW);
    @BeforeEach void setup(){
        transactions=mock(ProductionAuthAdapter.class);
        store=new ProductionIdentityStore(mock(BigQueryIdentityStore.class),transactions,Clock.fixed(ProductionFixture.NOW,ZoneOffset.UTC));
    }
    @Test void bootstrapReportsSuccessOnlyAfterConfirmedCreation(){
        when(transactions.submit(any())).thenThrow(new IdentityPersistenceException("IDENTITY_COMMIT_OUTCOME_UNKNOWN"));
        assertThrows(IdentityPersistenceException.class,()->store.createFirstAdministrator(user));
        verify(transactions,times(1)).submit(any());
    }
    @Test void existingAdminAndDuplicateEmailAreRejectedWithoutRetry(){
        when(transactions.submit(any())).thenReturn(new ProductionAuthResult(ProductionAuthResult.Status.BOOTSTRAP_NOT_EMPTY,null));
        assertThrows(IllegalStateException.class,()->store.createFirstAdministrator(user));
        when(transactions.submit(any())).thenReturn(new ProductionAuthResult(ProductionAuthResult.Status.DUPLICATE_EMAIL,null));
        assertEquals("DUPLICATE_EMAIL",assertThrows(VendorDirectoryException.class,()->store.createUnique(user)).code());
        verify(transactions,times(2)).submit(any());
    }
    @Test void loginSessionFailureCannotBeReportedAsSuccess(){
        when(transactions.submit(any())).thenReturn(new ProductionAuthResult(ProductionAuthResult.Status.INVALID,null));
        var session=new RefreshTokenSession(user.getId(),UUID.randomUUID(),ProductionFixture.HASH,ProductionFixture.CSRF,
            ProductionFixture.NOW,ProductionFixture.NOW.plusSeconds(60));
        assertThrows(InvalidRefreshTokenException.class,()->store.createForAuthenticatedUser(user,session));
    }
    @Test void committedReplayAndCsrfDenialsPreserveServiceContracts(){
        when(transactions.submit(any())).thenReturn(new ProductionAuthResult(ProductionAuthResult.Status.REUSED,null));
        assertEquals(com.kingofcards.identity.repository.RefreshTokenSessionRepository.Status.REUSED,store.rotate(
            ProductionFixture.HASH,ProductionFixture.CSRF,UUID.randomUUID(),ProductionFixture.NEXT,ProductionFixture.HASH,
            ProductionFixture.NOW,ProductionFixture.NOW.plusSeconds(60)).status());
        when(transactions.submit(any())).thenReturn(new ProductionAuthResult(ProductionAuthResult.Status.CSRF_REJECTED,null));
        assertThrows(CsrfValidationException.class,()->store.revoke(ProductionFixture.HASH,ProductionFixture.CSRF,ProductionFixture.NOW));
    }
    @Test void inactiveInvitationWorkflowCannotReachTransactions(){
        assertThrows(IdentityPersistenceException.class,()->store.createVendorInvitation(user,null));
        assertThrows(IdentityPersistenceException.class,()->store.completeVendorPasswordSetup("unused","unused","unused"));
        verifyNoInteractions(transactions);
    }
}
