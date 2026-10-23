package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.crypto.Tokens;
import com.jurislpo.juristrack.repository.LoginOtpRepository;
import com.jurislpo.juristrack.repository.LoginOtpRepository.OpenCode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Emailed sign-in codes. Only a salted SHA-256 of a code is stored (the email is the salt). A code works once, for
 * {@code juristrack.otp.code-ttl-minutes}, and is burnt after {@code juristrack.otp.max-attempts} wrong tries; a new code
 * replaces the old one. With {@code juristrack.otp.fixed-code} set (dev only, EnvironmentGuard) every code is that value.
 */
@Service
public class OtpService {

    private final LoginOtpRepository loginOtpRepository;
    private final TransactionTemplate transactionTemplate;
    private final MailService mailService;
    private final JuristrackProperties.Otp settings;

    public OtpService(LoginOtpRepository loginOtpRepository, TransactionTemplate transactionTemplate, MailService mailService,
            JuristrackProperties properties) {
        this.loginOtpRepository = loginOtpRepository;
        this.transactionTemplate = transactionTemplate;
        this.mailService = mailService;
        this.settings = properties.otp();
    }

    static String hash(String email, String code) {
        return Tokens.sha256(email + ":" + code);
    }

    /** Creates a code for this (already checked) account and emails it. */
    public void issue(String email, String name, String ipAddress) {
        String code = settings.fixedCode().isEmpty() ? Tokens.randomSixDigitCode() : settings.fixedCode();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            loginOtpRepository.invalidateOpenCodes(email, now);
            loginOtpRepository.insert(email, hash(email, code), now.plus(Duration.ofMinutes(settings.codeTtlMinutes())), ipAddress);
        });
        mailService.send(email, MailTemplates.signInCode(name, code, settings.codeTtlMinutes()));
    }

    /** What checking a code found (for the answer only {@link #MATCHED} matters; the others are recorded in the audit log). */
    public enum CodeCheck {
        /** The newest open code of the email; it is now used up. */
        MATCHED,
        /** A wrong code; the try was counted and tries are left. */
        WRONG_CODE,
        /** A wrong code that used up the last try: the code is burnt from now on. */
        WRONG_CODE_BURNT_NOW,
        /** The open code was already burnt by earlier wrong tries. */
        ALREADY_BURNT,
        /** No open code: never requested, expired, used, or replaced by a newer one. */
        NO_OPEN_CODE
    }

    /** Checks {@code code} against the newest open code of the email (used up when it matches). A wrong try is counted. */
    public CodeCheck verify(String email, String code) {
        return transactionTemplate.execute(transactionStatus -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Optional<OpenCode> openCode = loginOtpRepository.findOpenCodeForUpdate(email, now);
            if (openCode.isEmpty()) return CodeCheck.NO_OPEN_CODE;
            if (openCode.get().attempts() >= settings.maxAttempts()) return CodeCheck.ALREADY_BURNT;
            if (Tokens.isEqualInConstantTime(openCode.get().codeHash(), hash(email, code))) {
                loginOtpRepository.markUsed(openCode.get().id(), now);
                return CodeCheck.MATCHED;
            }
            loginOtpRepository.addAttempt(openCode.get().id());
            return openCode.get().attempts() + 1 >= settings.maxAttempts() ? CodeCheck.WRONG_CODE_BURNT_NOW : CodeCheck.WRONG_CODE;
        });
    }
}
