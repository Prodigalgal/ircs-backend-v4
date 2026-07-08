package com.prodigalgal.ircs.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.identity.dto.IdentityDtos.PoWChallenge;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class PoWServiceTest {

    private static final Pattern CAPTCHA_PATTERN = Pattern.compile("^(\\d+) ([+*/-]) (\\d+) = \\?$");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final IdentityConfigService configService = mock(IdentityConfigService.class);

    @Test
    void springContextCreatesPoWServiceWithProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> redisTemplate)
                .withBean(IdentityConfigService.class, () -> configService)
                .withBean(SecureRandom.class, SecureRandom::new)
                .withUserConfiguration(PoWServiceConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(PoWService.class));
    }

    @Test
    void generatesAddSubtractMultiplyAndDivideCaptchas() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(configService.intProperty("app.identity.pow.difficulty", 4)).thenReturn(0);
        when(configService.durationProperty("app.identity.pow.ttl", Duration.ofMinutes(5)))
                .thenReturn(Duration.ofMinutes(5));
        PoWService service = new PoWService(
                redisTemplate,
                configService,
                new QueuedSecureRandom(
                        0, 0, 1,
                        1, 0, 0,
                        2, 0, 1,
                        3, 0, 1));

        List<PoWChallenge> challenges = List.of(
                service.generateChallenge("admin.login"),
                service.generateChallenge("admin.login"),
                service.generateChallenge("admin.login"),
                service.generateChallenge("admin.login"));

        assertThat(challenges)
                .extracting(PoWChallenge::captchaQuestion)
                .containsExactly("2 + 3 = ?", "3 - 1 = ?", "2 * 3 = ?", "6 / 2 = ?");
        assertThat(challenges).allSatisfy(challenge -> assertThat(challenge.captchaRequired()).isTrue());

        ArgumentCaptor<String> storedChallenge = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(4)).set(anyString(), storedChallenge.capture(), any(Duration.class));

        assertThat(storedChallenge.getAllValues())
                .extracting(value -> value.split("\\|", -1)[3])
                .containsExactly("5", "2", "6", "3");
        for (int index = 0; index < challenges.size(); index++) {
            assertThat(answer(challenges.get(index).captchaQuestion()))
                    .isEqualTo(storedChallenge.getAllValues().get(index).split("\\|", -1)[3]);
        }
    }

    private String answer(String question) {
        Matcher matcher = CAPTCHA_PATTERN.matcher(question);
        assertThat(matcher.matches()).isTrue();
        int left = Integer.parseInt(matcher.group(1));
        String operator = matcher.group(2);
        int right = Integer.parseInt(matcher.group(3));
        return String.valueOf(switch (operator) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
        });
    }

    private static final class QueuedSecureRandom extends SecureRandom {

        private final Queue<Integer> values = new ArrayDeque<>();

        QueuedSecureRandom(int... values) {
            for (int value : values) {
                this.values.add(value);
            }
        }

        @Override
        public int nextInt(int bound) {
            if (values.isEmpty()) {
                return 0;
            }
            return Math.floorMod(values.remove(), bound);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PoWService.class)
    static class PoWServiceConfiguration {
    }
}
