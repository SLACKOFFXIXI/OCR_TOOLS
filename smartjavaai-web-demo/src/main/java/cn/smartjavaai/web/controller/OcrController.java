package cn.smartjavaai.web.controller;

import cn.smartjavaai.web.service.ImageUtils;
import cn.smartjavaai.web.service.OcrService;
import cn.smartjavaai.web.service.OcrService.BBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR REST API
 *
 * 端点：
 * - POST /api/ocr/recognize        通用文字识别（上传文件）
 * - POST /api/ocr/recognize-base64 通用文字识别（base64）
 * - POST /api/ocr/positions        文字+位置
 * - POST /api/ocr/idcard           身份证识别
 * - POST /api/ocr/plate            车牌识别
 * - GET  /api/health              健康检查
 */
@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")  // 开发阶段允许跨域
public class OcrController {

    @Autowired
    private OcrService ocrService;

    @Autowired
    private ImageUtils imageUtils;

    /**
     * 健康检查 - 同时返回 SmartJavaAI 依赖状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("smartjavaai_available", ocrService.isAvailable());
        result.put("version", "0.1.0");
        return result;
    }

    /**
     * 通用文字识别（multipart 上传）
     */
    @PostMapping(value = "/ocr/recognize", consumes = "multipart/form-data")
    public Map<String, Object> recognizeFile(@RequestParam("file") MultipartFile file) {
        long start = System.currentTimeMillis();
        try {
            BufferedImage img = imageUtils.decodeFromStream(file.getInputStream());
            List<OcrService.OcrItem> items = ocrService.recognizeCommon(img);
            String text = items.stream()
                .map(OcrService.OcrItem::getText)
                .filter(t -> t != null && !t.isEmpty())
                .reduce((a, b) -> a + "\n" + b).orElse("");
            float avgScore = (float) items.stream()
                .mapToDouble(OcrService.OcrItem::getScore)
                .average().orElse(0.0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("text", text);
            result.put("line_count", text.isEmpty() ? 0 : text.split("\n").length);
            result.put("image_size", img.getWidth() + "x" + img.getHeight());
            result.put("avg_confidence", avgScore);
            result.put("elapsed_ms", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("recognize 失败", e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * 通用文字识别（base64 - 方便前端 fetch 提交）
     */
    @PostMapping(value = "/ocr/recognize-base64")
    public Map<String, Object> recognizeBase64(@RequestBody Map<String, String> req) {
        long start = System.currentTimeMillis();
        try {
            String base64 = req.get("image");
            BufferedImage img = imageUtils.decodeFromBase64(base64);
            String text = ocrService.extractText(img);
            Float avgConfidence = ocrService.recognizeCommon(img).stream()
                .map(OcrService.OcrItem::getScore)
                .reduce(0f, Float::sum) / Math.max(1, ocrService.recognizeCommon(img).size());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("text", text);
            result.put("line_count", text.isEmpty() ? 0 : text.split("\n").length);
            result.put("image_size", img.getWidth() + "x" + img.getHeight());
            result.put("avg_confidence", avgConfidence);
            result.put("elapsed_ms", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("recognize 失败", e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * 文字识别 + 位置 + 置信度
     */
    @PostMapping(value = "/ocr/positions", consumes = "multipart/form-data")
    public Map<String, Object> recognizeWithPositions(@RequestParam("file") MultipartFile file) {
        long start = System.currentTimeMillis();
        try {
            BufferedImage img = imageUtils.decodeFromStream(file.getInputStream());
            List<OcrService.OcrItem> items = ocrService.recognizeCommon(img);

            java.util.List<Map<String, Object>> itemList = new java.util.ArrayList<>();
            for (OcrService.OcrItem item : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", item.getText() != null ? item.getText() : "");
                m.put("score", item.getScore());
                if (item.getBox() != null) {
                    BBox b = item.getBox();
                    m.put("bbox", Map.of(
                        "x1", b.getX1(), "y1", b.getY1(),
                        "x2", b.getX2(), "y2", b.getY2()
                    ));
                }
                itemList.add(m);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("items", itemList);
            result.put("line_count", items.size());
            result.put("image_size", img.getWidth() + "x" + img.getHeight());
            result.put("elapsed_ms", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("positions 失败", e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * 身份证识别
     */
    @PostMapping(value = "/ocr/idcard", consumes = "multipart/form-data")
    public Map<String, Object> recognizeIdCard(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "side", defaultValue = "front") String side) {
        long start = System.currentTimeMillis();
        try {
            BufferedImage img = imageUtils.decodeFromStream(file.getInputStream());
            Map<String, Object> data = ocrService.recognizeIdCard(img, side);
            data.put("success", true);
            data.put("image_size", img.getWidth() + "x" + img.getHeight());
            data.put("elapsed_ms", System.currentTimeMillis() - start);
            return data;
        } catch (Exception e) {
            log.error("idcard 失败", e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * 车牌识别（严格校验，未识别到则返回 detected=false）
     */
    @PostMapping(value = "/ocr/plate", consumes = "multipart/form-data")
    public Map<String, Object> recognizePlate(@RequestParam("file") MultipartFile file) {
        long start = System.currentTimeMillis();
        try {
            BufferedImage img = imageUtils.decodeFromStream(file.getInputStream());
            Map<String, Object> data = ocrService.recognizePlate(img);
            data.put("success", true);
            data.put("image_size", img.getWidth() + "x" + img.getHeight());
            data.put("elapsed_ms", System.currentTimeMillis() - start);
            return data;
        } catch (Exception e) {
            log.error("plate 失败", e);
            return errorResult(e.getMessage());
        }
    }

    private Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
