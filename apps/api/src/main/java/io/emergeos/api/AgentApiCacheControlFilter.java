package io.emergeos.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the private Agent API cache contract before MVC can reject a request. */
@Component
final class AgentApiCacheControlFilter extends OncePerRequestFilter {

  private static final String PRIVATE_NO_STORE = "private, no-store";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/api/v1/agent-drafts")
        || path.startsWith("/api/v1/agent-drafts/")
        || path.equals("/api/v1/agent-runs")
        || path.startsWith("/api/v1/agent-runs/"));
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE);
    try {
      filterChain.doFilter(request, response);
    } finally {
      response.setHeader(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE);
    }
  }
}
