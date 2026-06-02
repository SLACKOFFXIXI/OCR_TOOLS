package cn.smartjavaai.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由 - 把 "/" 重定向到首页模板
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";  // 渲染 templates/index.html
    }
}
