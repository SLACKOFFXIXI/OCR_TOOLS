package cn.smartjavaai.web.controller;

import cn.smartjavaai.web.service.DedupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片查重 / PS 检测 API
 *
 * POST /api/dedup - 接受多个文件，返回重复/PS 分组
 */
@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DedupController {

    @Autowired
    private DedupService dedupService;

    @PostMapping(value = "/dedup", consumes = "multipart/form-data")
    public Map<String, Object> dedup(@RequestParam("files") List<MultipartFile> files) {
        long start = System.currentTimeMillis();
        try {
            log.info("收到查重请求: {} 个文件", files.size());
            List<DedupService.ImageGroup> groups = dedupService.detectDuplicates(files);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("total_files", files.size());
            result.put("group_count", groups.size());
            result.put("groups", groups);
            result.put("elapsed_ms", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("查重失败", e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
}
