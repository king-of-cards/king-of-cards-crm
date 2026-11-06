package com.kingofcards.identity;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;
import com.kingofcards.identity.repository.IdentityUserRepository;

/** In-memory surrogate only: never connects to the configured PostgreSQL datasource. */
class VendorMigrationAndRepositoryTest {
    @Test
    void additiveMigrationPreservesAccountsAndValidatesJpaAndEligibilityQuery() throws Exception {
        String url = "jdbc:h2:mem:vendor_" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        // H2 lacks PostgreSQL's TIMESTAMPTZ spelling. Alias only in this in-memory test.
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("2").load().migrate();
        UUID existingId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var insert = connection.prepareStatement("""
                    INSERT INTO identity_user (id, email, normalized_email, password_hash, enabled, created_at, updated_at)
                    VALUES (?, 'existing@example.test', 'existing@example.test', 'synthetic-hash', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)) {
            insert.setObject(1, existingId);
            insert.executeUpdate();
        }
        var flyway = Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var columns = connection.getMetaData().getColumns(null, null, "IDENTITY_USER", "DISPLAY_NAME")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(120);
            assertThat(columns.getInt("NULLABLE")).isEqualTo(java.sql.DatabaseMetaData.columnNullable);
        }
        var registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", "sa")
            .applySetting("hibernate.connection.password", "")
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .build();
        try (var factory = new MetadataSources(registry)
                .addAnnotatedClass(IdentityUser.class).addAnnotatedClass(IdentityRole.class)
                .addAnnotatedClass(RefreshTokenSession.class).buildMetadata().buildSessionFactory();
                var session = factory.openSession()) {
            var existing = session.find(IdentityUser.class, existingId);
            assertThat(existing.getDisplayName()).isNull();
            assertThat(existing.getEmail()).isEqualTo("existing@example.test");
            assertThat(existing.getPasswordHash()).isEqualTo("synthetic-hash");
            assertThat(existing.isEnabled()).isTrue();

            session.beginTransaction();
            var vendorRole = session.find(IdentityRole.class, "VENDOR");
            var adminRole = session.find(IdentityRole.class, "CRM_ADMIN");
            var first = user("one", true, "Vendor One", vendorRole);
            first.addRole(adminRole);
            var second = user("two", true, "Vendor Two", vendorRole);
            var disabled = user("disabled", false, "Disabled Vendor", vendorRole);
            var nameless = user("nameless", true, null, vendorRole);
            var staff = user("staff", true, "Staff", adminRole);
            var blank = user("blank", true, "Temporary", vendorRole);
            for (var user : List.of(first, second, disabled, nameless, staff, blank)) session.persist(user);
            session.flush();
            session.createNativeMutationQuery("UPDATE identity_user SET display_name = '   ' WHERE id = :id")
                .setParameter("id", blank.getId()).executeUpdate();
            session.clear();
            var repository = new JpaRepositoryFactory(session).getRepository(IdentityUserRepository.class);
            var ids = List.of(first.getId(), second.getId()).stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();
            var page = repository.findAssignableVendors(PageRequest.of(0, 1, Sort.by("id").ascending()));
            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.getContent()).extracting(IdentityUser::getId).containsExactly(ids.getFirst());
            assertThat(repository.findAssignableVendors(PageRequest.of(1, 1, Sort.by("id").ascending())).getContent())
                .extracting(IdentityUser::getId).containsExactly(ids.getLast());
            session.getTransaction().rollback();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private IdentityUser user(String suffix, boolean enabled, String name, IdentityRole role) {
        var user = new IdentityUser(suffix + "@example.test", suffix + "@example.test", "synthetic-hash", enabled);
        user.addRole(role);
        if (name != null) user.setDisplayName(name);
        return user;
    }
}
