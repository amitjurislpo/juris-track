package com.jurislpo.juristrack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.service.SeedService.SeedOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The seed creates the super admin and the demo business once; running it again changes nothing. */
class SeedServiceTest extends IntegrationTest {

    static final String SUPER_ADMIN_EMAIL = "Seed.Super.Admin@Example.com";

    @Autowired SeedService seedService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void seedingTwiceCreatesEverythingOnce() {
        SeedOutcome firstOutcome = seedService.seed(SUPER_ADMIN_EMAIL, true);
        Map<String, Object> countsAfterFirstRun = counts();
        SeedOutcome secondOutcome = seedService.seed(SUPER_ADMIN_EMAIL, true);

        assertThat(firstOutcome).isEqualTo(new SeedOutcome(true, true));
        assertThat(secondOutcome).isEqualTo(new SeedOutcome(false, false));
        assertThat(counts()).isEqualTo(countsAfterFirstRun);

        // the super admin: stored lower-cased, no business
        Map<String, Object> superAdmin = jdbcTemplate.queryForMap("SELECT role::text AS role, business_id FROM users WHERE email = 'seed.super.admin@example.com'");
        assertThat(superAdmin.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(superAdmin.get("business_id")).isNull();

        // the demo business: owner, manager, four employees in one business; the employees report to the manager, the manager to the owner
        assertThat(jdbcTemplate.queryForList("SELECT role::text FROM users WHERE email LIKE '%" + SeedService.DEMO_EMAIL_DOMAIN + "' ORDER BY employee_code", String.class))
                .containsExactly("ADMIN", "MANAGER", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT business_id) FROM users WHERE email LIKE '%" + SeedService.DEMO_EMAIL_DOMAIN + "'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT person.employee_code || ' -> ' || COALESCE(manager.employee_code, '-') FROM users person "
                + "LEFT JOIN users manager ON manager.id = person.manager_id WHERE person.email LIKE '%" + SeedService.DEMO_EMAIL_DOMAIN + "' "
                + "ORDER BY person.employee_code", String.class))
                .containsExactly("DEMO-001 -> -", "DEMO-002 -> DEMO-001", "DEMO-003 -> DEMO-002", "DEMO-004 -> DEMO-002", "DEMO-005 -> DEMO-002", "DEMO-006 -> DEMO-002");
    }

    @Test
    void withoutTheDemoFlagOnlyTheSuperAdminIsCreated() {
        assertThat(seedService.seed("only.super.admin@example.com", false)).isEqualTo(new SeedOutcome(true, false));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = 'only.super.admin@example.com' AND role = 'SUPER_ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    private Map<String, Object> counts() {
        return jdbcTemplate.queryForMap("SELECT (SELECT count(*) FROM businesses) AS businesses, (SELECT count(*) FROM users) AS users, "
                + "(SELECT count(*) FROM users WHERE manager_id IS NOT NULL) AS reporting_lines");
    }
}
