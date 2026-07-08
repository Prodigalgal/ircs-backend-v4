package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

class MediaRequestCommandServiceTest {

    private final JdbcMediaRequestRepository repository = org.mockito.Mockito.mock(JdbcMediaRequestRepository.class);
    private final MemberPointLedger pointLedger = org.mockito.Mockito.mock(MemberPointLedger.class);
    private final SystemConfigRepository systemConfigRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final MediaRequestCommandService service =
            new MediaRequestCommandService(repository, pointLedger, systemConfigRepository, environment);

    @Test
    void submitsCleanedMediaRequestAndSpendsPoints() {
        UUID memberId = UUID.randomUUID();
        MediaRequestResponse expected = response(memberId, "黑客帝国", 1999, 3);
        when(systemConfigRepository.findValue("member.media-request.daily-limit")).thenReturn(Optional.of("5"));
        when(systemConfigRepository.findValue("member.media-request.point-cost")).thenReturn(Optional.of("3"));
        when(repository.countMemberRequestsCreatedBetween(eq(memberId), any(), any())).thenReturn(0L);
        when(repository.upsert(any(), eq(memberId), any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(expected);

        MediaRequestResponse actual = service.submit(memberId, new MediaRequestSubmitRequest(
                "<b>黑客帝国</b>",
                1999,
                "<script>alert(1)</script>基努&nbsp;&amp;&nbsp;科幻"));

        assertSame(expected, actual);
        verify(pointLedger).spend(memberId, 3, "MEDIA_REQUEST");
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> normalizedCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> extraCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(
                any(),
                eq(memberId),
                titleCaptor.capture(),
                normalizedCaptor.capture(),
                eq(1999),
                extraCaptor.capture(),
                any(),
                eq(3));
        assertEquals("黑客帝国", titleCaptor.getValue());
        assertEquals("黑客帝国", normalizedCaptor.getValue());
        assertEquals("基努 & 科幻", extraCaptor.getValue());
    }

    @Test
    void rejectsWhenDailyLimitIsReachedBeforeSpendingPoints() {
        UUID memberId = UUID.randomUUID();
        when(systemConfigRepository.findValue("member.media-request.daily-limit")).thenReturn(Optional.of("1"));
        when(repository.countMemberRequestsCreatedBetween(eq(memberId), any(), any())).thenReturn(1L);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new MediaRequestSubmitRequest("想看", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("今日求片次数已达上限 (1次)", exception.getMessage());
        verify(pointLedger, never()).spend(any(), anyInt(), any());
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void injectedPointCostOverridesStoredCost() {
        UUID memberId = UUID.randomUUID();
        environment.setProperty("member.media-request.point-cost", "7");
        when(systemConfigRepository.findValue("member.media-request.daily-limit")).thenReturn(Optional.empty());
        when(systemConfigRepository.findValue("member.media-request.point-cost")).thenReturn(Optional.of("3"));
        when(repository.countMemberRequestsCreatedBetween(eq(memberId), any(), any())).thenReturn(0L);
        when(repository.upsert(any(), eq(memberId), any(), any(), any(), any(), any(), eq(7)))
                .thenReturn(response(memberId, "异形", null, 7));

        service.submit(memberId, new MediaRequestSubmitRequest("异形", null, null));

        verify(pointLedger).spend(memberId, 7, "MEDIA_REQUEST");
    }

    @Test
    void rejectsInvalidYear() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(UUID.randomUUID(), new MediaRequestSubmitRequest("太早", 1800, null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("年份范围无效", exception.getMessage());
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    private MediaRequestResponse response(UUID memberId, String title, Integer releaseYear, int spentPoints) {
        Instant now = Instant.parse("2026-06-30T00:00:00Z");
        return new MediaRequestResponse(
                UUID.randomUUID(),
                memberId,
                title,
                releaseYear,
                null,
                "PENDING",
                1,
                now,
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                spentPoints);
    }
}
