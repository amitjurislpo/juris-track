package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.tenancy.TenantContext;
import com.jurislpo.juristrack.tenancy.TenantDataSource;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The audit log is append-only in the database: UPDATE, DELETE and TRUNCATE are refused for the schema owner (trigger) and the role
 * business requests run as (no rights, and the trigger); a user or business that appears in the log cannot be deleted. Row-level
 * security: inside a business only its rows are visible, platform rows (no business) only in system mode.
 */
class AuditLogAppendOnlyTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    TestData testData;
    UUID businessId;
    UUID otherBusinessId;
    UUID userId;
    UUID entryId;
    UUID otherEntryId;
    UUID platformEntryId;

    @BeforeEach
    void setUp() {
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Append Only Co " + UUID.randomUUID());
        otherBusinessId = testData.createBusiness("Other Append Only Co " + UUID.randomUUID());
        userId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("append.only"), null);
        entryId = insertEntry(businessId, userId);
        otherEntryId = insertEntry(otherBusinessId, null);
        platformEntryId = insertEntry(null, null);
    }

    private UUID insertEntry(UUID entryBusinessId, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO audit_logs (id, business_id, actor_id, subject_id, action, entity_type, category, outcome, source) "
                + "VALUES (?, ?, ?, ?, 'test.entry', 'test', 'audit', 'SUCCESS', 'SYSTEM')", id, entryBusinessId, actorId, actorId);
        return id;
    }

    @Test
    void theOwnerOfTheSchemaCannotUpdateDeleteOrTruncate() {
        assertRefused(() -> jdbcTemplate.update("UPDATE audit_logs SET action = 'tampered' WHERE id = ?", entryId), "append-only: UPDATE");
        assertRefused(() -> jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", entryId), "append-only: DELETE");
        assertRefused(() -> jdbcTemplate.execute("TRUNCATE audit_logs"), "append-only: TRUNCATE");
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM audit_logs WHERE id = ?", String.class, entryId)).isEqualTo("test.entry");
    }

    @Test
    void theRoleOfBusinessRequestsCannotUpdateDeleteOrTruncateEither() {
        for (String sql : new String[] {"UPDATE audit_logs SET action = 'tampered'", "DELETE FROM audit_logs", "TRUNCATE audit_logs"}) {
            assertRefused(() -> runAsBusinessRole(sql), "permission denied");
        }
        // inserting stays allowed (the application writes its entries this way)
        runAsBusinessRole("INSERT INTO audit_logs (business_id, action, entity_type, category, outcome, source) VALUES ('" + businessId
                + "', 'test.inserted', 'test', 'audit', 'SUCCESS', 'SYSTEM')");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'test.inserted'", Integer.class, businessId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM audit_logs WHERE id = ?", String.class, entryId)).isEqualTo("test.entry");
    }

    /** The database refused the statement with a message containing {@code expectedText}. */
    private static void assertRefused(ThrowingCallable statement, String expectedText) {
        assertThatThrownBy(statement).isInstanceOfSatisfying(DataAccessException.class,
                e -> assertThat(e.getMostSpecificCause().getMessage()).contains(expectedText));
    }

    /** {@code sql} as the business role inside {@link #businessId} (as TenantDataSource sets up a business request). */
    private void runAsBusinessRole(String sql) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + TenantDataSource.TENANT_ROLE + "; SELECT set_config('app.business_id', '" + businessId + "', false)");
                statement.execute(sql);
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("RESET ROLE; SELECT set_config('app.business_id', '', false)");
                }
            }
            return null;
        });
    }

    @Test
    void aUserOrBusinessInTheLogCannotBeDeleted() {
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM businesses WHERE id = ?", otherBusinessId)).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT actor_id FROM audit_logs WHERE id = ?", UUID.class, entryId)).isEqualTo(userId);
    }

    @Test
    void insideABusinessOnlyItsEntriesAreVisibleAndPlatformEntriesOnlyInSystemMode() {
        String visible = "SELECT count(*) FROM audit_logs WHERE id IN (?, ?, ?)";
        assertThat(TenantContext.call(businessId, () -> jdbcTemplate.queryForObject(visible, Integer.class, entryId, otherEntryId, platformEntryId)))
                .isEqualTo(1);
        assertThat(TenantContext.call(otherBusinessId, () -> jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE id = ?", Integer.class,
                entryId))).isZero();
        assertThat(jdbcTemplate.queryForObject(visible, Integer.class, entryId, otherEntryId, platformEntryId)).isEqualTo(3);
        // a business request cannot write into another business's log either
        assertThatThrownBy(() -> TenantContext.run(businessId, () -> insertEntry(otherBusinessId, null))).isInstanceOf(DataAccessException.class);
    }
}
