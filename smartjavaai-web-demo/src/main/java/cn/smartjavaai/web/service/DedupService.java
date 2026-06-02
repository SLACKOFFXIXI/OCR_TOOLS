package cn.smartjavaai.web.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 * 图像查重 + PS 检测服务
 *
 * 核心思路：
 * - 完全相同：MD5 哈希一致
 * - 轻微改动（调色/缩放/裁剪）：pHash 汉明距离 < 10/64
 * - PS 篡改（结构相似但局部修改）：aHash 距离 < 5/64 但 pHash > 10/64
 *
 * 输出：用并查集 Union-Find 合并相关图片为组
 */
@Slf4j
@Service
public class DedupService {

    /**
     * 完整相似度检测
     */
    public List<ImageGroup> detectDuplicates(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 解析每个文件：算 MD5 + aHash + pHash
        List<ImageInfo> infos = new ArrayList<>();
        for (MultipartFile f : files) {
            try {
                ImageInfo info = analyze(f);
                if (info != null) infos.add(info);
            } catch (Exception e) {
                log.warn("解析 {} 失败: {}", f.getOriginalFilename(), e.getMessage());
            }
        }

        log.info("成功解析 {} 张图（共 {} 张）", infos.size(), files.size());

        if (infos.size() < 2) {
            // 单张或 0 张 - 没有重复
            return new ArrayList<>();
        }

        // 2. Union-Find 合并相关图片
        int n = infos.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Similarity sim = compare(infos.get(i), infos.get(j));
                if (sim.isRelated) {
                    union(parent, i, j);
                }
            }
        }

        // 3. 收集分组（只保留 >= 2 张的组）
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }

        List<ImageGroup> result = new ArrayList<>();
        int groupId = 1;
        for (List<Integer> memberIndices : groups.values()) {
            if (memberIndices.size() < 2) continue;  // 单张不显示

            ImageGroup group = new ImageGroup();
            group.groupId = groupId++;
            group.reason = analyzeReason(memberIndices, infos);
            group.images = memberIndices.stream().map(idx -> {
                ImageInfo info = infos.get(idx);
                ImageGroup.ImageEntry entry = new ImageGroup.ImageEntry();
                entry.index = idx;
                entry.name = info.name;
                entry.size = info.size;
                entry.thumbnail = makeThumbnail(info.image);
                return entry;
            }).collect(Collectors.toList());
            result.add(group);
        }

        return result;
    }

    /**
     * 解析单个文件
     */
    private ImageInfo analyze(MultipartFile f) throws Exception {
        ImageInfo info = new ImageInfo();
        info.name = f.getOriginalFilename();
        info.size = f.getSize();
        info.bytes = f.getBytes();

        // MD5
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] md5Bytes = md.digest(info.bytes);
        info.md5 = bytesToHex(md5Bytes);

        // 图像
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(info.bytes));
        if (img == null) {
            log.warn("{} 不是有效图片", info.name);
            return null;
        }
        info.image = img;
        info.width = img.getWidth();
        info.height = img.getHeight();

        // 算 aHash + pHash + 颜色直方图
        info.aHash = averageHash(img);
        info.pHash = dctHash(img);
        info.colorHist = colorHistogram(img);

        return info;
    }

    /**
     * 平均哈希 (aHash)：缩放到 8x8 灰度，比较平均亮度
     * 简单快速，对亮度/对比度调整鲁棒
     */
    private String averageHash(BufferedImage img) {
        BufferedImage gray = resizeGrayscale(img, 8, 8);
        int[] pixels = gray.getRGB(0, 0, 8, 8, null, 0, 8);
        int sum = 0;
        for (int p : pixels) sum += (p & 0xFF);
        int avg = sum / 64;
        StringBuilder sb = new StringBuilder();
        for (int p : pixels) {
            sb.append(((p & 0xFF) >= avg) ? '1' : '0');
        }
        return sb.toString();
    }

    /**
     * DCT 哈希 (pHash)：缩放到 32x32 → 灰度 → DCT 变换 → 取 8x8 低频 → 比较中位数
     * 对缩放、调色、轻微 PS 鲁棒
     */
    private String dctHash(BufferedImage img) {
        // 缩放到 32x32 灰度（DCT 要求）
        BufferedImage gray = resizeGrayscale(img, 32, 32);
        double[][] vals = new double[32][32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                vals[y][x] = gray.getRGB(x, y) & 0xFF;
            }
        }

        // 简化的 2D DCT（不调用外部库）
        double[][] dct = dct2D(vals);

        // 取左上 8x8（低频）
        double[] lowFreq = new double[64];
        int idx = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                lowFreq[idx++] = dct[y][x];
            }
        }

        // 计算中位数（跳过 DC 分量 lowFreq[0]）
        double[] sorted = Arrays.copyOfRange(lowFreq, 1, 64);
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];

        // 比较生成 hash
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            sb.append((lowFreq[i] >= median) ? '1' : '0');
        }
        return sb.toString();
    }

    /**
     * 2D DCT 变换（简化版）
     */
    private double[][] dct2D(double[][] input) {
        int n = input.length;
        double[][] output = new double[n][n];
        double sqrt2overN = Math.sqrt(2.0 / n);
        double sqrt1overN = Math.sqrt(1.0 / n);

        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        sum += input[i][j] *
                            Math.cos(Math.PI * u * (2 * i + 1) / (2 * n)) *
                            Math.cos(Math.PI * v * (2 * j + 1) / (2 * n));
                    }
                }
                double cu = (u == 0) ? sqrt1overN : sqrt2overN;
                double cv = (v == 0) ? sqrt1overN : sqrt2overN;
                output[u][v] = cu * cv * sum;
            }
        }
        return output;
    }

    /**
     * 缩放到指定尺寸并转灰度
     */
    private BufferedImage resizeGrayscale(BufferedImage src, int w, int h) {
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return resized;
    }

    /**
     * 颜色直方图（每个通道 16 桶，3 通道共 48 桶）
     * 用 long 累加避免 int 溢出
     */
    private long[] colorHistogram(BufferedImage img) {
        long[] hist = new long[48];  // R: 0-15, G: 16-31, B: 32-47
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < h; y += 4) {  // 降采样 4x
            for (int x = 0; x < w; x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                hist[r / 16]++;
                hist[16 + g / 16]++;
                hist[32 + b / 16]++;
            }
        }
        return hist;
    }

    /**
     * 直方图相似度（0-1），用余弦相似度 + 兜底 NaN
     */
    private double histogramSimilarity(long[] h1, long[] h2) {
        double dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < h1.length; i++) {
            dot += h1[i] * h2[i];
            n1 += h1[i] * h1[i];
            n2 += h2[i] * h2[i];
        }
        double denom = Math.sqrt(n1) * Math.sqrt(n2);
        if (denom < 1e-9 || Double.isNaN(denom) || Double.isInfinite(denom)) {
            long inter = 0, union = 0;
            for (int i = 0; i < h1.length; i++) {
                inter += Math.min(h1[i], h2[i]);
                union += Math.max(h1[i], h2[i]);
            }
            return union > 0 ? (double) inter / union : 1.0;
        }
        return dot / denom;
    }

    /**
     * 比较两张图 - 综合 pHash + 颜色直方图 + 尺寸
     */
    private Similarity compare(ImageInfo a, ImageInfo b) {
        Similarity sim = new Similarity();

        // 1. MD5 完全相同
        if (a.md5 != null && b.md5 != null && a.md5.equals(b.md5)) {
            sim.isRelated = true;
            sim.reason = "完全相同（MD5 一致）";
            sim.similarity = 1.0;
            return sim;
        }

        int aDist = hammingDistance(a.aHash, b.aHash);
        int pDist = hammingDistance(a.pHash, b.pHash);
        double histSim = histogramSimilarity(a.colorHist, b.colorHist);
        boolean sameSize = Math.abs(a.width - b.width) < 20 && Math.abs(a.height - b.height) < 20;

        log.debug("比较 {} vs {} -> aDist={}, pDist={}, histSim={}, sameSize={}",
            a.name, b.name, aDist, pDist, Double.isNaN(histSim) ? "NaN" : String.format("%.3f", histSim), sameSize);

        // 几乎完全一致：pHash 极小（<= 2）+ aHash 也小（<= 2）+ 颜色高相似
        if (pDist <= 2 && aDist <= 2 && histSim > 0.95) {
            sim.isRelated = true;
            sim.reason = "几乎完全一致（轻微调色/缩放）";
            sim.similarity = 1.0 - pDist / 64.0;
            return sim;
        }

        // 高度相似：pHash 小（<= 4）+ aHash 小（<= 4）+ 颜色高相似
        if (pDist <= 4 && aDist <= 4 && histSim > 0.95) {
            sim.isRelated = true;
            sim.reason = "高度相似（轻度编辑）";
            sim.similarity = 1.0 - pDist / 64.0;
            return sim;
        }

        // PS 检测：颜色高相似 + pHash 中等距离 + aHash 中等距离 + 同尺寸
        if (pDist <= 16 && aDist <= 12 && sameSize && histSim > 0.75) {
            sim.isRelated = true;
            sim.reason = "疑似 PS 篡改（结构相似但细节不同）";
            sim.similarity = 1.0 - pDist / 64.0;
            return sim;
        }

        return sim;  // 不相关
    }

    /**
     * 汉明距离（不同 bit 数）
     */
    private int hammingDistance(String a, String b) {
        if (a.length() != b.length()) return 64;
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) d++;
        }
        return d;
    }

    /**
     * 缩略图（用于前端显示）
     */
    private String makeThumbnail(BufferedImage img) {
        try {
            int w = 200;
            int h = (int) (img.getHeight() * (200.0 / img.getWidth()));
            BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(thumb, "jpg", baos);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 分析组的"主因" - 选组内最相似的一对来确定
     */
    private String analyzeReason(List<Integer> indices, List<ImageInfo> infos) {
        if (indices.size() < 2) return "单张";
        // 找最相似的一对（pHash 距离最小）
        int bestI = indices.get(0), bestJ = indices.get(1);
        int bestPDist = Integer.MAX_VALUE;
        String bestReason = "可能相关";
        for (int i = 0; i < indices.size(); i++) {
            for (int j = i + 1; j < indices.size(); j++) {
                ImageInfo a = infos.get(indices.get(i));
                ImageInfo b = infos.get(indices.get(j));
                int pDist = hammingDistance(a.pHash, b.pHash);
                if (pDist < bestPDist) {
                    bestPDist = pDist;
                    bestI = indices.get(i);
                    bestJ = indices.get(j);
                }
            }
        }
        Similarity sim = compare(infos.get(bestI), infos.get(bestJ));
        return sim.reason;
    }

    private int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ============ DTOs ============

    public static class ImageInfo {
        public String name;
        public long size;
        public byte[] bytes;
        public String md5;
        public String aHash;
        public String pHash;
        public long[] colorHist;  // RGB 颜色直方图（long 防止溢出）
        public int width;
        public int height;
        public BufferedImage image;
    }

    public static class Similarity {
        boolean isRelated = false;
        String reason = "";
        double similarity = 0.0;
    }

    public static class ImageGroup {
        public int groupId;
        public String reason;
        public List<ImageEntry> images;

        public static class ImageEntry {
            public int index;
            public String name;
            public long size;
            public String thumbnail;
        }
    }
}
