package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;

class FeedbackCommandServiceTest {

    private final JdbcFeedbackRepository repository = org.mockito.Mockito.mock(JdbcFeedbackRepository.class);
    private final MemberPointLedger pointLedger = org.mockito.Mockito.mock(MemberPointLedger.class);
    private final SystemConfigRepository systemConfigRepository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final FeedbackCommandService service =
            new FeedbackCommandService(repository, pointLedger, systemConfigRepository, environment);

    @Test
    void submitsCleanedContentWithinDailyLimit() {
        UUID memberId = UUID.randomUUID();
        UserMessageResponse expected = message(memberId, "可以 & 继续看");
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.of("5"));
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(0L);
        when(repository.insertMessage(any(), eq(memberId), any(), any())).thenReturn(expected);

        UserMessageResponse response = service.submit(
                memberId,
                new FeedbackSubmitRequest(" <script>alert(1)</script><b>可以</b>&nbsp;&amp;&nbsp;继续看 "));

        assertSame(expected, response);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insertMessage(any(), eq(memberId), contentCaptor.capture(), any());
        assertEquals("可以 & 继续看", contentCaptor.getValue());
        verify(pointLedger).spend(memberId, 1, "MESSAGE");
    }

    @Test
    void rejectsBlankContentAfterHtmlCleaning() {
        UUID memberId = UUID.randomUUID();
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.empty());
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(0L);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new FeedbackSubmitRequest("&lt;b&gt;&lt;/b&gt;")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("留言内容无效或包含非法字符", exception.getMessage());
        verify(repository, never()).insertMessage(any(), eq(memberId), any(), any());
    }

    @Test
    void rejectsContentLongerThanFiveHundredCharactersBeforeLimitCheck() {
        UUID memberId = UUID.randomUUID();

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new FeedbackSubmitRequest("a".repeat(501))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("留言内容过长 (最多500字)", exception.getMessage());
        verifyNoInteractions(repository, pointLedger, systemConfigRepository);
    }

    @Test
    void usesDefaultDailyLimitWhenConfigMissing() {
        UUID memberId = UUID.randomUUID();
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.empty());
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(5L);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new FeedbackSubmitRequest("今日已满")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertTrue(exception.getMessage().contains("5次"));
        verify(repository, never()).insertMessage(any(), eq(memberId), any(), any());
    }

    @Test
    void injectedDailyLimitOverridesStoredConfig() {
        UUID memberId = UUID.randomUUID();
        environment.setProperty("member.message.daily-limit", "1");
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.of("5"));
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(1L);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new FeedbackSubmitRequest("今日已满")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertTrue(exception.getMessage().contains("1次"));
        verify(repository, never()).insertMessage(any(), eq(memberId), any(), any());
    }

    @Test
    void k8sDailyLimitOverridesStoredConfig() {
        UUID memberId = UUID.randomUUID();
        environment.setProperty("MEMBER_MESSAGE_DAILY_LIMIT", "1");
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.of("5"));
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(1L);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new FeedbackSubmitRequest("今日已满")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertTrue(exception.getMessage().contains("1次"));
        verify(repository, never()).insertMessage(any(), eq(memberId), any(), any());
    }

    @Test
    void configMapDailyLimitOverridesStoredConfig() {
        UUID memberId = UUID.randomUUID();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Kubernetes ConfigMap ircs-dev/runtime",
                java.util.Map.of("member.message.daily-limit", "1")));
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.of("5"));
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(1L);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.submit(memberId, new FeedbackSubmitRequest("今日已满")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertTrue(exception.getMessage().contains("1次"));
        verify(repository, never()).insertMessage(any(), eq(memberId), any(), any());
    }

    @Test
    void applicationConfigDefaultDoesNotOverrideStoredDailyLimit() {
        UUID memberId = UUID.randomUUID();
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
                java.util.Map.of("member.message.daily-limit", "1")));
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.of("5"));
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(4L);
        UserMessageResponse expected = message(memberId, "还能留言");
        when(repository.insertMessage(any(), eq(memberId), any(), any())).thenReturn(expected);

        UserMessageResponse response = service.submit(memberId, new FeedbackSubmitRequest("还能留言"));

        assertSame(expected, response);
    }

    @Test
    void storedPointCostOverridesDefaultCost() {
        UUID memberId = UUID.randomUUID();
        when(systemConfigRepository.findValue("member.message.daily-limit")).thenReturn(Optional.of("5"));
        when(systemConfigRepository.findValue("member.message.point-cost")).thenReturn(Optional.of("2"));
        when(repository.countMemberMessagesCreatedBetween(eq(memberId), any(), any())).thenReturn(0L);
        UserMessageResponse expected = message(memberId, "扣积分");
        when(repository.insertMessage(any(), eq(memberId), any(), any())).thenReturn(expected);

        service.submit(memberId, new FeedbackSubmitRequest("扣积分"));

        verify(pointLedger).spend(memberId, 2, "MESSAGE");
    }

    @Test
    void deletesByMemberAndMessageIdAsNoOpBoundary() {
        UUID memberId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        service.delete(memberId, messageId);

        verify(repository).deleteMemberMessage(memberId, messageId);
    }

    @Test
    void rejectsNullMessageIdOnDelete() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> service.delete(UUID.randomUUID(), null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("留言标识不能为空", exception.getMessage());
        verifyNoInteractions(repository);
    }

    private UserMessageResponse message(UUID memberId, String content) {
        return new UserMessageResponse(
                UUID.randomUUID(),
                memberId,
                "画外用户",
                "member@example.invalid",
                "https://example.invalid/avatar.png",
                content,
                null,
                "PENDING",
                false,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));
    }
}
