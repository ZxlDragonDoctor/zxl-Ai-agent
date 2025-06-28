package com.zxl.zxlaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.zxl.zxlaiagent.contstant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;


/**
 * @Title: ImageSplitterTool
 * @Description: 将指定图片按照高度进行分割进行三列并排处理
 * @Author:  zxlDragonDoctor
 * @Date: 2025/6/28
 * @Version: 1.0
 */
public class ImageSplitterTool {


    /**
     * 将输入图片分割成三列，并生成一张三列并排的新图片
     *
     * @param inputImagePath  输入图片路径
     * @param filename 输出图片保存名称
     */
    @Tool(description = "Divide the specified image into three columns side by side", returnDirect = true)
    public String splitAndMergeImage(@ToolParam(description = "Input image path") String inputImagePath,@ToolParam(description = "The name of the file that was saved after the replacement") String filename) {
        try {
            String fileDir = FileConstant.FILE_SAVE_DIR + "/imageSpilter";
            String outputImagePath = fileDir + "/" + filename;
            FileUtil.mkdir(fileDir); //创建目录
            File inputFile = new File(inputImagePath);
            BufferedImage originalImage = ImageIO.read(inputFile);

            int width = originalImage.getWidth();
            int height = originalImage.getHeight();

            // 每一段的高度
            int segmentHeight = height / 3;

            // 分割图像为三段
            BufferedImage top = originalImage.getSubimage(0, 0, width, segmentHeight);
            BufferedImage center = originalImage.getSubimage(0, segmentHeight, width, segmentHeight);
            BufferedImage bottom = originalImage.getSubimage(0, 2 * segmentHeight, width, height - 2 * segmentHeight);

            // 创建新的合并图像（宽度不变，高度为原高度的1/3）
            BufferedImage mergedImage = new BufferedImage(width * 3, segmentHeight, originalImage.getType());

            // 绘制三个部分到新图像中
            mergedImage.createGraphics().drawImage(top, 0, 0, null);
            mergedImage.createGraphics().drawImage(center, width, 0, null);
            mergedImage.createGraphics().drawImage(bottom, 2 * width, 0, null);

            // 保存结果
            File outputFile = new File(outputImagePath);
            ImageIO.write(mergedImage, "png", outputFile);

            return outputFile.getAbsolutePath();

        } catch (IOException e) {
            throw new RuntimeException("图片处理失败：" + e.getMessage(), e);
        }
    }
}
