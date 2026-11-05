package org.example.bankcashwithdrawalapp.rest;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLoggingFilterTest {
    @Test
    void assignsResponseCorrelationIdAndClearsMdcAfterRequest() {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bank/withdraw");
        request.setQueryString("accountId=101&amount=25.50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertNotNull(MDC.get("requestId"));
            assertTrue(UUID.fromString(MDC.get("requestId")) != null);
        }));

        assertNotNull(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get("requestId"));
    }
}
