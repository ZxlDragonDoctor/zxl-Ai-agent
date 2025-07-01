// ChatMemoryAspect.java
package com.zxl.zxlaiagent.annotation;

import com.zxl.zxlaiagent.chatmemory.FileChatBasedMemory;
import jakarta.annotation.Resource;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;


@Aspect
@Component
public class ChatMemoryAspect {
    @Resource
    private  FileChatBasedMemory chatMemory;

    @Around("@annotation(com.zxl.zxlaiagent.annotation.RecordChat)")
    public Object recordChat(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取参数
        Object[] args = joinPoint.getArgs();
        String message = (String) args[0];
        String chatId = (String) args[1];

        // 保存用户消息
        Message userMessage = new UserMessage(message);
        chatMemory.add(chatId, List.of(userMessage));

        // 执行原方法
        Object result = joinPoint.proceed();

        if (result instanceof String content) {
            // 保存AI回复
            Message assistantMessage = new AssistantMessage(content);
            chatMemory.add(chatId, List.of(assistantMessage));
        }

        return result;
    }
}
