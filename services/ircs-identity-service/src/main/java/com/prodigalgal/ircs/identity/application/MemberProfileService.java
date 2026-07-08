package com.prodigalgal.ircs.identity.application;








import com.prodigalgal.ircs.identity.domain.IdentityConfigKey;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient;
import com.prodigalgal.ircs.identity.repository.MemberRepository;
import com.prodigalgal.ircs.identity.messaging.AvatarSyncPublisher;
import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.identity.domain.MemberRecord;
import com.prodigalgal.ircs.identity.domain.MemberStatus;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.AvatarUploadResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.CheckInResult;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.MemberProfileResponse;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.PasswordChangeRequest;
import com.prodigalgal.ircs.identity.dto.IdentityDtos.ProfileUpdateRequest;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient.AvatarFile;
import com.prodigalgal.ircs.identity.infrastructure.AvatarStorageClient.StoredAvatar;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MemberProfileService {

    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024;
    private static final String AVATAR_PUBLIC_PREFIX = "/media/avatars";
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp");
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityConfigService configService;
    private final MemberResponseMapper mapper;
    private final AvatarStorageClient avatarStorageClient;
    private final AvatarSyncPublisher avatarSyncPublisher;

    @Transactional(readOnly = true)
    public MemberProfileResponse getProfile(UUID memberId) {
        MemberRecord member = requireUsableMember(memberId);
        return mapper.toProfile(member);
    }

    @Transactional
    public MemberProfileResponse updateProfile(UUID memberId, ProfileUpdateRequest request) {
        MemberRecord member = requireUsableMember(memberId);
        MemberRecord updated = member;
        if (request != null && StringUtils.hasText(request.nickname())) {
            String nickname = request.nickname().replaceAll("<[^>]*>", "").trim();
            if (nickname.length() < 2) {
                throw ApiException.badRequest("昵称包含非法字符", "member", "nickname.invalid");
            }
            updated = member.withNickname(nickname);
            memberRepository.update(updated);
        }
        return mapper.toProfile(updated);
    }

    @Transactional
    public void changePassword(UUID memberId, PasswordChangeRequest request) {
        MemberRecord member = requireUsableMember(memberId);
        if (!passwordEncoder.matches(request.oldPassword(), member.passwordHash())) {
            throw ApiException.badRequest("旧密码错误", "member", "password.mismatch");
        }
        if (request.newPassword().equals(request.oldPassword())) {
            throw ApiException.badRequest("新密码不能与旧密码相同", "member", "password.same");
        }
        memberRepository.update(member.withPasswordHash(passwordEncoder.encode(request.newPassword())));
    }

    @Transactional
    public AvatarUploadResponse uploadAvatar(UUID memberId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("文件为空", "avatar", "file.empty");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw ApiException.badRequest("头像文件不能超过 2MB", "avatar", "file.size.limit");
        }

        MemberRecord member = requireUsableMember(memberId);
        byte[] data = avatarBytes(file);
        String contentType = normalizedContentType(file.getContentType());
        validateContentType(contentType, data);

        StoredAvatar stored = avatarStorageClient.store(new AvatarFile(file.getOriginalFilename(), contentType, data));
        validateStorageResponse(stored);
        memberRepository.update(member.withAvatarUrl(stored.url()));
        avatarSyncPublisher.publish(memberId);
        return new AvatarUploadResponse(stored.url());
    }

    @Transactional
    public CheckInResult checkIn(UUID memberId) {
        MemberRecord member = requireUsableMember(memberId);
        String timezone = configService.value(IdentityConfigKey.MEMBER_REGISTER_TIMEZONE);
        MemberRecord.CheckInMutation mutation = member.checkIn(LocalDate.now(ZoneId.of(timezone)));
        memberRepository.update(mutation.member());
        return new CheckInResult(
                mutation.earnedPoints(),
                mutation.member().points(),
                mutation.member().checkInStreak(),
                mutation.member().experience(),
                mutation.member().level());
    }

    private MemberRecord requireUsableMember(UUID memberId) {
        MemberRecord member = memberRepository.findById(memberId)
                .orElseThrow(() -> ApiException.notFound("用户不存在", "member", "not.found"));
        if (member.status() == MemberStatus.BANNED) {
            throw ApiException.forbidden("账号已被封禁", "member", "auth.banned");
        }
        return member;
    }

    private byte[] avatarBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "文件上传失败", "avatar", "read.failed");
        }
    }

    private String normalizedContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType.toLowerCase(Locale.ROOT) : "";
    }

    private void validateContentType(String contentType, byte[] data) {
        if (!ALLOWED_AVATAR_TYPES.contains(contentType) || !matchesImageSignature(contentType, data)) {
            throw ApiException.badRequest("文件校验失败: 不支持的图片类型", "avatar", "content.type.invalid");
        }
    }

    private boolean matchesImageSignature(String contentType, byte[] data) {
        return switch (contentType) {
            case "image/jpeg" -> data.length >= 3
                    && (data[0] & 0xFF) == 0xFF
                    && (data[1] & 0xFF) == 0xD8
                    && (data[2] & 0xFF) == 0xFF;
            case "image/png" -> data.length >= 8
                    && (data[0] & 0xFF) == 0x89
                    && data[1] == 0x50
                    && data[2] == 0x4E
                    && data[3] == 0x47
                    && data[4] == 0x0D
                    && data[5] == 0x0A
                    && data[6] == 0x1A
                    && data[7] == 0x0A;
            case "image/gif" -> data.length >= 6
                    && data[0] == 0x47
                    && data[1] == 0x49
                    && data[2] == 0x46
                    && data[3] == 0x38
                    && (data[4] == 0x37 || data[4] == 0x39)
                    && data[5] == 0x61;
            case "image/webp" -> data.length >= 12
                    && data[0] == 0x52
                    && data[1] == 0x49
                    && data[2] == 0x46
                    && data[3] == 0x46
                    && data[8] == 0x57
                    && data[9] == 0x45
                    && data[10] == 0x42
                    && data[11] == 0x50;
            default -> false;
        };
    }

    private void validateStorageResponse(StoredAvatar stored) {
        if (stored == null || !StringUtils.hasText(stored.url()) || !stored.url().startsWith(AVATAR_PUBLIC_PREFIX + "/")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "头像存储服务返回无效", "avatar", "storage.response.invalid");
        }
    }
}
