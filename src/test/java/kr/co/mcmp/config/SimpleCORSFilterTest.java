package kr.co.mcmp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class SimpleCORSFilterTest {

    private final SimpleCORSFilter filter = new SimpleCORSFilter();

    @Test
    void allowsProjectContextHeadersDuringCorsPreflight() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/applications/status/groups");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Headers"))
                .contains(
                        "authorization",
                        "X-MCMP-Workspace-ID",
                        "X-MCMP-Project-ID",
                        "X-MCMP-Namespace-ID");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void continuesNormalRequestsAfterAddingCorsHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/applications/status/groups");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
