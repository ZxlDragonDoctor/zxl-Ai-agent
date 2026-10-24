package com.zxl.zxlaiagent.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeProfileTest {
    @Test void defaultApplicationScanCannotCreateDatabaseOrTeachingController(){
        var configuration=Lesson12Demo.DemoConfiguration.class.getAnnotation(Profile.class);
        var controller=KnowledgeController.class.getAnnotation(Profile.class);
        assertNotNull(configuration,"Demo configuration must be isolated from normal application component scan");
        assertNotNull(controller,"Demo controller must be isolated from normal application component scan");
        assertArrayEquals(new String[]{"lesson12-demo"},configuration.value());
        assertArrayEquals(new String[]{"lesson12-demo"},controller.value());
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.scan("com.zxl.zxlaiagent.knowledge");context.refresh();
            assertTrue(context.getBeansOfType(KnowledgeDatabase.class).isEmpty());
            assertTrue(context.getBeansOfType(KnowledgeController.class).isEmpty());
        }
    }
}
