package com.lms.config;

import com.lms.payment.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refuses to run with development defaults outside development.
 *
 * <p>Both of these are convenient locally and dangerous in production: a signing key that is in
 * the repository lets anyone mint an admin token, and simulated settlement lets a learner grant
 * themselves a paid course. Failing at startup is the only reliable way to stop either reaching a
 * real deployment, because nobody reads a warning in a log they never open.
 */
@Component
public class DeploymentSafetyCheck implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DeploymentSafetyCheck.class);
    private static final List<String> DEVELOPMENT_PROFILES = List.of("dev", "test", "schema", "local", "realinfra");

    private final JwtProperties jwt;
    private final PaymentProperties payments;
    private final Environment environment;

    public DeploymentSafetyCheck(JwtProperties jwt, PaymentProperties payments, Environment environment) {
        this.jwt = jwt;
        this.payments = payments;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (isDevelopment()) {
            if (jwt.isPlaceholder()) {
                log.warn("Using the example JWT signing key. Set JWT_SECRET before deploying.");
            }
            if (payments.allowSimulatedSettlement()) {
                log.warn("Simulated payment settlement is enabled. Learners can grant themselves "
                        + "paid courses; this must be off outside development.");
            }
            return;
        }
        if (jwt.isPlaceholder()) {
            throw new IllegalStateException("JWT_SECRET is still the example value from the repository. "
                    + "Generate one with: openssl rand -base64 48");
        }
        if (payments.allowSimulatedSettlement()) {
            throw new IllegalStateException("lms.payments.allow-simulated-settlement must be false "
                    + "outside development: it lets a learner settle their own payment.");
        }
    }

    /**
     * Development has to be asked for by name.
     *
     * <p>Treating "no profile set" as development would mean the one configuration most likely to
     * reach a server by accident is also the one that waves these checks through.
     */
    private boolean isDevelopment() {
        return List.of(environment.getActiveProfiles()).stream().anyMatch(DEVELOPMENT_PROFILES::contains);
    }
}
