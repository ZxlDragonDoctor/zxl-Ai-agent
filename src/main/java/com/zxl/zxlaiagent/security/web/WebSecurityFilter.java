package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Formal-mode HTTP boundary. No ThreadLocal identity and no implicit route permission. */
public final class WebSecurityFilter extends OncePerRequestFilter {
    private final AuthenticationService authentication;
    private final AuthorizationService authorization;
    private final Set<String> origins;
    public WebSecurityFilter(AuthenticationService authentication,AuthorizationService authorization,Set<String> origins) {
        this.authentication=authentication;this.authorization=authorization;
        var reviewed=new HashSet<String>();for(String origin:origins)reviewed.add(canonicalOrigin(origin));this.origins=Set.copyOf(reviewed);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        try {
            checkOrigin(request,response);
            String path=EndpointPolicy.servletPath(request.getContextPath(),request.getRequestURI());
            if("OPTIONS".equals(request.getMethod())) {
                String method=request.getHeader("Access-Control-Request-Method");
                if(request.getHeader("Origin")==null||EndpointPolicy.classify(method,path).blocked()) {
                    WebSecurityErrors.write(response,404,"NOT_FOUND");return;
                }
                response.setHeader("Access-Control-Allow-Methods",method);
                response.setHeader("Access-Control-Allow-Headers","Content-Type, X-CSRF-Token");
                response.setStatus(204);return;
            }
            var requirement=EndpointPolicy.classify(request.getMethod(),path);
            if(requirement.blocked()){WebSecurityErrors.write(response,404,"NOT_FOUND");return;}
            if(requirement.publicAccess()) {
                if(requirement.csrf())AuthCookies.requireDoubleSubmit(request);
            } else {
                String credential=AuthCookies.read(request,AuthCookies.SESSION);
                if(credential==null||credential.isBlank())throw new SecurityFailure("UNAUTHENTICATED",401);
                var actor=authentication.authenticate(credential);
                if(actor==null)throw new SecurityFailure("UNAUTHENTICATED",401);
                if(requirement.csrf())authentication.validateCsrf(actor.ref(),request.getHeader("X-CSRF-Token"));
                if(requirement.action()!=null) {
                    var resource=requirement.resourceType()==null?null:new ResourceRef(requirement.resourceType(),requirement.resourceId());
                    var decision=authorization.decide(actor.ref(),requirement.action(),resource,Phase.ENTRY,null);
                    if(!decision.allowed()) {
                        if("RESOURCE_UNAVAILABLE".equals(decision.code()))throw new SecurityFailure("NOT_FOUND",404);
                        decision.requireAllowed();
                    }
                    request.setAttribute(AuthenticatedRequest.DECISION,decision);
                }
                request.setAttribute(AuthenticatedRequest.ACTOR,actor);
            }
            chain.doFilter(request,response);
        } catch(SecurityFailure failure) {
            if(failure.status()==401)AuthCookies.clear(request,response);
            WebSecurityErrors.write(response,failure.status(),failure.code());
        } finally {
            request.removeAttribute(AuthenticatedRequest.ACTOR);request.removeAttribute(AuthenticatedRequest.DECISION);
        }
    }
    private void checkOrigin(HttpServletRequest request,HttpServletResponse response) {
        String origin=request.getHeader("Origin");
        if("cross-site".equals(request.getHeader("Sec-Fetch-Site")))throw new SecurityFailure("ORIGIN_DENIED",403);
        if(origin==null)return; // Non-browser calls still require credentials and mutation CSRF.
        String canonical=canonicalOrigin(origin);
        String own=canonicalOrigin(request.getScheme()+"://"+request.getServerName()+":"+request.getServerPort());
        if(!canonical.equals(own)&&!origins.contains(canonical))throw new SecurityFailure("ORIGIN_DENIED",403);
        response.setHeader("Access-Control-Allow-Origin",origin);response.setHeader("Access-Control-Allow-Credentials","true");response.addHeader("Vary","Origin");
    }
    private static String canonicalOrigin(String raw) {
        try {
            if(raw==null||raw.length()>300)throw new IllegalArgumentException();
            var uri=URI.create(raw);String scheme=uri.getScheme();
            if(!Set.of("http","https").contains(scheme)||uri.getHost()==null||uri.getRawUserInfo()!=null
                    ||!uri.getRawPath().isEmpty()||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();
            int port=uri.getPort()<0?("https".equals(scheme)?443:80):uri.getPort();
            return scheme+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+port;
        }catch(RuntimeException invalid){throw new SecurityFailure("ORIGIN_DENIED",403);}
    }
}
