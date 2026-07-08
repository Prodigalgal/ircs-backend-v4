package com.prodigalgal.ircs.identity.domain;


import com.prodigalgal.ircs.identity.api.ApiException;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MemberRecord(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        String email,
        String passwordHash,
        String nickname,
        String avatarUrl,
        String role,
        MemberStatus status,
        boolean adultContentAllowed,
        int experience,
        int points,
        LocalDate lastCheckInDate,
        int checkInStreak) {

    public static MemberRecord newMember(String email, String passwordHash, String nickname, MemberStatus status) {
        Instant now = Instant.now();
        return new MemberRecord(
                IrcsUuidGenerators.nextId(),
                now,
                now,
                0L,
                email,
                passwordHash,
                nickname,
                "https://i.pravatar.cc/300?u=" + email,
                "MEMBER",
                status,
                false,
                0,
                0,
                null,
                0);
    }

    public int level() {
        if (experience <= 0) {
            return 1;
        }
        return (int) Math.sqrt(experience / 100.0) + 1;
    }

    public int nextLevelXp() {
        int nextLevel = level() + 1;
        return (int) Math.pow(nextLevel - 1, 2) * 100;
    }

    public String title() {
        return MemberTitle.byLevel(level());
    }

    public MemberRecord withStatus(MemberStatus nextStatus) {
        return with(statusSafe(nextStatus), passwordHash, nickname, avatarUrl, experience, points, lastCheckInDate,
                checkInStreak);
    }

    public MemberRecord withPasswordHash(String nextPasswordHash) {
        return with(status, nextPasswordHash, nickname, avatarUrl, experience, points, lastCheckInDate, checkInStreak);
    }

    public MemberRecord withNickname(String nextNickname) {
        return with(status, passwordHash, nextNickname, avatarUrl, experience, points, lastCheckInDate, checkInStreak);
    }

    public MemberRecord withAvatarUrl(String nextAvatarUrl) {
        return with(status, passwordHash, nickname, nextAvatarUrl, experience, points, lastCheckInDate, checkInStreak);
    }

    public MemberRecord withAdminFields(
            String nextEmail,
            String nextPasswordHash,
            String nextNickname,
            String nextAvatarUrl,
            String nextRole,
            MemberStatus nextStatus,
            int nextExperience,
            int nextPoints,
            int nextCheckInStreak) {
        return new MemberRecord(
                id,
                createdAt,
                Instant.now(),
                version,
                nextEmail == null ? email : nextEmail,
                nextPasswordHash == null ? passwordHash : nextPasswordHash,
                nextNickname == null ? nickname : nextNickname,
                nextAvatarUrl == null ? avatarUrl : nextAvatarUrl,
                nextRole == null ? role : nextRole,
                statusSafe(nextStatus),
                adultContentAllowed,
                nextExperience,
                nextPoints,
                lastCheckInDate,
                nextCheckInStreak);
    }

    public MemberRecord withAdultContentAllowed(boolean nextAdultContentAllowed) {
        return new MemberRecord(
                id,
                createdAt,
                Instant.now(),
                version,
                email,
                passwordHash,
                nickname,
                avatarUrl,
                role,
                status,
                nextAdultContentAllowed,
                experience,
                points,
                lastCheckInDate,
                checkInStreak);
    }

    public CheckInMutation checkIn(LocalDate today) {
        if (today.equals(lastCheckInDate)) {
            throw ApiException.badRequest("今天已经签到过了", "member", "checkin.duplicate");
        }
        boolean consecutive = lastCheckInDate != null && lastCheckInDate.plusDays(1).equals(today);
        int nextStreak = consecutive ? checkInStreak + 1 : 1;
        int bonus = Math.min((nextStreak - 1) * 5, 50);
        int earnedPoints = 10 + bonus;
        MemberRecord updated = with(
                status,
                passwordHash,
                nickname,
                avatarUrl,
                experience + 20,
                points + earnedPoints,
                today,
                nextStreak);
        return new CheckInMutation(updated, earnedPoints);
    }

    private MemberRecord with(
            MemberStatus nextStatus,
            String nextPasswordHash,
            String nextNickname,
            String nextAvatarUrl,
            int nextExperience,
            int nextPoints,
            LocalDate nextLastCheckInDate,
            int nextCheckInStreak) {
        return new MemberRecord(
                id,
                createdAt,
                Instant.now(),
                version,
                email,
                nextPasswordHash,
                nextNickname,
                nextAvatarUrl,
                role,
                nextStatus,
                adultContentAllowed,
                nextExperience,
                nextPoints,
                nextLastCheckInDate,
                nextCheckInStreak);
    }

    private MemberStatus statusSafe(MemberStatus nextStatus) {
        return nextStatus == null ? status : nextStatus;
    }

    public record CheckInMutation(MemberRecord member, int earnedPoints) {
    }
}
