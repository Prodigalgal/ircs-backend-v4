package com.prodigalgal.ircs.opsalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OpsAlertInternalAccessFilterTest {

    @Test
    void passesThroughWhenTokenIsNotRequiredAndNotConfigured() throws Exception {
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.stringValue(OpsAlertInternalAccessFilter.TOKEN_KEY, "")).thenReturn("");
        when(runtimeConfig.booleanValue(OpsAlertInternalAccessFilter.REQUIRE_TOKEN_KEY, false)).thenReturn(false);
        OpsAlertInternalAccessFilter filter = new OpsAlertInternalAccessFilter(runtimeConfig);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ops-alert/incidents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CountingFilterChain chain = new CountingFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.count()).isEqualTo(1);
    }

    @Test
    void deniesWriteWhenRunScopeIsMissing() throws Exception {
        RuntimeConfigService runtimeConfig = securedRuntimeConfig();
        OpsAlertInternalAccessFilter filter = new OpsAlertInternalAccessFilter(runtimeConfig);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ops-alert/events");
        request.addHeader(InternalServiceAuthHeaders.SERVICE_ID, "api-gateway");
        request.addHeader(InternalServiceAuthHeaders.SERVICE_TOKEN, "secret");
        request.addHeader(InternalServiceAuthHeaders.SERVICE_SCOPES, "ops-alert:read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CountingFilterChain chain = new CountingFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.count()).isZero();
    }

    @Test
    void allowsReadWithReadScope() throws Exception {
        RuntimeConfigService runtimeConfig = securedRuntimeConfig();
        OpsAlertInternalAccessFilter filter = new OpsAlertInternalAccessFilter(runtimeConfig);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ops-alert/incidents");
        request.addHeader(InternalServiceAuthHeaders.SERVICE_ID, "api-gateway");
        request.addHeader(InternalServiceAuthHeaders.SERVICE_TOKEN, "secret");
        request.addHeader(InternalServiceAuthHeaders.SERVICE_SCOPES, "ops-alert:read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CountingFilterChain chain = new CountingFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.count()).isEqualTo(1);
    }

    private static RuntimeConfigService securedRuntimeConfig() {
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.stringValue(OpsAlertInternalAccessFilter.TOKEN_KEY, "")).thenReturn("secret");
        when(runtimeConfig.booleanValue(OpsAlertInternalAccessFilter.REQUIRE_TOKEN_KEY, false)).thenReturn(true);
        when(runtimeConfig.stringValue(OpsAlertInternalAccessFilter.READ_SCOPE_KEY, "ops-alert:read"))
                .thenReturn("ops-alert:read");
        when(runtimeConfig.stringValue(OpsAlertInternalAccessFilter.RUN_SCOPE_KEY, "ops-alert:run"))
                .thenReturn("ops-alert:run");
        return runtimeConfig;
    }

    private static final class CountingFilterChain implements FilterChain {

        private int count;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            count++;
        }

        int count() {
            return count;
        }
    }
}
