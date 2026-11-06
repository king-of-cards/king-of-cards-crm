package com.kingofcards.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityUserRepository;

class VendorDirectoryServiceTest {
    private IdentityUserRepository users;
    private VendorDirectoryService service;

    @BeforeEach
    void setup() {
        users = mock(IdentityUserRepository.class);
        service = new VendorDirectoryService(users);
    }

    private IdentityUser vendor(boolean enabled) {
        var user = new IdentityUser("synthetic@example.test", "synthetic@example.test", "synthetic-hash", enabled);
        user.addRole(new IdentityRole("CRM_VENDOR", "Vendor"));
        return user;
    }

    @Test
    void paginatesWithFixedIdOrderAndSafeResponse() {
        var vendor = vendor(true);
        vendor.setDisplayName("Vendor One");
        when(users.findAssignableVendors(any())).thenAnswer(call ->
            new PageImpl<>(List.of(vendor), call.getArgument(0), 41));
        var result = service.list(1, 20);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(users).findAssignableVendors(page.capture());
        assertThat(page.getValue().getPageNumber()).isEqualTo(1);
        assertThat(page.getValue().getPageSize()).isEqualTo(20);
        assertThat(page.getValue().getSort().toString()).isEqualTo("id: ASC");
        assertThat(result.totalElements()).isEqualTo(41);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.items().getFirst().subjectId()).isEqualTo(vendor.getId());
        assertThat(result.items().getFirst().roles()).containsExactly("CRM_VENDOR");
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void rejectsUnboundedOrInvalidPagesBeforeRepository(int page, int size) {
        assertThatThrownBy(() -> service.list(page, size)).hasMessageContaining("size must be between");
        verifyNoInteractions(users);
    }

    @Test
    void allowsMaximumPageSize() {
        when(users.findAssignableVendors(any())).thenAnswer(call ->
            new PageImpl<>(List.of(), call.getArgument(0), 0));
        assertThat(service.list(0, 100).size()).isEqualTo(100);
    }

    @Test
    void missingNameBlocksEligibilityUntilExplicitlyProvisioned() {
        var vendor = vendor(true);
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        assertThat(vendor.getDisplayName()).isNull();
        assertThatThrownBy(() -> service.get(vendor.getId())).hasMessageContaining("explicitly provisioned");
        when(users.save(vendor)).thenReturn(vendor);
        var result = service.provisionDisplayName(vendor.getId(), "  Vendor One  ");
        assertThat(result.displayName()).isEqualTo("Vendor One");
        assertThat(result.enabled()).isTrue();
        assertThat(service.get(vendor.getId()).displayName()).isEqualTo("Vendor One");
        assertThat(vendor.getEmail()).isEqualTo("synthetic@example.test");
        assertThat(vendor.getPasswordHash()).isEqualTo("synthetic-hash");
        assertThat(vendor.getRoles()).extracting(IdentityRole::getCode).containsExactly("CRM_VENDOR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "non-vendor", "unknown"})
    void ineligibleAccountsCannotBeRetrievedOrProvisioned(String kind) {
        IdentityUser user = "non-vendor".equals(kind)
            ? new IdentityUser("staff@example.test", "staff@example.test", "synthetic-hash", true)
            : vendor(!"disabled".equals(kind));
        when(users.findById(user.getId())).thenReturn("unknown".equals(kind) ? Optional.empty() : Optional.of(user));
        assertThatThrownBy(() -> service.get(user.getId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.provisionDisplayName(user.getId(), "Vendor One")).hasMessageContaining("not found");
        verify(users, never()).save(any());
        assertThat(user.getDisplayName()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void invalidNameNeverChangesOrSavesUser(String name) {
        var vendor = vendor(true);
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        assertThatThrownBy(() -> service.provisionDisplayName(vendor.getId(), name)).hasMessageContaining("1 to 120");
        verify(users, never()).save(any());
        assertThat(vendor.getDisplayName()).isNull();
    }

    @Test
    void validatesLengthAfterTrimming() {
        var vendor = vendor(true);
        when(users.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        when(users.save(vendor)).thenReturn(vendor);
        assertThat(service.provisionDisplayName(vendor.getId(), " " + "A".repeat(120) + " ").displayName()).hasSize(120);
        assertThatThrownBy(() -> service.provisionDisplayName(vendor.getId(), "A".repeat(121))).hasMessageContaining("1 to 120");
        verify(users, times(1)).save(vendor);
    }

    @Test
    void nullableNameRemainsValidForExistingAccounts() {
        var user = new IdentityUser("staff@example.test", "staff@example.test", "synthetic-hash", true);
        assertThat(user.getDisplayName()).isNull();
        assertThat(user.getId()).isInstanceOf(UUID.class);
    }
}
