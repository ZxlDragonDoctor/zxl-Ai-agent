package com.zxl.zxlaiagent.rag;

import com.zxl.zxlaiagent.rag.loader.LoveAppDocumentLoader;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LoveAppDocumentLoaderTest {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;
    @Test
    void loadDocuments() {
        loveAppDocumentLoader.loadMarkdownDocuments();
    }
}