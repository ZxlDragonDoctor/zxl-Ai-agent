package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.SecurityFailure;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseCookie;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.Base64;

final class AuthCookies {
    static final String SESSION="ZXL_SESSION", CSRF="ZXL_CSRF";
    private static final SecureRandom RANDOM=new SecureRandom();
    private AuthCookies() {}
    static String read(HttpServletRequest request,String name) {
        String value=null;
        if (request.getCookies()!=null) for(var cookie:request.getCookies()) if(name.equals(cookie.getName())) {
            if(value!=null)throw new SecurityFailure("UNAUTHENTICATED",401);
            value=cookie.getValue();
        }
        return value;
    }
    static String random() {byte[] raw=new byte[32];RANDOM.nextBytes(raw);return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);}
    static boolean validToken(String token) {return token!=null && token.matches("[A-Za-z0-9_-]{43}");}
    static void requireDoubleSubmit(HttpServletRequest request) {
        String cookie=read(request,CSRF),header=request.getHeader("X-CSRF-Token");
        if(!validToken(cookie)||!validToken(header)||!MessageDigest.isEqual(cookie.getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII)))
            throw new SecurityFailure("CSRF_INVALID",403);
    }
    static void set(HttpServletRequest request,HttpServletResponse response,String name,String value,Duration lifetime) {
        String path=request.getContextPath().isEmpty()?"/":request.getContextPath();
        response.addHeader("Set-Cookie",ResponseCookie.from(name,value).httpOnly(true).secure(request.isSecure())
                .sameSite("Lax").path(path).maxAge(lifetime).build().toString());
    }
    static void clear(HttpServletRequest request,HttpServletResponse response) {
        set(request,response,SESSION,"",Duration.ZERO);set(request,response,CSRF,"",Duration.ZERO);
    }
}
