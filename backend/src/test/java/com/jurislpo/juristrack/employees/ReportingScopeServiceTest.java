package com.jurislpo.juristrack.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.service.ReportingScopeService;
import com.jurislpo.juristrack.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Whom a manager sees, from the database: depth 1 = direct reports, depth 2 = also their reports; never the manager, nobody above or
 * beside them, nobody of another business; deactivated reports stay in the line.
 *
 * <p>owner &lt;- director &lt;- (lead A &lt;- worker A1, worker A2 (deactivated)), (lead B &lt;- worker B1 &lt;- intern B1a); peer &lt;- owner.
 */
class ReportingScopeServiceTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReportingScopeService reportingScopeService;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID directorId;
    UUID leadAId;
    UUID leadBId;
    UUID workerA1Id;
    UUID workerA2Id;
    UUID workerB1Id;
    UUID internId;
    UUID peerId;

    @BeforeEach
    void setUp() {
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Scope Co " + UUID.randomUUID());
        ownerId = testData.createUser(businessId, "ADMIN", TestData.uniqueEmail("scope.owner"), null);
        directorId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("scope.director"), ownerId);
        peerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("scope.peer"), ownerId);
        leadAId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("scope.lead.a"), directorId);
        leadBId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("scope.lead.b"), directorId);
        workerA1Id = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("scope.a1"), leadAId);
        workerA2Id = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("scope.a2"), leadAId);
        testData.deactivate(workerA2Id);
        workerB1Id = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("scope.b1"), leadBId);
        internId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("scope.intern"), workerB1Id);
        // another business's user can never appear, even with a manager id pointing into this tree it cannot have (same-business FK)
        testData.createUser(testData.createBusiness("Other Scope Co " + UUID.randomUUID()), "EMPLOYEE", TestData.uniqueEmail("scope.other"), null);
    }

    @Test
    void depthOneIsTheDirectReports() {
        assertThat(reportingScopeService.visibleEmployeeIds(businessId, directorId, 1)).containsExactlyInAnyOrder(leadAId, leadBId);
        assertThat(reportingScopeService.visibleEmployeeIds(businessId, leadAId, 1)).containsExactlyInAnyOrder(workerA1Id, workerA2Id);
        assertThat(reportingScopeService.visibleEmployeeIds(businessId, workerA1Id, 1)).isEmpty();
    }

    @Test
    void depthTwoAddsOneLevelBelow() {
        assertThat(reportingScopeService.visibleEmployeeIds(businessId, directorId, 2))
                .containsExactlyInAnyOrder(leadAId, leadBId, workerA1Id, workerA2Id, workerB1Id)
                .doesNotContain(internId, directorId, ownerId, peerId);
        assertThat(reportingScopeService.visibleEmployeeIds(businessId, ownerId, 2)).containsExactlyInAnyOrder(directorId, peerId, leadAId, leadBId);
    }

    @Test
    void insideTheBusinessTheSameAnswerComesBack() {
        // run as a business request would (row-level security on)
        assertThat(TenantContext.call(businessId, () -> reportingScopeService.visibleEmployeeIds(businessId, leadBId, 2)))
                .containsExactlyInAnyOrder(workerB1Id, internId);
    }

    @Test
    void anotherBusinessOrAnUnknownManagerSeesNobody() {
        UUID otherBusinessId = testData.createBusiness("Empty Scope Co " + UUID.randomUUID());
        assertThat(reportingScopeService.visibleEmployeeIds(otherBusinessId, directorId, 2)).isEmpty();
        assertThat(TenantContext.call(otherBusinessId, () -> reportingScopeService.visibleEmployeeIds(businessId, directorId, 2))).isEmpty();
        assertThat(reportingScopeService.visibleEmployeeIds(businessId, UUID.randomUUID(), 1)).isEmpty();
    }

    @Test
    void onlyDepthOneOrTwo() {
        assertThatThrownBy(() -> reportingScopeService.visibleEmployeeIds(businessId, directorId, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reportingScopeService.visibleEmployeeIds(businessId, directorId, 3)).isInstanceOf(ApiException.class);
    }
}
