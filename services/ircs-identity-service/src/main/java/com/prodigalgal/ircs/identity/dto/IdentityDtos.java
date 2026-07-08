package com.prodigalgal.ircs.identity.dto;


import com.prodigalgal.ircs.identity.domain.MemberStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record PoWChallenge(
            String id,
            String nonce,
            Integer difficulty,
            String algorithm,
            String scope,
            Instant expiresAt,
            boolean captchaRequired,
            String captchaQuestion) {

        public PoWChallenge(String id, Integer difficulty) {
            this(id, null, difficulty, "SHA-256", "auth", null, false, null);
        }
    }

    public record PoWVerification(
            @NotBlank(message = "Challenge ID cannot be empty") String id,
            @NotBlank(message = "Nonce cannot be empty") String nonce) {
    }

    public record MemberRegisterRequest(
            @Email(message = "邮箱格式不正确") @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度必须在 6-32 位之间")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码强度太弱：必须包含至少一个字母和一个数字")
            String password,
            @NotBlank(message = "昵称不能为空") @Size(max = 50, message = "昵称过长") String nickname,
            @Valid PoWVerification powVerification) {
    }

    public record MemberLoginRequest(
            @Email(message = "邮箱格式不正确") @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "密码不能为空") String password,
            @Valid PoWVerification powVerification) {
    }

    public record AdminLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            @Valid PoWVerification powVerification) {
    }

    public record AdminPasswordChangeRequest(
            @NotBlank(message = "旧密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    public record AdminTokenResponse(String token) {
    }

    public record AccountActivateRequest(
            @Email(message = "邮箱格式不正确") @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "激活码不能为空") String code) {
    }

    public record ResendCodeRequest(
            @Email(message = "邮箱格式不正确") @NotBlank(message = "邮箱不能为空") String email) {
    }

    public record ForgotPasswordRequest(
            @Email(message = "邮箱格式不正确") @NotBlank(message = "邮箱不能为空") String email,
            @NotBlank(message = "昵称不能为空") String nickname,
            @Valid PoWVerification powVerification) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "令牌不能为空") String token,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度必须在 6-32 位之间")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码强度太弱：必须包含至少一个字母和一个数字")
            String newPassword) {
    }

    public record ProfileUpdateRequest(
            @Size(min = 2, max = 50, message = "昵称长度必须在 2-50 字符之间") String nickname) {
    }

    public record PasswordChangeRequest(
            @NotBlank(message = "旧密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度必须在 6-32 位之间")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码强度太弱：必须包含至少一个字母和一个数字")
            String newPassword) {
    }

    public record MemberTokenResponse(
            String token,
            String id,
            String email,
            String nickname,
            String avatarUrl,
            String role,
            String status,
            boolean adultContentAllowed,
            int experience,
            int points,
            int level,
            int nextLevelXp,
            int checkInStreak,
            String title,
            LocalDate lastCheckInDate) {
    }

    public record MemberProfileResponse(
            UUID id,
            String email,
            String nickname,
            String avatarUrl,
            String role,
            MemberStatus status,
            boolean adultContentAllowed,
            int experience,
            int points,
            int level,
            int nextLevelXp,
            LocalDate lastCheckInDate,
            int checkInStreak,
            String title,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AvatarUploadResponse(String url) {
    }

    public record MemberAdminUpdateRequest(
            @Email(message = "邮箱格式不正确") String email,
            String nickname,
            String avatarUrl,
            String password,
            String role,
            MemberStatus status,
            Boolean adultContentAllowed,
            @Min(value = 0, message = "经验值不能为负数") Integer experience,
            @Min(value = 0, message = "积分不能为负数") Integer points,
            @Min(value = 0, message = "签到连续天数不能为负数") Integer checkInStreak) {
    }

    public record MemberStatusUpdateRequest(
            @NotNull(message = "状态不能为空") MemberStatus status) {
    }

    public record MemberAdminResponse(
            UUID id,
            String email,
            String nickname,
            String avatarUrl,
            String role,
            MemberStatus status,
            boolean adultContentAllowed,
            int experience,
            int points,
            int level,
            int nextLevelXp,
            LocalDate lastCheckInDate,
            int checkInStreak,
            String title,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CheckInResult(
            int earnedPoints,
            int currentPoints,
            int checkInStreak,
            int experience,
            int level) {
    }
}
