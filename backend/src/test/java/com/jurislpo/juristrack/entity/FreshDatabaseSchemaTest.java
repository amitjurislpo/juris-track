package com.jurislpo.juristrack.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.PostgresContainer;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Empty database: Flyway applies V1__baseline.sql, V2, V3, V11 and V12, then Hibernate validates every entity against it
 * (ddl-auto=validate; the context would not start otherwise). Rows of every entity round-trip through JPA with native
 * enums, jsonb, text[] and the database defaults, and the CHECK constraints guard the "one open X" rules.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class FreshDatabaseSchemaTest {

    static final PostgreSQLContainer<?> POSTGRES = PostgresContainer.start();

    @DynamicPropertySource
    static void registerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopDatabase() { POSTGRES.stop(); }

    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedTheBaselineWithRowLevelSecurity() {
        assertThat(jdbcTemplate.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "11", "12", "20");
        assertThat(jdbcTemplate.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'businesses' AND column_name = 'is_suspended'",
                String.class)).isEqualTo("false");
        assertThat(jdbcTemplate.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'is_login_enabled'",
                String.class)).isEqualTo("true");
        assertThat(jdbcTemplate.queryForList("SELECT typname FROM pg_type WHERE typname IN ('user_role', 'workday_status') ORDER BY typname", String.class))
                .containsExactly("user_role", "workday_status");
        assertThat(jdbcTemplate.queryForList("SELECT tablename FROM pg_policies WHERE policyname = 'tenant_isolation' ORDER BY tablename", String.class))
                .containsExactly("audit_logs", "break_sessions", "businesses", "employee_schedule_entries", "employee_shift_assignments", "shifts", "users",
                        "work_sessions", "workdays");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND column_name LIKE '%password%'", Integer.class)).isZero();
    }

    @Test
    void everyEntityRoundTripsWithDefaults() throws Exception {
        Business business = new Business();
        business.setName("Schema test");
        entityManager.persist(business);

        User manager = user(business, "manager@schema.test", Role.MANAGER);
        User employee = user(business, "employee@schema.test", Role.EMPLOYEE);
        employee.setJurisoneEmployeeId(UUID.randomUUID());
        employee.setManagerId(manager.getId());

        Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Workday workday = new Workday();
        workday.setBusinessId(business.getId());
        workday.setEmployeeId(employee.getId());
        workday.setManagerId(manager.getId());
        workday.setWorkDate(LocalDate.of(2026, 10, 22));
        workday.setTimeZone("Asia/Kolkata");
        workday.setStartedAt(startedAt);
        workday.setActiveEmployeeId(employee.getId());
        entityManager.persist(workday);

        WorkSession workSession = new WorkSession();
        workSession.setBusinessId(business.getId());
        workSession.setEmployeeId(employee.getId());
        workSession.setWorkdayId(workday.getId());
        workSession.setStartedAt(startedAt);
        workSession.setEndedAt(startedAt.plus(1, ChronoUnit.HOURS));
        entityManager.persist(workSession);

        BreakSession breakSession = new BreakSession();
        breakSession.setBusinessId(business.getId());
        breakSession.setEmployeeId(employee.getId());
        breakSession.setWorkdayId(workday.getId());
        breakSession.setStartedAt(startedAt.plus(1, ChronoUnit.HOURS));
        breakSession.setOpenWorkdayId(workday.getId());
        entityManager.persist(breakSession);

        AuditLog auditLog = new AuditLog();
        auditLog.setBusinessId(business.getId());
        auditLog.setActorId(manager.getId());
        auditLog.setSubjectId(employee.getId());
        auditLog.setAction("user.manager");
        auditLog.setCategory("people");
        auditLog.setEntityType("User");
        auditLog.setEntityId(employee.getId().toString());
        auditLog.setAfter(new ObjectMapper().readTree("{\"managerId\":\"" + manager.getId() + "\",\"count\":1}"));
        auditLog.setReason("New joiner");
        entityManager.persist(auditLog);

        Session session = new Session();
        session.setId("session-hash");
        session.setUserId(employee.getId());
        session.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        entityManager.persist(session);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setTokenHash("refresh-hash");
        refreshToken.setSessionId(session.getId());
        refreshToken.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        entityManager.persist(refreshToken);

        LoginOtp loginOtp = new LoginOtp();
        loginOtp.setEmail(employee.getEmail());
        loginOtp.setCodeHash("code-hash");
        loginOtp.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        entityManager.persist(loginOtp);

        entityManager.flush();
        entityManager.clear();

        Business storedBusiness = entityManager.find(Business.class, business.getId());
        assertThat(storedBusiness.getTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(storedBusiness.getStaleWorkdayHours()).isEqualTo(14);
        assertThat(storedBusiness.getDisplayTimeZones()).containsExactly("Asia/Kolkata", "America/New_York");
        User storedEmployee = entityManager.find(User.class, employee.getId());
        assertThat(storedEmployee.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(storedEmployee.isActive()).isTrue();
        assertThat(storedEmployee.getJurisoneEmployeeId()).isEqualTo(employee.getJurisoneEmployeeId());
        Workday storedWorkday = entityManager.find(Workday.class, workday.getId());
        assertThat(storedWorkday.getStatus()).isEqualTo(WorkdayStatus.WORKING);
        assertThat(storedWorkday.getStartedAt()).isEqualTo(startedAt);
        assertThat(storedWorkday.getWorkDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(entityManager.find(AuditLog.class, auditLog.getId()).getAfter().get("count").asInt()).isEqualTo(1);
        assertThat(entityManager.find(LoginOtp.class, loginOtp.getId()).getAttempts()).isZero();
        assertThat(entityManager.find(RefreshToken.class, "refresh-hash").getSessionId()).isEqualTo("session-hash");
        assertThat(entityManager.find(Session.class, "session-hash").getLastSeenAt()).isNotNull();
        assertThat(storedEmployee.getManagerId()).isEqualTo(manager.getId());
        assertThat(storedWorkday.getManagerId()).isEqualTo(manager.getId());
        assertThat(entityManager.find(WorkSession.class, workSession.getId()).getOpenWorkdayId()).isNull();
        assertThat(entityManager.find(BreakSession.class, breakSession.getId()).getOpenWorkdayId()).isEqualTo(workday.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM workdays WHERE id = ?", String.class, workday.getId())).isEqualTo("WORKING");
        assertThat(jdbcTemplate.queryForObject("SELECT \"after\"->>'count' FROM audit_logs WHERE id = ?", String.class, auditLog.getId())).isEqualTo("1");
        assertThat(jdbcTemplate.queryForMap("SELECT outcome, source FROM audit_logs WHERE id = ?", auditLog.getId()))
                .containsEntry("outcome", "SUCCESS").containsEntry("source", "SYSTEM");
    }

    @Test
    void checkConstraintsGuardTheOpenAndClosedStates() {
        Business business = new Business();
        business.setName("Constraint test");
        entityManager.persist(business);
        User employee = user(business, "constraints@schema.test", Role.EMPLOYEE);
        entityManager.flush();
        UUID businessId = business.getId();
        UUID employeeId = employee.getId();

        // a completed workday must have ended and released its active key
        assertRejected("INSERT INTO workdays (business_id, employee_id, work_date, time_zone, started_at, status, active_employee_id) "
                + "VALUES (?, ?, DATE '2026-10-01', 'Asia/Kolkata', now(), 'COMPLETED', ?)", businessId, employeeId, employeeId);
        // an open workday's active key is its employee
        assertRejected("INSERT INTO workdays (business_id, employee_id, work_date, time_zone, started_at, active_employee_id) "
                + "VALUES (?, ?, DATE '2026-10-02', 'Asia/Kolkata', now(), ?)", businessId, employeeId, UUID.randomUUID());
        // no negative totals, no end before start
        assertRejected("INSERT INTO workdays (business_id, employee_id, work_date, time_zone, started_at, ended_at, status, productive_seconds) "
                + "VALUES (?, ?, DATE '2026-10-03', 'Asia/Kolkata', now(), now(), 'COMPLETED', -1)", businessId, employeeId);
        assertRejected("INSERT INTO workdays (business_id, employee_id, work_date, time_zone, started_at, ended_at, status) "
                + "VALUES (?, ?, DATE '2026-10-04', 'Asia/Kolkata', now(), now() - interval '1 hour', 'COMPLETED')", businessId, employeeId);
        // stale hours stay within 1..48
        assertRejected("INSERT INTO businesses (name, stale_workday_hours) VALUES ('Too long', 49)");
        // one open workday per employee
        UUID firstWorkday = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workdays (id, business_id, employee_id, work_date, time_zone, started_at, active_employee_id) "
                + "VALUES (?, ?, ?, DATE '2026-10-05', 'Asia/Kolkata', now(), ?)", firstWorkday, businessId, employeeId, employeeId);
        assertRejected("INSERT INTO workdays (business_id, employee_id, work_date, time_zone, started_at, active_employee_id) "
                + "VALUES (?, ?, DATE '2026-10-06', 'Asia/Kolkata', now(), ?)", businessId, employeeId, employeeId);
        // an open interval's key is its workday; a closed one has none
        assertRejected("INSERT INTO work_sessions (business_id, employee_id, workday_id, started_at) VALUES (?, ?, ?, now())", businessId, employeeId, firstWorkday);
        assertRejected("INSERT INTO break_sessions (business_id, employee_id, workday_id, started_at, ended_at, open_workday_id) "
                + "VALUES (?, ?, ?, now(), now(), ?)", businessId, employeeId, firstWorkday, firstWorkday);
    }

    private void assertRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> {
            jdbcTemplate.execute("SAVEPOINT before_rejected_insert");
            try {
                jdbcTemplate.update(sql, arguments);
            } finally {
                jdbcTemplate.execute("ROLLBACK TO SAVEPOINT before_rejected_insert");
            }
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private User user(Business business, String email, Role role) {
        User user = new User();
        user.setBusinessId(business.getId());
        user.setFirstName("Test");
        user.setLastName(role.name());
        user.setEmail(email);
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    @Test
    void emailIsUniqueAcrossBusinesses() {
        List<UUID> businessIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        for (UUID businessId : businessIds) jdbcTemplate.update("INSERT INTO businesses (id, name) VALUES (?, 'Email test')", businessId);
        jdbcTemplate.update("INSERT INTO users (business_id, first_name, last_name, email) VALUES (?, 'One', 'Person', 'same@schema.test')", businessIds.get(0));
        assertRejected("INSERT INTO users (business_id, first_name, last_name, email) VALUES (?, 'Other', 'Person', 'same@schema.test')", businessIds.get(1));
    }

    @Test
    void theReportingManagerIsAnotherUserOfTheSameBusiness() {
        UUID businessId = UUID.randomUUID();
        UUID otherBusinessId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO businesses (id, name) VALUES (?, 'Manager test'), (?, 'Other business')", businessId, otherBusinessId);
        UUID managerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, business_id, first_name, last_name, email, role) VALUES (?, ?, 'Mona', 'Manager', 'mona@manager.test', 'MANAGER')",
                managerId, businessId);
        jdbcTemplate.update("INSERT INTO users (id, business_id, first_name, last_name, email, manager_id) VALUES (?, ?, 'Ravi', 'Report', 'ravi@manager.test', ?)",
                reportId, businessId, managerId);
        jdbcTemplate.update("INSERT INTO users (id, business_id, first_name, last_name, email) VALUES (?, ?, 'Otto', 'Outsider', 'otto@manager.test')",
                outsiderId, otherBusinessId);

        // nobody manages themselves; the manager must exist in the same business; the super admin has no manager
        assertRejected("UPDATE users SET manager_id = id WHERE id = ?", managerId);
        assertRejected("UPDATE users SET manager_id = ? WHERE id = ?", outsiderId, reportId);
        assertRejected("UPDATE users SET manager_id = ? WHERE id = ?", UUID.randomUUID(), reportId);
        assertRejected("INSERT INTO users (first_name, last_name, email, role, manager_id) VALUES ('Super', 'Admin', 'super@manager.test', 'SUPER_ADMIN', ?)", managerId);

        // the workday keeps a snapshot of the manager; deleting the manager clears both links, nothing else
        UUID workdayId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workdays (id, business_id, employee_id, manager_id, work_date, time_zone, started_at, active_employee_id) "
                + "VALUES (?, ?, ?, ?, DATE '2026-10-07', 'Asia/Kolkata', now(), ?)", workdayId, businessId, reportId, managerId, reportId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", managerId);
        assertThat(jdbcTemplate.queryForMap("SELECT business_id, manager_id FROM users WHERE id = ?", reportId))
                .containsEntry("business_id", businessId).containsEntry("manager_id", null);
        assertThat(jdbcTemplate.queryForObject("SELECT manager_id FROM workdays WHERE id = ?", UUID.class, workdayId)).isNull();
    }

    @Test
    void onlyTheSuperAdminBelongsToNoBusiness() {
        UUID businessId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO businesses (id, name) VALUES (?, 'Role test')", businessId);
        // a super admin inside a business, and a business user without one, are both refused
        assertRejected("INSERT INTO users (business_id, first_name, last_name, email, role) VALUES (?, 'Super', 'Admin', 'inside@schema.test', 'SUPER_ADMIN')", businessId);
        for (String role : List.of("ADMIN", "MANAGER", "EMPLOYEE")) {
            assertRejected("INSERT INTO users (business_id, first_name, last_name, email, role) VALUES (NULL, 'No', 'Business', ?, ?::user_role)",
                    role.toLowerCase() + "@nowhere.schema.test", role);
        }

        User superAdmin = new User();
        superAdmin.setFirstName("Super");
        superAdmin.setLastName("Admin");
        superAdmin.setEmail("super.admin@schema.test");
        superAdmin.setRole(Role.SUPER_ADMIN);
        entityManager.persist(superAdmin);
        entityManager.flush();
        entityManager.clear();
        User storedSuperAdmin = entityManager.find(User.class, superAdmin.getId());
        assertThat(storedSuperAdmin.getRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(storedSuperAdmin.getBusinessId()).isNull();
    }
}
