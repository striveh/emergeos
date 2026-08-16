package io.emergeos.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the private browser boundary to the Quick Capture page and its resources. */
@Component
final class QuickCapturePageHeadersFilter extends OncePerRequestFilter {

  private static final String PRIVATE_NO_STORE = "private, no-store";
  private static final String CSP_HEADER = "Content-Security-Policy";
  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; base-uri 'none'; object-src 'none';"
          + " frame-ancestors 'none'; form-action 'self'; connect-src 'self';"
          + " img-src 'self'; style-src 'self'; script-src 'self'";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/capture") || path.startsWith("/capture/"));
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
    applyHeaders(response);
    try {
      filterChain.doFilter(request, response);
    } finally {
      applyHeaders(response);
    }
  }

  private static void applyHeaders(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE);
    response.setHeader(CSP_HEADER, CONTENT_SECURITY_POLICY);
  }
}
