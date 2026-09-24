package gov.ttb.labelverification.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import gov.ttb.labelverification.domain.Applicant;
import gov.ttb.labelverification.domain.User;
import gov.ttb.labelverification.domain.UserRole;
import gov.ttb.labelverification.repository.ApplicantRepository;
import gov.ttb.labelverification.repository.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates or updates user accounts declared in {@code APP_USERS}, a JSON array:
 * <pre>
 * [{"role":"SPECIALIST","email":"a@agency.gov","name":"A. Reviewer","passwordHash":"{bcrypt}$2a$10$..."},
 *  {"role":"APPLICANT","email":"b@winery.com","name":"B. Owner","company":"B Winery","password":"..."}]
 * </pre>
 * <ul>
 *   <li>{@code passwordHash} (preferred) is a Spring Security encoded hash, e.g. {@code {bcrypt}…};
 *       {@code password} is plain text (at least 12 characters) and is hashed on startup.</li>
 *   <li>Idempotent: missing accounts are created; an existing account's password is updated
 *       only when it differs from the declared one. Role changes are not applied to existing
 *       accounts. Applicant companies are created by name if they don't exist.</li>
 *   <li>Invalid entries are skipped with a log message. Passwords are never logged.</li>
 * </ul>
 * Runs after {@link DataSeeder}, independently of {@code APP_SEED}.
 */
@Component
@Order(100)
public class UserProvisioner implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(UserProvisioner.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** One declared account. */
    public record Entry(String role, String email, String name, String company, String password,
                        String passwordHash) {
    }

    private final UserRepository users;
    private final ApplicantRepository applicants;
    private final PasswordEncoder encoder;
    private final String config;

    public UserProvisioner(UserRepository users, ApplicantRepository applicants, PasswordEncoder encoder,
                           @Value("${app.users:}") String config) {
        this.users = users;
        this.applicants = applicants;
        this.encoder = encoder;
        this.config = config;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (config == null || config.isBlank()) {
            log.info("APP_USERS is not set on this service — no additional accounts provisioned.");
            return;
        }
        List<Entry> entries;
        try {
            entries = List.of(JSON.readValue(config, Entry[].class));
        } catch (JsonProcessingException e) {
            // Never echo the value: it may contain passwords.
            log.error("APP_USERS is not valid JSON (line {}, column {}); no accounts were provisioned.",
                    e.getLocation() == null ? "?" : e.getLocation().getLineNr(),
                    e.getLocation() == null ? "?" : e.getLocation().getColumnNr());
            return;
        }
        int created = 0, updated = 0, skipped = 0;
        for (int i = 0; i < entries.size(); i++) {
            switch (apply(entries.get(i), i + 1)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> { }
                case SKIPPED -> skipped++;
            }
        }
        log.info("APP_USERS: {} created, {} password(s) updated, {} skipped, {} declared.",
                created, updated, skipped, entries.size());
    }

    enum Outcome { CREATED, UPDATED, UNCHANGED, SKIPPED }

    Outcome apply(Entry e, int position) {
        String email = e.email() == null ? "" : e.email().trim();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            log.error("APP_USERS entry {}: missing or invalid email — skipped.", position);
            return Outcome.SKIPPED;
        }
        UserRole role;
        try {
            role = UserRole.valueOf(e.role() == null ? "" : e.role().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.error("APP_USERS entry {} ({}): role must be SPECIALIST or APPLICANT — skipped.", position, email);
            return Outcome.SKIPPED;
        }
        String hash = hashFor(e, position, email);
        if (hash == null) {
            return Outcome.SKIPPED;
        }

        Optional<User> existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getRole() != role) {
                log.warn("APP_USERS entry {} ({}): account exists with role {} — role not changed.",
                        position, email, user.getRole());
            }
            if (!samePassword(e, user.getPasswordHash())) {
                user.changePasswordHash(hash);
                log.info("APP_USERS: password updated for {}.", email);
                return Outcome.UPDATED;
            }
            return Outcome.UNCHANGED;
        }

        String name = e.name() == null || e.name().isBlank() ? email : e.name().trim();
        Applicant company = null;
        if (role == UserRole.APPLICANT) {
            String companyName = e.company() == null || e.company().isBlank() ? name : e.company().trim();
            company = applicants.findFirstByCompanyNameIgnoreCase(companyName)
                    .orElseGet(() -> applicants.save(new Applicant(companyName, email, name, null)));
        }
        users.save(new User(name, email, hash, role, company));
        log.info("APP_USERS: created {} {}{}.", role.name().toLowerCase(Locale.ROOT), email,
                company == null ? "" : " (" + company.getCompanyName() + ")");
        return Outcome.CREATED;
    }

    private String hashFor(Entry e, int position, String email) {
        if (e.passwordHash() != null && !e.passwordHash().isBlank()) {
            String h = e.passwordHash().trim();
            if (!h.startsWith("{")) {
                log.error("APP_USERS entry {} ({}): passwordHash must include its encoder id, e.g. {{bcrypt}}… — skipped.",
                        position, email);
                return null;
            }
            return h;
        }
        if (e.password() == null || e.password().length() < MIN_PASSWORD_LENGTH) {
            log.error("APP_USERS entry {} ({}): password missing or shorter than {} characters — skipped.",
                    position, email, MIN_PASSWORD_LENGTH);
            return null;
        }
        return encoder.encode(e.password());
    }

    private boolean samePassword(Entry e, String storedHash) {
        if (e.passwordHash() != null && !e.passwordHash().isBlank()) {
            return e.passwordHash().trim().equals(storedHash);
        }
        return encoder.matches(e.password(), storedHash);
    }
}
