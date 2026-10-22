package com.jurislpo.juristrack.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.juristrack.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Two businesses in one database: inside one business the other's rows are invisible and cannot be written (row-level security). */
class TenantIsolationTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    UUID alphaBusinessId;
    UUID betaBusinessId;
    UUID alphaUserId;
    UUID betaUserId;

    @BeforeEach
    void createTwoBusinesses() {
        // system mode (no TenantContext): sees and writes every business
        alphaBusinessId = createBusiness("Alpha");
        betaBusinessId = createBusiness("Beta");
        alphaUserId = createUser(alphaBusinessId, "DEMO-1");
        betaUserId = createUser(betaBusinessId, "DEMO-1"); // same employee code in another business: allowed
    }

    private UUID createBusiness(String name) {
        UUID businessId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO businesses (id, name) VALUES (?, ?)", businessId, name + " " + businessId);
        return businessId;
    }

    private UUID createUser(UUID businessId, String employeeCode) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, business_id, employee_code, first_name, last_name, email) VALUES (?, ?, ?, 'Test', 'Person', ?)",
                userId, businessId, employeeCode, userId + "@tenancy.test");
        return userId;
    }

    @Test
    void aRowOfAnotherBusinessIsInvisible() {
        int betaUsersSeenFromAlpha = TenantContext.call(alphaBusinessId,
                () -> jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, betaUserId));
        int betaUsersSeenFromBeta = TenantContext.call(betaBusinessId,
                () -> jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, betaUserId));
        assertThat(betaUsersSeenFromAlpha).isZero();
        assertThat(betaUsersSeenFromBeta).isEqualTo(1);

        // even a query without any business filter returns only the current business's rows
        Integer businessesSeenFromAlpha = TenantContext.call(alphaBusinessId,
                () -> jdbcTemplate.queryForObject("SELECT count(DISTINCT business_id) FROM users", Integer.class));
        assertThat(businessesSeenFromAlpha).isEqualTo(1);
        assertThat(TenantContext.call(alphaBusinessId, () -> jdbcTemplate.queryForList("SELECT id FROM businesses", UUID.class)))
                .containsExactly(alphaBusinessId);
    }

    @Test
    void aRowCannotBeWrittenIntoAnotherBusiness() {
        assertThatThrownBy(() -> TenantContext.run(alphaBusinessId, () -> jdbcTemplate.update(
                "INSERT INTO users (business_id, first_name, last_name, email) VALUES (?, 'Sneaky', 'Person', ?)", betaBusinessId, UUID.randomUUID() + "@tenancy.test")))
                .rootCause().hasMessageContaining("row-level security");
        int updatedRows = TenantContext.call(alphaBusinessId,
                () -> jdbcTemplate.update("UPDATE users SET first_name = 'Changed' WHERE id = ?", betaUserId));
        assertThat(updatedRows).isZero();
    }

    @Test
    void insertsInsideABusinessAreTaggedWithIt() {
        UUID userId = UUID.randomUUID();
        TenantContext.run(alphaBusinessId, () -> jdbcTemplate.update(
                "INSERT INTO users (id, first_name, last_name, email, manager_id) VALUES (?, 'New', 'Report', ?, ?)", userId, userId + "@tenancy.test", alphaUserId));
        assertThat(jdbcTemplate.queryForObject("SELECT business_id FROM users WHERE id = ?", UUID.class, userId)).isEqualTo(alphaBusinessId);
        assertThat(TenantContext.current()).isNull(); // restored after the call
    }

    @Test
    void aManagerMustBelongToTheSameBusiness() {
        // the foreign key checks the manager across row-level security: beta's user cannot manage alpha's
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE users SET manager_id = ? WHERE id = ?", betaUserId, alphaUserId))
                .rootCause().hasMessageContaining("users_manager_id_fkey");
        UUID reportId = createUser(alphaBusinessId, null);
        jdbcTemplate.update("UPDATE users SET manager_id = ? WHERE id = ?", alphaUserId, reportId);
        assertThat(TenantContext.call(alphaBusinessId,
                () -> jdbcTemplate.queryForList("SELECT id FROM users WHERE manager_id = ?", UUID.class, alphaUserId))).containsExactly(reportId);
    }

    @Test
    void systemModeSeesEveryBusinessAndTheSuperAdminWhileABusinessSeesOnlyItsOwnUsers() {
        UUID superAdminId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, first_name, last_name, email, role) VALUES (?, 'Super', 'Admin', ?, 'SUPER_ADMIN')",
                superAdminId, "super." + superAdminId + "@tenancy.test");
        String usersQuery = "SELECT id FROM users WHERE id IN (?, ?, ?)";

        // system mode (sign-in, the super admin, commands): every row, the super admin and both businesses
        assertThat(jdbcTemplate.queryForList(usersQuery, UUID.class, superAdminId, alphaUserId, betaUserId))
                .containsExactlyInAnyOrder(superAdminId, alphaUserId, betaUserId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE id IN (?, ?)", Integer.class, alphaBusinessId, betaBusinessId))
                .isEqualTo(2);
        // inside alpha: only alpha's user; the super admin (no business) and beta's user are invisible
        assertThat(TenantContext.call(alphaBusinessId, () -> jdbcTemplate.queryForList(usersQuery, UUID.class, superAdminId, alphaUserId, betaUserId)))
                .containsExactly(alphaUserId);
        // and a business cannot create a super admin
        assertThatThrownBy(() -> TenantContext.run(alphaBusinessId, () -> jdbcTemplate.update(
                "INSERT INTO users (business_id, first_name, last_name, email, role) VALUES (NULL, 'Sneaky', 'Admin', 'sneaky@tenancy.test', 'SUPER_ADMIN')")))
                .rootCause().hasMessageContaining("row-level security");
    }
}
