package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code seed} command (idempotent: running it again creates nothing twice). Runs in system mode (no business).
 * <ul>
 *   <li>the platform super admin ({@code juristrack.seed.super-admin-email}), who belongs to no business;</li>
 *   <li>with {@code juristrack.seed.demo=true} (dev, sqe): one demo business with its owner (ADMIN), a MANAGER, four
 *       EMPLOYEEs who report to the manager (the manager reports to the owner). Every demo address ends in {@value #DEMO_EMAIL_DOMAIN} (a reserved, never-delivered domain).</li>
 * </ul>
 */
@Service
public class SeedService {

    public static final String DEMO_EMAIL_DOMAIN = "@demo.juristrack.test";
    static final String DEMO_OWNER_EMAIL = "owner" + DEMO_EMAIL_DOMAIN;

    private record DemoPerson(String firstName, String lastName, String email, Role role, String employeeCode) {}

    private static final List<DemoPerson> DEMO_PEOPLE = List.of(
            new DemoPerson("Olivia", "Owner", DEMO_OWNER_EMAIL, Role.ADMIN, "DEMO-001"),
            new DemoPerson("Manish", "Manager", "manager" + DEMO_EMAIL_DOMAIN, Role.MANAGER, "DEMO-002"),
            new DemoPerson("Asha", "Rao", "asha.rao" + DEMO_EMAIL_DOMAIN, Role.EMPLOYEE, "DEMO-003"),
            new DemoPerson("Ben", "Carter", "ben.carter" + DEMO_EMAIL_DOMAIN, Role.EMPLOYEE, "DEMO-004"),
            new DemoPerson("Chen", "Li", "chen.li" + DEMO_EMAIL_DOMAIN, Role.EMPLOYEE, "DEMO-005"),
            new DemoPerson("Divya", "Nair", "divya.nair" + DEMO_EMAIL_DOMAIN, Role.EMPLOYEE, "DEMO-006"));

    /** What one run created. */
    public record SeedOutcome(boolean isSuperAdminCreated, boolean isDemoBusinessCreated) {}

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final EntityManager entityManager;
    private final JuristrackProperties properties;

    public SeedService(EntityManager entityManager, JuristrackProperties properties) {
        this.entityManager = entityManager;
        this.properties = properties;
    }

    /** Seeds what the configuration asks for. */
    @Transactional
    public SeedOutcome seed() {
        return seed(properties.seed().superAdminEmail(), properties.seed().demo());
    }

    @Transactional
    public SeedOutcome seed(String superAdminEmail, boolean isDemoWanted) {
        if (TenantContext.current() != null) throw new IllegalStateException("The seed runs in system mode, outside any business");
        boolean isSuperAdminCreated = superAdminEmail != null && !superAdminEmail.isBlank() && createSuperAdminIfMissing(normalizeEmail(superAdminEmail));
        boolean isDemoBusinessCreated = isDemoWanted && createDemoBusinessIfMissing();
        return new SeedOutcome(isSuperAdminCreated, isDemoBusinessCreated);
    }

    private boolean createSuperAdminIfMissing(String email) {
        User existingUser = findUserByEmail(email);
        if (existingUser != null) {
            if (existingUser.getRole() != Role.SUPER_ADMIN) {
                log.warn("Seed: {} already exists as {} in a business; not turned into the super admin", email, existingUser.getRole());
            }
            return false;
        }
        User superAdmin = new User();
        superAdmin.setFirstName("Super");
        superAdmin.setLastName("Admin");
        superAdmin.setEmail(email);
        superAdmin.setRole(Role.SUPER_ADMIN);
        entityManager.persist(superAdmin);
        log.info("Seed: created the super admin {}", email);
        return true;
    }

    private boolean createDemoBusinessIfMissing() {
        if (findUserByEmail(DEMO_OWNER_EMAIL) != null) return false;
        Business business = new Business();
        business.setName("Demo Company");
        entityManager.persist(business);

        // persisted in reporting order (owner, manager, employees), so every manager row exists before its reports
        User owner = null;
        User manager = null;
        for (DemoPerson person : DEMO_PEOPLE) {
            User user = new User();
            user.setBusinessId(business.getId());
            user.setFirstName(person.firstName());
            user.setLastName(person.lastName());
            user.setEmail(person.email());
            user.setRole(person.role());
            user.setEmployeeCode(person.employeeCode());
            switch (person.role()) {
                case ADMIN -> owner = user;                              // the owner reports to nobody
                case MANAGER -> user.setManagerId(owner.getId());        // the manager reports to the owner
                default -> user.setManagerId(manager.getId());           // the employees report to the manager
            }
            entityManager.persist(user);
            if (person.role() == Role.MANAGER) manager = user;
        }
        log.info("Seed: created the demo business {} with {} people (owner {})", business.getId(), DEMO_PEOPLE.size(), DEMO_OWNER_EMAIL);
        return true;
    }

    private User findUserByEmail(String email) {
        return entityManager.createQuery("SELECT existingUser FROM User existingUser WHERE existingUser.email = :email", User.class)
                .setParameter("email", email).getResultStream().findFirst().orElse(null);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
