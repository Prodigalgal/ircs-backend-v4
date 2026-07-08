package com.prodigalgal.ircs.identity.application;



import com.prodigalgal.ircs.identity.IdentityRedisKeys;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWChallenge;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWVerification;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PoWService {

    static final String HEADER_CHALLENGE = "X-IRCS-POW-Challenge";
    static final String HEADER_COUNTER = "X-IRCS-POW-Counter";
    static final String HEADER_DIGEST = "X-IRCS-POW-Digest";
    static final String HEADER_CAPTCHA_ANSWER = "X-IRCS-POW-Captcha-Answer";

    private static final String ALGORITHM = "SHA-256";
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); if v then redis.call('DEL', KEYS[1]); end; return v;",
            String.class);
    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "admin.login",
            "portal.login",
            "portal.register",
            "portal.activate",
            "portal.resend-code",
            "portal.forgot-password",
            "portal.reset-password");
    private static final Set<String> CAPTCHA_SCOPES = Set.of("admin.login", "portal.login");

    private final StringRedisTemplate redisTemplate;
    private final IdentityConfigService configService;
    private final SecureRandom secureRandom;

    public PoWService(StringRedisTemplate redisTemplate, IdentityConfigService configService, SecureRandom secureRandom) {
        this.redisTemplate = redisTemplate;
        this.configService = configService;
        this.secureRandom = secureRandom;
    }

    public PoWChallenge generateChallenge() {
        return generateChallenge("auth");
    }

    public PoWChallenge generateChallenge(String requestedScope) {
        String scope = normalizeScope(requestedScope, "auth");
        String id = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        int difficulty = clampDifficulty(configService.intProperty("app.identity.pow.difficulty", 4));
        Duration ttl = configService.durationProperty("app.identity.pow.ttl", Duration.ofMinutes(5));
        Instant expiresAt = Instant.now().plus(ttl);
        MathCaptcha captcha = CAPTCHA_SCOPES.contains(scope) ? mathCaptcha() : MathCaptcha.none();
        redisTemplate.opsForValue().set(
                IdentityRedisKeys.powChallenge(id),
                String.join(
                        "|",
                        scope,
                        String.valueOf(difficulty),
                        nonce,
                        captcha.answer(),
                        String.valueOf(expiresAt.toEpochMilli())),
                ttl);
        return new PoWChallenge(
                id,
                nonce,
                difficulty,
                ALGORITHM,
                scope,
                expiresAt,
                captcha.required(),
                captcha.question());
    }

    public void verifyHeaders(HttpServletRequest request, String expectedScope, boolean captchaRequired) {
        String id = header(request, HEADER_CHALLENGE);
        String counter = header(request, HEADER_COUNTER);
        String digest = header(request, HEADER_DIGEST);
        if (!StringUtils.hasText(id) || !StringUtils.hasText(counter) || !StringUtils.hasText(digest)) {
            throw ApiException.badRequest("Missing PoW verification headers", "security", "pow.missing");
        }

        ChallengeState state = consumeChallenge(id);
        if (!state.scope().equals(expectedScope)) {
            throw ApiException.badRequest("PoW challenge scope mismatch", "security", "pow.scope.invalid");
        }
        String expectedDigest = sha256(id + ":" + state.scope() + ":" + state.nonce() + ":" + counter);
        if (!constantTimeEquals(expectedDigest, digest) || !expectedDigest.startsWith("0".repeat(state.difficulty()))) {
            throw ApiException.badRequest("PoW validation failed", "security", "pow.invalid");
        }
        if (captchaRequired && !state.captchaAnswer().equals(header(request, HEADER_CAPTCHA_ANSWER))) {
            throw ApiException.badRequest("Captcha validation failed", "security", "captcha.invalid");
        }
    }

    public void verifyIfPresent(PoWVerification verification) {
        if (verification != null) {
            verify(verification);
        }
    }

    public void verify(PoWVerification verification) {
        if (verification == null) {
            throw ApiException.badRequest("Missing PoW verification data", "security", "pow.missing");
        }
        String key = IdentityRedisKeys.powChallenge(verification.id());
        String difficultyValue = redisTemplate.opsForValue().get(key);
        if (difficultyValue == null) {
            throw ApiException.badRequest("PoW challenge expired or invalid", "security", "pow.expired");
        }
        redisTemplate.delete(key);

        int difficulty = legacyDifficulty(difficultyValue);
        String hash = sha256(verification.id() + verification.nonce());
        if (!hash.startsWith("0".repeat(difficulty))) {
            throw ApiException.badRequest("PoW validation failed", "security", "pow.invalid");
        }
    }

    private ChallengeState consumeChallenge(String id) {
        String raw = redisTemplate.execute(CONSUME_SCRIPT, List.of(IdentityRedisKeys.powChallenge(id)));
        if (!StringUtils.hasText(raw)) {
            throw ApiException.badRequest("PoW challenge expired or invalid", "security", "pow.expired");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 5) {
            throw ApiException.badRequest("PoW challenge expired or invalid", "security", "pow.expired");
        }
        return new ChallengeState(
                parts[0],
                parseInt(parts[1], 4),
                parts[2],
                parts[3],
                parseLong(parts[4], 0L));
    }

    private MathCaptcha mathCaptcha() {
        MathCaptchaOperator[] operators = MathCaptchaOperator.values();
        return operators[secureRandom.nextInt(operators.length)].create(secureRandom);
    }

    private String normalizeScope(String value, String fallback) {
        String normalized = StringUtils.hasText(value)
                ? value.trim().toLowerCase(Locale.ROOT)
                : fallback;
        if ("auth".equals(normalized)) {
            return "portal.login";
        }
        if (!ALLOWED_SCOPES.contains(normalized)) {
            throw ApiException.badRequest("Unsupported PoW challenge scope", "security", "pow.scope.unsupported");
        }
        return normalized;
    }

    private int legacyDifficulty(String raw) {
        if (raw != null && raw.contains("|")) {
            return parseInt(raw.split("\\|", -1)[1], 4);
        }
        return parseInt(raw, 4);
    }

    private int clampDifficulty(int value) {
        return Math.max(0, Math.min(value, 8));
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private record ChallengeState(String scope, int difficulty, String nonce, String captchaAnswer, long expiresAtMillis) {
    }

    private record MathCaptcha(boolean required, String question, String answer) {

        static MathCaptcha none() {
            return new MathCaptcha(false, null, "");
        }
    }

    private enum MathCaptchaOperator {
        ADD("+") {
            @Override
            MathCaptcha create(SecureRandom random) {
                int left = number(random, 2, 19);
                int right = number(random, 2, 19);
                return captcha(left, symbol, right, left + right);
            }
        },
        SUBTRACT("-") {
            @Override
            MathCaptcha create(SecureRandom random) {
                int left = number(random, 3, 20);
                int right = number(random, 1, left);
                return captcha(left, symbol, right, left - right);
            }
        },
        MULTIPLY("*") {
            @Override
            MathCaptcha create(SecureRandom random) {
                int left = number(random, 2, 12);
                int right = number(random, 2, 12);
                return captcha(left, symbol, right, left * right);
            }
        },
        DIVIDE("/") {
            @Override
            MathCaptcha create(SecureRandom random) {
                int divisor = number(random, 2, 12);
                int quotient = number(random, 2, 12);
                return captcha(divisor * quotient, symbol, divisor, quotient);
            }
        };

        final String symbol;

        MathCaptchaOperator(String symbol) {
            this.symbol = symbol;
        }

        abstract MathCaptcha create(SecureRandom random);

        static int number(SecureRandom random, int min, int max) {
            return random.nextInt(max - min + 1) + min;
        }

        static MathCaptcha captcha(int left, String symbol, int right, int answer) {
            return new MathCaptcha(true, left + " " + symbol + " " + right + " = ?", String.valueOf(answer));
        }
    }
}
