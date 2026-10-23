package com.jurislpo.juristrack.cli;

import com.jurislpo.juristrack.service.SeedService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One-shot commands, run as {@code ./mvnw spring-boot:run -Dspring-boot.run.arguments=<command>} or {@code java -jar ... <command>}:
 * <ul>
 *   <li>{@code migrate}: Flyway brings the schema up to date while the app starts, then it exits;</li>
 *   <li>{@code seed}: idempotent; the platform super admin and, with {@code juristrack.seed.demo=true}, the demo business
 *       (see {@link SeedService});</li>
 *   <li>{@code reset}: development only: empties the schema, migrates from scratch, then seeds. FlywayConfig refuses
 *       unless {@code juristrack.app-url} is localhost or {@code juristrack.allow-database-reset=true}.</li>
 * </ul>
 */
@Component
public class Commands {

    public static final String MIGRATE = "migrate";
    public static final String SEED = "seed";
    public static final String RESET = "reset";
    static final Set<String> NAMES = Set.of(MIGRATE, SEED, RESET);

    private static final Logger log = LoggerFactory.getLogger(Commands.class);

    private final SeedService seedService;

    public Commands(SeedService seedService) {
        this.seedService = seedService;
    }

    /** The command named in the arguments, or null to run the API. */
    public static String commandIn(String[] arguments) {
        for (String argument : arguments) {
            if (NAMES.contains(argument)) return argument;
        }
        return null;
    }

    public int run(String command) {
        try {
            if (MIGRATE.equals(command)) {
                log.info("Database schema is up to date");
            } else {
                // seed, and reset after the schema was emptied and re-migrated while the app started
                SeedService.SeedOutcome outcome = seedService.seed();
                log.info("Seed done: super admin created {}, demo business created {}", outcome.isSuperAdminCreated(), outcome.isDemoBusinessCreated());
            }
            return 0;
        } catch (RuntimeException e) {
            log.error("{} failed", command, e);
            return 1;
        }
    }
}
