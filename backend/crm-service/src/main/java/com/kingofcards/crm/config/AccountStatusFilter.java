package com.kingofcards.crm.config;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccountStatusFilter extends OncePerRequestFilter {
    private final AccountStatusVerifier verifier;
    public AccountStatusFilter(AccountStatusVerifier verifier){this.verifier=verifier;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=request.getRequestURI().substring(request.getContextPath().length());return path.equals("/health") || path.equals("/actuator/health") || path.startsWith("/actuator/health/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth instanceof JwtAuthenticationToken jwt && auth.isAuthenticated()) {
            try{verifier.verify(jwt.getToken());}
            catch(AccessDeniedException denied){problem(response,403,"ACCOUNT_ACCESS_REVOKED");return;}
            catch(RuntimeException unavailable){problem(response,503,"IDENTITY_ACCOUNT_CHECK_UNAVAILABLE");return;}
        }
        chain.doFilter(request,response);
    }
    private static void problem(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"status\":"+status+",\"code\":\""+code+"\"}");
    }
}
