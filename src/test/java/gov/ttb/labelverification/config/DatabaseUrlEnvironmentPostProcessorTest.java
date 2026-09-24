package gov.ttb.labelverification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void convertsPlatformUrlToJdbcWithCredentials() {
        Map<String, Object> p = DatabaseUrlEnvironmentPostProcessor.convert(
                "postgresql://app_user:s%40fe%3Apw@postgres.railway.internal:5432/railway", null, null);
        assertThat(p)
                .containsEntry("spring.datasource.url", "jdbc:postgresql://postgres.railway.internal:5432/railway")
                .containsEntry("spring.datasource.username", "app_user")
                .containsEntry("spring.datasource.password", "s@fe:pw");
    }

    @Test
    void keepsQueryAndDefaultsPort() {
        Map<String, Object> p = DatabaseUrlEnvironmentPostProcessor.convert(
                "postgres://u:p@db.example.com/labels?sslmode=require", null, null);
        assertThat(p).containsEntry("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/labels?sslmode=require");
    }

    @Test
    void explicitCredentialsWin() {
        Map<String, Object> p = DatabaseUrlEnvironmentPostProcessor.convert(
                "postgresql://u:p@h:5432/d", "explicit", "explicit-secret");
        assertThat(p).doesNotContainKeys("spring.datasource.username", "spring.datasource.password");
    }

    @Test
    void jdbcUrlsAreLeftAlone() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.convert("jdbc:postgresql://h:5432/d", null, null)).isEmpty();
        assertThat(DatabaseUrlEnvironmentPostProcessor.convert(null, null, null)).isEmpty();
    }
}
