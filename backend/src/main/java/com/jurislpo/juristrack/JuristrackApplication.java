package com.jurislpo.juristrack;

import com.jurislpo.juristrack.cli.Commands;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JurisTrack REST API. The web app (juris-track-frontend, port 3006) is its client and proxies /api/* here; embedded in
 * JurisOne, the JurisOne API is the gateway instead. With a command argument it runs that once and exits:
 * {@code migrate}, {@code seed} or {@code reset} (see cli.Commands).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class) // no passwords: no default in-memory user
@ConfigurationPropertiesScan
public class JuristrackApplication {
    public static void main(String[] arguments) {
        String command = Commands.commandIn(arguments);
        SpringApplication application = new SpringApplication(JuristrackApplication.class);
        if (command == null) {
            application.run(arguments);
            return;
        }
        application.setWebApplicationType(WebApplicationType.NONE);
        if (Commands.RESET.equals(command)) {
            System.setProperty("juristrack.reset", "true");               // FlywayConfig cleans before migrating
            System.setProperty("spring.flyway.clean-disabled", "false");
        }
        ConfigurableApplicationContext applicationContext = application.run(arguments);
        int exitCode = applicationContext.getBean(Commands.class).run(command);
        System.exit(SpringApplication.exit(applicationContext, () -> exitCode));
    }
}
