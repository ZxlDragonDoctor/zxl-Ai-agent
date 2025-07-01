package com.zxl.zxlaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ZxlManusTest {
  
    @Resource
    private ZxlManus zxlManus;
  
    @Test
    void run() {  
        String userPrompt = """  
                我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点，  
                并结合一些网络图片，制定一份详细的约会计划，  
                并以 PDF 格式输出""";  
        String answer = zxlManus.run(userPrompt);
        System.out.println(answer);
        Assertions.assertNotNull(answer);
    }

    @Test
    void run1() {
        String userPrompt = """  
                找出一张关于原神的图片""";
        String answer = zxlManus.run(userPrompt);
        System.out.println(answer);
        Assertions.assertNotNull(answer);
    }
}
