package com.zxl.zxlaiagent;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
public class ZxlAiAgentApplication {

    public static void main(String[] args) {

//        ApplicationContext context = SpringApplication.run(ZxlAiAgentApplication.class, args);
//        String[] beanDefinitionNames = context.getBeanDefinitionNames();
//        for (int i = 0; i < beanDefinitionNames.length; i++) {
//            System.out.println("BeanName:"+ beanDefinitionNames[i]);
//        }
        SpringApplication.run(ZxlAiAgentApplication.class, args);
    }

}
