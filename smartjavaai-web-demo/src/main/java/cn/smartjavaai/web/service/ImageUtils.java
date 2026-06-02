package cn.smartjavaai.web.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * 图像处理工具 - 负责把各种来源（multipart 文件、base64 字符串）转为 BufferedImage
 */
@Slf4j
@Service
public class ImageUtils {

    /**
     * 去掉 data URI 前缀
     */
    public static String stripDataUri(String base64) {
        if (base64 != null && base64.contains(",")) {
            return base64.substring(base64.indexOf(",") + 1);
        }
        return base64;
    }

    /**
     * 从 multipart 上传的 InputStream 解码
     */
    public BufferedImage decodeFromStream(InputStream is) throws IOException {
        BufferedImage img = ImageIO.read(is);
        if (img == null) {
            throw new IllegalArgumentException("无法解析图片（不支持的格式？）");
        }
        return img;
    }

    /**
     * 从 base64 字符串解码
     */
    public BufferedImage decodeFromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) {
            throw new IllegalArgumentException("图片数据为空");
        }
        String raw = stripDataUri(base64);
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            return decodeFromStream(new ByteArrayInputStream(bytes));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base64 格式错误: " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("图片读取失败: " + e.getMessage(), e);
        }
    }
}
