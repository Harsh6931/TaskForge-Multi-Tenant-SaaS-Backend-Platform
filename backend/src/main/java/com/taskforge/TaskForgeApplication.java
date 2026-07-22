package com.taskforge;

import com.taskforge.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * TaskForge application entry point.
 *
 * <p><b>@EnableConfigurationProperties:</b>
 * Registers {@link JwtProperties} as a Spring bean and triggers binding of
 * the {@code app.jwt.*} block from {@code application.yml} into its fields.
 * Without this annotation, {@code @ConfigurationProperties} classes are ignored
 * unless they are also annotated with {@code @Component} (which couples them to
 * Spring — putting it here on the main class is the cleaner pattern).
 */
@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class TaskForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskForgeApplication.class, args);
    }
}
