package com.zxl.zxlaiagent.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

final class WebSecurityErrors {
    private static final ObjectMapper JSON=new ObjectMapper();
    private WebSecurityErrors() {}
    static void write(HttpServletResponse response,int status,String code)throws IOException {
        if(response.isCommitted())return;
        String safe=code!=null&&code.matches("[A-Z_]{1,60}")?code:"REQUEST_FAILED";
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");
        String message=switch(status){case 401->"请重新登录。";case 403->"当前操作未获允许。";case 404->"资源不可用。";case 503->"认证服务暂不可用。";default->"请求未完成。";};
        JSON.writeValue(response.getOutputStream(),Map.of("code",safe,"message",message));
    }
}
