package com.prodigalgal.ircs.identity.application;





import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.IdentityRedisKeys;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberStatusCacheService {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final MemberRepository memberRepository;

    public MemberStatus getStatus(UUID memberId) {
        String key = IdentityRedisKeys.authStatus(memberId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            try {
                return MemberStatus.valueOf(cached);
            } catch (IllegalArgumentException ignored) {
                redisTemplate.delete(key);
            }
        }
        return memberRepository.findById(memberId)
                .map(MemberRecord::status)
                .map(status -> {
                    updateStatus(memberId, status);
                    return status;
                })
                .orElse(null);
    }

    public void updateStatus(UUID memberId, MemberStatus status) {
        if (memberId == null || status == null) {
            return;
        }
        redisTemplate.opsForValue().set(IdentityRedisKeys.authStatus(memberId), status.name(), TTL);
    }

    public void evict(UUID memberId) {
        if (memberId != null) {
            redisTemplate.delete(IdentityRedisKeys.authStatus(memberId));
        }
    }
}
