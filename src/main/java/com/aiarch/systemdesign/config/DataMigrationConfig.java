package com.aiarch.systemdesign.config;

import com.aiarch.systemdesign.service.SystemDesignMigrationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataMigrationConfig {

    @Bean
    public ApplicationRunner systemDesignMigrationRunner(SystemDesignMigrationService migrationService) {
        return args -> migrationService.migrateLegacyJsonColumnIfNeeded();
    }
}
