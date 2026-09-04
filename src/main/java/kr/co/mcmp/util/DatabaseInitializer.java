package kr.co.mcmp.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class DatabaseInitializer implements CommandLineRunner{

    private static final String STORAGE_CLASS_CAPABILITY = "storage-class";
    private static final String JUPYTER_CATALOG_TITLE = "JupyterLab for Object Storage";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public void run(String... args) throws Exception {
        // SOFTWARE_SOURCE_MAPPING 테이블이 비어있을 때만 실행 (이 테이블이 마지막에 생성되므로)
        if (isSourceMappingEmpty() && isDatabaseEmpty()) {
            System.out.println("데이터베이스 초기화를 시작합니다...");
            Resource resource = resourceLoader.getResource("classpath:import.sql");
            String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            jdbcTemplate.execute(sql);
            System.out.println("데이터베이스 초기화가 완료되었습니다.");
        } else {
            System.out.println("데이터베이스가 이미 초기화되어 있습니다. 건너뜁니다.");
        }
        ensureBuiltInJupyterCatalog();
        ensureBuiltInCatalogCapabilities();
    }

    private void ensureBuiltInJupyterCatalog() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO SOFTWARE_CATALOG (
                        TITLE, DESCRIPTION, SUMMARY, CATEGORY, LOGO_URL_LARGE, LOGO_URL_SMALL,
                        MIN_CPU, RECOMMENDED_CPU, MIN_MEMORY, RECOMMENDED_MEMORY, MIN_DISK,
                        RECOMMENDED_DISK, CPU_THRESHOLD, MEMORY_THRESHOLD, MIN_REPLICAS,
                        MAX_REPLICAS, HPA_ENABLED, DEFAULT_PORT, INGRESS_ENABLED, CREATED_AT, UPDATED_AT)
                    SELECT ?,
                        'JupyterLab data analysis environment connected to Object Storage registered in Tumblebug.',
                        'Analyze Object Storage data in JupyterLab', 'Object Storage',
                        'https://raw.githubusercontent.com/jupyter/design/master/logos/Square%20Logo/squarelogo-greytext-orangebody-greymoons/squarelogo-greytext-orangebody-greymoons.png',
                        'https://raw.githubusercontent.com/jupyter/design/master/logos/Square%20Logo/squarelogo-greytext-orangebody-greymoons/squarelogo-greytext-orangebody-greymoons.png',
                        1, 2, 2, 4, 5, 10, 80.0, 80.0, 1, 1, false, 8888, false,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    WHERE NOT EXISTS (SELECT 1 FROM SOFTWARE_CATALOG WHERE LOWER(TITLE) = LOWER(?))
                    """, JUPYTER_CATALOG_TITLE, JUPYTER_CATALOG_TITLE);

            jdbcTemplate.update("""
                    UPDATE SOFTWARE_CATALOG
                       SET DESCRIPTION = 'JupyterLab data analysis environment connected to Object Storage registered in Tumblebug.',
                           UPDATED_AT = CURRENT_TIMESTAMP
                     WHERE LOWER(TITLE) = LOWER(?)
                       AND DESCRIPTION = 'JupyterLab data analysis environment connected to user-supplied S3-compatible object storage.'
                    """, JUPYTER_CATALOG_TITLE);

            List<Long> catalogIds = jdbcTemplate.queryForList(
                    "SELECT ID FROM SOFTWARE_CATALOG WHERE LOWER(TITLE) = LOWER(?)",
                    Long.class,
                    JUPYTER_CATALOG_TITLE);
            for (Long catalogId : catalogIds) {
                jdbcTemplate.update("""
                        INSERT INTO PACKAGE_INFO (
                            CATALOG_ID, PACKAGE_TYPE, PACKAGE_NAME, PACKAGE_VERSION, REPOSITORY_URL,
                            DOCKER_PUBLISHER, DOCKER_CREATED_AT, DOCKER_UPDATED_AT,
                            DOCKER_SHORT_DESCRIPTION, DOCKER_SOURCE, ARCHITECTURES, CATEGORIES,
                            IS_ARCHIVED, IS_AUTOMATED, IS_OFFICIAL, LAST_PULLED_AT, OPERATING_SYSTEMS)
                        SELECT ?, 'DOCKER', 'quay.io/jupyter/scipy-notebook', '2026-07-28',
                            'https://quay.io/repository/jupyter/scipy-notebook', 'Jupyter Docker Stacks',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                            'JupyterLab scientific Python environment for Object Storage analysis',
                            'official', 'amd64,arm64', 'Object Storage', false, false, true,
                            CURRENT_TIMESTAMP, 'linux'
                        WHERE NOT EXISTS (SELECT 1 FROM PACKAGE_INFO WHERE CATALOG_ID = ?)
                        """, catalogId, catalogId);

                ensureCatalogReference(catalogId, "https://jupyter-docker-stacks.readthedocs.io/", "HOMEPAGE");
                ensureCatalogReference(catalogId, "object-storage", "CAPABILITY");
                ensureCatalogReference(catalogId, "s3-compatible", "TAG");
                ensureCatalogReference(catalogId, "jupyterlab", "TAG");
                ensureCatalogReference(catalogId, "vm_application_install", "workflow");
                ensureCatalogReference(catalogId, "vm_application_uninstall", "workflow");
            }
        } catch (Exception e) {
            System.out.println("Built-in Jupyter catalog synchronization skipped: " + e.getMessage());
        }
    }

    private void ensureCatalogReference(Long catalogId, String value, String type) {
        Long existingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SOFTWARE_CATALOG_REF WHERE CATALOG_ID = ? AND LOWER(REF_VALUE) = LOWER(?) AND UPPER(REF_TYPE) = UPPER(?)",
                Long.class,
                catalogId,
                value,
                type);
        if (existingCount != null && existingCount > 0) {
            return;
        }

        Number nextRefIdx = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(REF_IDX), -1) + 1 FROM SOFTWARE_CATALOG_REF WHERE CATALOG_ID = ?",
                Number.class,
                catalogId);
        jdbcTemplate.update(
                "INSERT INTO SOFTWARE_CATALOG_REF (CATALOG_ID, REF_IDX, REF_VALUE, REF_DESC, REF_TYPE) VALUES (?, ?, ?, '', ?)",
                catalogId,
                nextRefIdx != null ? nextRefIdx.intValue() : 0,
                value,
                type);
    }

    private boolean isDatabaseEmpty() {
        try {
            // SOFTWARE_CATALOG 테이블이 존재하는지 확인
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SOFTWARE_CATALOG", Long.class);
            // 테이블이 존재하면 데이터 개수 확인
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SOFTWARE_CATALOG", Long.class);
            return count != null && count == 0;
        } catch (Exception e) {
            // 테이블이 존재하지 않으면 빈 데이터베이스로 간주
            return true;
        }
    }
    
    private boolean isSourceMappingEmpty() {
        try {
            // SOFTWARE_SOURCE_MAPPING 테이블이 존재하는지 확인
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SOFTWARE_SOURCE_MAPPING", Long.class);
            // 테이블이 존재하면 데이터 개수 확인
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SOFTWARE_SOURCE_MAPPING", Long.class);
            return count != null && count == 0;
        } catch (Exception e) {
            // 테이블이 존재하지 않으면 빈 데이터베이스로 간주
            return true;
        }
    }

    private void ensureBuiltInCatalogCapabilities() {
        try {
            ensureStorageClassCapability("loki");
        } catch (Exception e) {
            System.out.println("Built-in catalog capability synchronization skipped: " + e.getMessage());
        }
    }

    private void ensureStorageClassCapability(String chartName) {
        List<Long> catalogIds = jdbcTemplate.queryForList(
                "SELECT sc.ID FROM SOFTWARE_CATALOG sc JOIN HELM_CHART hc ON hc.CATALOG_ID = sc.ID WHERE LOWER(hc.CHART_NAME) = ?",
                Long.class,
                chartName.toLowerCase());

        for (Long catalogId : catalogIds) {
            Long existingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM SOFTWARE_CATALOG_REF WHERE CATALOG_ID = ? AND LOWER(REF_VALUE) = ? AND UPPER(REF_TYPE) IN ('CAPABILITY', 'TAG')",
                    Long.class,
                    catalogId,
                    STORAGE_CLASS_CAPABILITY);
            if (existingCount != null && existingCount > 0) {
                continue;
            }

            Number nextRefIdx = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(REF_IDX), -1) + 1 FROM SOFTWARE_CATALOG_REF WHERE CATALOG_ID = ?",
                    Number.class,
                    catalogId);

            jdbcTemplate.update(
                    "INSERT INTO SOFTWARE_CATALOG_REF (CATALOG_ID, REF_IDX, REF_VALUE, REF_DESC, REF_TYPE) VALUES (?, ?, ?, '', 'CAPABILITY')",
                    catalogId,
                    nextRefIdx != null ? nextRefIdx.intValue() : 0,
                    STORAGE_CLASS_CAPABILITY);
        }
    }
}
