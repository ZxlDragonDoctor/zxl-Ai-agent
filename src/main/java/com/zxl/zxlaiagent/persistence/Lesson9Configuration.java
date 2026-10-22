package com.zxl.zxlaiagent.persistence;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@Profile("lesson9-demo")
public class Lesson9Configuration {
    @Bean public LessonDatabase lesson9Database(Environment env) {
        return new LessonDatabase(env.getProperty("lesson9.jdbc.url","jdbc:h2:file:./runtime-data/lesson9/workflow"),
            env.getProperty("lesson9.jdbc.user","sa"),env.getProperty("lesson9.jdbc.password",""));
    }
    @Bean public PersistentWorkflowRepository persistentWorkflowRepository(LessonDatabase db){return new PersistentWorkflowRepository(db);}
    @Bean(destroyMethod="close") @Lazy(false) public PersistentWorker persistentWorker(PersistentWorkflowRepository repository,Environment env) {
        return new PersistentWorker(repository,env.getProperty("lesson9.worker.enabled",Boolean.class,true));
    }
}
