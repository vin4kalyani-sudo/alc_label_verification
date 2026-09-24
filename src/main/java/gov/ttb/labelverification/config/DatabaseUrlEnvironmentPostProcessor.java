package gov.ttb.labelverification.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Accepts {@code DATABASE_URL} in the {@code postgresql://user:password@host:port/db} form
 * that hosting platforms (Railway, Heroku, Render…) inject, and converts it to the JDBC
 * URL plus username and password Spring expects. JDBC URLs pass through untouched.
 * Explicit {@code DATABASE_USERNAME} / {@code DATABASE_PASSWORD} always win.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "platformDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> converted = convert(env.getProperty("DATABASE_URL"),
                env.getProperty("DATABASE_USERNAME"), env.getProperty("DATABASE_PASSWORD"));
        if (!converted.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, converted));
        }
    }

    static Map<String, Object> convert(String url, String explicitUser, String explicitPassword) {
        Map<String, Object> props = new HashMap<>();
        if (url == null || !(url.startsWith("postgres://") || url.startsWith("postgresql://"))) {
            return props;
        }
        URI uri = URI.create(url);
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        props.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = decode(colon >= 0 ? userInfo.substring(0, colon) : userInfo);
            String password = colon >= 0 ? decode(userInfo.substring(colon + 1)) : null;
            if (isBlank(explicitUser)) {
                props.put("spring.datasource.username", user);
            }
            if (isBlank(explicitPassword) && password != null) {
                props.put("spring.datasource.password", password);
            }
        }
        return props;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
