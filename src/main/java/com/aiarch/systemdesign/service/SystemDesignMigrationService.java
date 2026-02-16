package com.aiarch.systemdesign.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemDesignMigrationService {

    private static final Logger log = LoggerFactory.getLogger(SystemDesignMigrationService.class);

    private final JdbcTemplate jdbcTemplate;

    public void migrateLegacyJsonColumnIfNeeded() {
        jdbcTemplate.execute("ALTER TABLE system_designs ADD COLUMN IF NOT EXISTS document_json jsonb");

        Integer hasLegacyColumn = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM information_schema.columns
                WHERE table_name = 'system_designs'
                  AND column_name = 'full_design_json'
                """,
                Integer.class
        );

        if (hasLegacyColumn != null && hasLegacyColumn > 0) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE system_designs
                       SET document_json = full_design_json
                     WHERE document_json IS NULL
                       AND full_design_json IS NOT NULL
                    """
            );
            log.info("System design migration complete. Migrated {} records from full_design_json to document_json", updated);
        }
    }
}
