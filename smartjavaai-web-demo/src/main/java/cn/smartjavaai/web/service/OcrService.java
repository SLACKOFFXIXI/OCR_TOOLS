package cn.smartjavaai.web.service;

import cn.smartjavaai.ocr.config.OcrDetModelConfig;
import cn.smartjavaai.ocr.entity.OcrItem;
import cn.smartjavaai.ocr.enums.CommonDetModelEnum;
import cn.smartjavaai.ocr.factory.OcrModelFactory;
import cn.smartjavaai.ocr.model.common.detect.OcrCommonDetModel;
import cn.smartjavaai.ocr.model.common.recognize.OcrCommonRecModel;
import cn.smartjavaai.ocr.config.OcrRecModelConfig;
import cn.smartjavaai.ocr.enums.CommonRecModelEnum;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * OCR 服务 v3 - 反射调用 SmartJavaAI，但直接用 DJL 加载 .onnx 文件
 *
 * 关键改进：
 * - 强制 ONNX 引擎
 * - 简化模型路径处理
 * - 友好的错误信息
 */
@Slf4j
@Service
public class OcrService {

    private final Map<String, Object> modelCache = new ConcurrentHashMap<>();
    private volatile boolean smartJavaAiAvailable = false;
    private volatile boolean smartJavaAiChecked = false;

    public OcrService() {
        // 强制 ONNX 引擎 - 必须在 SmartJavaAI 静态块之前
        System.setProperty("ai.djl.default_engine", "OnnxRuntime");
        try {
            checkSmartJavaAi();
        } catch (Exception e) {
            log.warn("checkSmartJavaAi 失败", e);
        }
    }

    private void checkSmartJavaAi() throws Exception {
        if (smartJavaAiChecked) return;
        try {
            Class.forName("cn.smartjavaai.ocr.factory.OcrModelFactory");
            smartJavaAiAvailable = true;
            log.info("✅ SmartJavaAI OCR 已就绪");
        } catch (ClassNotFoundException e) {
            log.warn("⚠️ SmartJavaAI OCR 依赖未找到");
        } finally {
            smartJavaAiChecked = true;
        }
    }

    public boolean isAvailable() {
        return smartJavaAiAvailable;
    }

    /**
     * 通用 OCR 识别
     */
    @SuppressWarnings("unchecked")
    public List<OcrItem> recognizeCommon(BufferedImage image) {
        if (!smartJavaAiAvailable) {
            return placeholderResult(image);
        }

        long start = System.currentTimeMillis();
        try {
            // 1. 加载 det 模型
            Object detModel = getOrLoadModel("common_det", () -> loadDetModel());

            // 2. 加载 rec 模型
            Object recModel = getOrLoadModel("common_rec", () -> {
                Object rec = loadRecModel();
                // 把 det 模型注入到 rec（SmartJavaAI 内部依赖）
                try {
                    Object det = modelCache.get("common_det");
                    if (det != null) {
                        Method setTextDetModel = rec.getClass().getMethod("setTextDetModel",
                            Class.forName("cn.smartjavaai.ocr.model.common.detect.OcrCommonDetModel"));
                        setTextDetModel.invoke(rec, det);
                        log.info("已将 det 模型注入到 rec 模型");
                    }
                } catch (Exception e) {
                    log.warn("注入 det 模型到 rec 失败: {}", e.getMessage());
                }
                return rec;
            });

            // 3. det 推理 - 检测文本区域（返回 List<OcrBox>）
            Method detectMethod = detModel.getClass().getMethod("detect", BufferedImage.class);
            List<Object> ocrBoxes = (List<Object>) detectMethod.invoke(detModel, image);
            log.info("det 检出 {} 个区域", ocrBoxes.size());

            if (ocrBoxes.isEmpty()) {
                return new ArrayList<>();
            }

            // 4. 调 rec 模型一次性识别所有 box - 接受 (Image, List<OcrBox>, OcrRecOptions)
            // 转换 BufferedImage -> ai.djl.modality.cv.Image (DJL 0.34 用 fromInputStream)
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            Class<?> imageFactoryCls = Class.forName("ai.djl.modality.cv.ImageFactory");
            Object imageFactory = imageFactoryCls.getMethod("getInstance").invoke(null);
            Method fromInputStream = imageFactoryCls.getMethod("fromInputStream", java.io.InputStream.class);
            Object djImage = fromInputStream.invoke(imageFactory,
                new java.io.ByteArrayInputStream(baos.toByteArray()));

            // 调 recognize(Image, List<OcrBox>, OcrRecOptions)
            Method recMethod = recModel.getClass().getMethod("recognize",
                Class.forName("ai.djl.modality.cv.Image"),
                java.util.List.class,
                Class.forName("cn.smartjavaai.ocr.config.OcrRecOptions"));

            Object ocrInfo;
            try {
                ocrInfo = recMethod.invoke(recModel, djImage, ocrBoxes, null);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                log.error("rec 推理真实异常", cause);
                throw new RuntimeException("rec 推理失败: " + (cause != null ? cause.toString() : e.getMessage()), cause);
            }

            // 解析 OcrInfo - 用 getFullText() 直接拿合并后的文本
            List<OcrItem> result = new ArrayList<>();
            Method getFullText = ocrInfo.getClass().getMethod("getFullText");
            String fullText = (String) getFullText.invoke(ocrInfo);

            // 也用 ocrItemList 拿行级信息（含 score）
            Method getOcrItemList = ocrInfo.getClass().getMethod("getOcrItemList");
            List<?> ocrItemList = (List<?>) getOcrItemList.invoke(ocrInfo);
            if (ocrItemList != null) {
                for (Object oi : ocrItemList) {
                    OcrItem item = new OcrItem();
                    Method getText = oi.getClass().getMethod("getText");
                    String text = (String) getText.invoke(oi);
                    item.setText(text != null ? text : "");
                    // 拿置信度
                    try {
                        Method getScore = oi.getClass().getMethod("getScore");
                        Object score = getScore.invoke(oi);
                        if (score instanceof Number) {
                            item.setScore(((Number) score).floatValue());
                        }
                    } catch (NoSuchMethodException ignore) {}
                    result.add(item);
                }
            }

            log.info("OCR 完成: {} 项, 总耗时 {} ms, fullText: {}",
                result.size(), System.currentTimeMillis() - start,
                fullText != null ? fullText.substring(0, Math.min(50, fullText.length())) : "(空)");

            // 把 fullText 塞到第一个 item 里
            if (!result.isEmpty()) {
                result.get(0).setText(fullText != null ? fullText : result.get(0).getText());
                if (fullText != null) result.get(0).setScore(1.0f);  // 整图合并后的视为高置信
            } else if (fullText != null) {
                OcrItem item = new OcrItem();
                item.setText(fullText);
                item.setScore(1.0f);
                result.add(item);
            }

            return result;
        } catch (Exception e) {
            log.error("OCR 识别失败", e);
            throw new RuntimeException("OCR 识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载 det 模型 - 把 checked 异常吃掉变成 RuntimeException
     */
    private Object loadDetModel() {
        log.info("加载 det 模型...");
        try {
            Class<?> factoryCls = Class.forName("cn.smartjavaai.ocr.factory.OcrModelFactory");
            Method getInstance = factoryCls.getMethod("getInstance");
            Object factory = getInstance.invoke(null);

            Class<?> cfgCls = Class.forName("cn.smartjavaai.ocr.config.OcrDetModelConfig");
            Object config = cfgCls.newInstance();
            Class<?> enumCls = Class.forName("cn.smartjavaai.ocr.enums.CommonDetModelEnum");
            cfgCls.getMethod("setModelEnum", enumCls).invoke(config,
                Enum.valueOf((Class<Enum>) enumCls, "PP_OCR_V5_SERVER_DET_MODEL"));

            String modelPath = resolveModelPath("ch_PP-OCRv5_server_det");
            cfgCls.getMethod("setDetModelPath", String.class).invoke(config, modelPath);

            Method getDet = factoryCls.getMethod("getDetModel", cfgCls);
            return getDet.invoke(factory, config);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            log.error("det 模型加载真实异常", cause);
            throw new RuntimeException("det 模型加载失败: " +
                (cause != null ? cause.toString() : e.getMessage()), cause);
        } catch (Exception e) {
            throw new RuntimeException("det 模型加载失败: " + e.getMessage(), e);
        }
    }

    private Object loadRecModel() {
        log.info("加载 rec 模型...");
        try {
            Class<?> factoryCls = Class.forName("cn.smartjavaai.ocr.factory.OcrModelFactory");
            Method getInstance = factoryCls.getMethod("getInstance");
            Object factory = getInstance.invoke(null);

            Class<?> cfgCls = Class.forName("cn.smartjavaai.ocr.config.OcrRecModelConfig");
            Object config = cfgCls.newInstance();
            Class<?> enumCls = Class.forName("cn.smartjavaai.ocr.enums.CommonRecModelEnum");
            cfgCls.getMethod("setRecModelEnum", enumCls).invoke(config,
                Enum.valueOf((Class<Enum>) enumCls, "PP_OCR_V5_SERVER_REC_MODEL"));

            String modelPath = resolveModelPath("ch_PP-OCRv5_server_rec");
            cfgCls.getMethod("setRecModelPath", String.class).invoke(config, modelPath);

            Method getRec = factoryCls.getMethod("getRecModel", cfgCls);
            return getRec.invoke(factory, config);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            log.error("rec 模型加载真实异常", cause);
            throw new RuntimeException("rec 模型加载失败: " +
                (cause != null ? cause.toString() : e.getMessage()), cause);
        } catch (Exception e) {
            throw new RuntimeException("rec 模型加载失败: " + e.getMessage(), e);
        }
    }

    public String extractText(BufferedImage image) {
        return recognizeCommon(image).stream()
                .map(OcrItem::getText)
                .filter(t -> t != null && !t.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    public Map<String, Object> recognizeIdCard(BufferedImage image, String side) {
        String text = extractText(image);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("raw_text", text);
        result.put("side", side);
        result.put("fields", parseIdCard(text, side));
        // 检查是否找到身份证号
        String idNumber = (String) result.get("id_number");
        boolean found = idNumber != null && !idNumber.isEmpty();
        result.put("detected", found);
        if (!found) {
            result.put("message", "未识别到身份证号字段，请确认上传的是身份证正/反面");
        }
        return result;
    }

    public Map<String, Object> recognizePlate(BufferedImage image) {
        // 1. 真实 OCR 识别
        List<OcrItem> items = recognizeCommon(image);
        String fullText = items.stream()
            .map(OcrItem::getText)
            .filter(t -> t != null && !t.isEmpty())
            .collect(java.util.stream.Collectors.joining("\n"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("raw_text", fullText);
        result.put("all_lines", items);  // 全部行（让前端展示）

        // 2. 严格车牌正则校验
        String plate = extractPlateNumber(fullText);

        if (plate == null) {
            result.put("detected", false);
            result.put("plate", null);
            result.put("message", "未识别到中国车牌号。请确认图片包含清晰的车牌（支持蓝牌/绿牌/黄牌/新能源等 12 种）。");
            result.put("candidates", getPlateCandidates(fullText));  // 提供疑似候选
            result.put("confidence", 0.0f);
        } else {
            // 3. 找到车牌后用 score 算平均置信度
            float avgScore = items.stream()
                .filter(it -> it.getText() != null && it.getText().contains(plate.substring(0, 1)))
                .map(OcrItem::getScore)
                .findFirst()
                .orElse(1.0f);
            result.put("detected", true);
            result.put("plate", plate);
            result.put("confidence", avgScore);
        }
        return result;
    }

    /**
     * 中国车牌正则（12 种）：
     * - 普通车牌：省份简称 + 字母 + 5-6 位字母数字
     * - 新能源：省份简称 + 字母 + 6 位字母数字（D/F 开头）
     * - 使领馆/警车等特殊车牌
     */
    private static final java.util.regex.Pattern PLATE_PATTERN = java.util.regex.Pattern.compile(
        "^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领]" +
        "[A-Z]" +
        "(?:[A-Z0-9]{5,6}|[A-Z0-9]{4}[A-Z0-9挂学警港澳]{1,2})$"
    );

    /**
     * 从 OCR 文本中提取车牌号
     * @return 匹配的车牌号，未匹配返回 null
     */
    private String extractPlateNumber(String text) {
        if (text == null || text.isEmpty()) return null;
        // 遍历所有行
        for (String line : text.split("\\n")) {
            line = line.trim().replace(" ", "").replace("·", "");
            // 整行匹配
            if (PLATE_PATTERN.matcher(line).matches()) {
                return line;
            }
            // 行内包含（处理 "车牌：京A12345" 这种格式）
            java.util.regex.Matcher m = PLATE_PATTERN.matcher(line);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    /**
     * 收集疑似候选（短文本 + 含字母数字），给用户参考
     */
    private List<String> getPlateCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        if (text == null) return candidates;
        for (String line : text.split("\\n")) {
            line = line.trim();
            // 6-8 位且包含字母和数字的行作为候选
            if (line.length() >= 6 && line.length() <= 9 &&
                line.matches(".*[A-Z].*") && line.matches(".*[0-9].*")) {
                candidates.add(line);
            }
        }
        return candidates;
    }

    // ============ Helpers ============

    private Map<String, String> parseIdCard(String text, String side) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return fields;
        for (String line : text.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.matches(".*\\d{17}[\\dXx].*")) fields.put("id_number", line.replaceAll("[^0-9Xx]", ""));
            else if (line.startsWith("姓名")) fields.put("name", line.replace("姓名", "").trim());
            else if (line.startsWith("性别")) fields.put("gender", line.replace("性别", "").trim());
            else if (line.startsWith("民族")) fields.put("ethnicity", line.replace("民族", "").trim());
            else if (line.matches(".*\\d{4}[年.-]\\d{1,2}[月.-]\\d{1,2}.*")) fields.put("birthday", line.replaceAll("[^\\d]", "-").replaceAll("-+", "-"));
            else if (line.startsWith("住址")) fields.put("address", line.replace("住址", "").trim());
        }
        return fields;
    }

    private String resolveModelPath(String modelName) {
        String base = System.getenv("SMARTJAVAAI_MODEL_PATH");
        if (base == null || base.isEmpty()) {
            File f = new File("models", modelName);
            return f.getAbsolutePath();
        }
        return Paths.get(base, modelName).toString();
    }

    private Object getOrLoadModel(String key, Supplier<Object> loader) {
        return modelCache.computeIfAbsent(key, k -> {
            log.info("首次加载模型: {}", k);
            return loader.get();
        });
    }

    private List<OcrItem> placeholderResult(BufferedImage image) {
        OcrItem item = new OcrItem();
        item.setText("[占位] 模型加载未完成");
        return Arrays.asList(item);
    }

    // ============ DTO ============

    @Data
    public static class OcrItem {
        private String text;
        private BBox box;
        private float score = 1.0f;  // 置信度 0-1
    }

    @Data
    public static class BBox {
        private final double x1, y1, x2, y2;
    }
}
