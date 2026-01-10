package com.commandbus.e2e;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * E2E Test Application for CommandBus library.
 *
 * <p>This application is used for:
 * <ul>
 *   <li>Integration testing during development</li>
 *   <li>Manual testing and debugging</li>
 *   <li>Demonstrating library features</li>
 * </ul>
 *
 * <p><strong>NOT shipped with the library - test scope only.</strong>
 *
 * <p>Run with: {@code mvn spring-boot:test-run -Dspring-boot.run.profiles=e2e}
 */
@SpringBootApplication(scanBasePackages = {"com.commandbus"})
public class E2ETestApplication {

    public static void main(String[] args) {
        SpringApplication.run(E2ETestApplication.class, args);
    }
}
