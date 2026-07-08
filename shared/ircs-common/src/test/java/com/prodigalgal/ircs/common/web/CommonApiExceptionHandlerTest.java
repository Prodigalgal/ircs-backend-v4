package com.prodigalgal.ircs.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class CommonApiExceptionHandlerTest {

    private final CommonApiExceptionHandler handler = new CommonApiExceptionHandler();

    @Test
    void mapsUnknownMvcResourcesToUniformNotFoundEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard");
        request.addHeader(ApiErrorResponses.TRACE_HEADER, "trace-404");
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, "/api/v1/dashboard");

        var response = handler.handleNotFound(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("http.404");
        assertThat(response.getBody().message()).isEqualTo("Resource not found");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/dashboard");
        assertThat(response.getBody().traceId()).isEqualTo("trace-404");
    }

    @Test
    void declaresJsonContentTypeForUnexpectedErrorEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

        var response = handler.handleUnexpected(new IllegalStateException("metrics write failed"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("internal.error");
    }

    @Test
    void mapsMissingRequestParameterToBadRequestEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/credentials/templates");
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("provider", "String");

        var response = handler.handleMissingRequestParameter(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("request.parameter.missing");
        assertThat(response.getBody().message()).isEqualTo("Missing required request parameter: provider");
        assertThat(response.getBody().details()).containsEntry("parameter", "provider");
    }

    @Test
    void treatsClientDisconnectAsEmptyClientClosedResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        AsyncRequestNotUsableException exception = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write: java.io.IOException: Broken pipe");

        var response = handler.handleUnexpected(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(499);
        assertThat(response.getHeaders().getContentType()).isNull();
        assertThat(response.getBody()).isNull();
    }

    @Test
    void writeOverridesPreexistingOpenMetricsContentType() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("application/openmetrics-text;version=1.0.0;charset=utf-8");

        ApiErrorResponses.write(
                request,
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal.error",
                "Internal server error",
                "system");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"internal.error\"");
    }
}
