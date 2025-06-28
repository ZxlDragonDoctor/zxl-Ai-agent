package com.zxl.zxlaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageSplitterToolTest {

    @Test
    void splitAndMergeImage() {
        ImageSplitterTool imageSplitterTool = new ImageSplitterTool();
        String s = imageSplitterTool.splitAndMergeImage("D:\\idea_java_project\\Agent\\zxl-Ai-agent\\tmp\\download\\img.png", "test.png");
        System.out.println(s);
    }
}