package com.dealflow360;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DealFlow360 - An Intelligent, Self Governing Sales Operations Platform.
 * <p>
 * Run this class (or `mvn spring-boot:run`) to start the whole application.
 * The backend REST API is served under /api/** and the frontend (plain
 * HTML/CSS/JS, no build step needed) is served as static content from
 * src/main/resources/static, so opening http://localhost:8080 in a browser
 * is enough to use the whole system.
 * <p>
 * {@code @EnableScheduling} turns on the {@code AutomationScheduler}
 * background jobs (auto-nudging stalled deals, auto-consolidating
 * backorders, auto-flagging low stock) - the "as much automation as
 * possible" differentiator that runs on its own, with no user action.
 */
@SpringBootApplication
@EnableScheduling
public class DealFlow360Application {
    public static void main(String[] args) {
        SpringApplication.run(DealFlow360Application.class, args);
    }
}
