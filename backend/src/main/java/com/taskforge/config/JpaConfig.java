package com.taskforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JpaConfig — Activates Spring Data JPA features needed by the entity layer.
 *             Turn on JPA auditing and repository scanning for the entire application.

 * <p><b>@EnableJpaAuditing:</b>
 * Without this annotation, the {@code @CreatedDate} and {@code @LastModifiedDate}
 * annotations on {@link com.taskforge.common.BaseEntity} are silently ignored —
 * those fields will always be {@code null}. This single annotation tells Spring to
 * register the {@code AuditingEntityListener} and populate those fields on save.
 *
 * <p><b>@EnableJpaRepositories:</b>
 * Tells Spring Data where to scan for {@code JpaRepository} interfaces. Without this,
 * repositories in sub-packages like {@code tenant.repository}, {@code user.repository},
 * etc. might not be detected if the main app class is not at the root of the package
 * hierarchy that Spring scans by default.
 * Explicitly pointing to {@code com.taskforge} covers all sub-packages.
 *
 * <p><b>Why a separate config class and not annotate TaskForgeApplication?</b>
 * Keeping JPA config separate from the main application class follows the single
 * responsibility principle and makes it easier to test JPA config in isolation.
 */
@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.taskforge")
public class JpaConfig {
    // No beans needed — annotations activate the Spring Data JPA features.
}
