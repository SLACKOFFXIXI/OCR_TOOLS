package cn.smartjavaai.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WebDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebDemoApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("  SmartJavaAI Web Demo 已启动");
        System.out.println("  浏览器打开: http://localhost:8080");
        System.out.println("========================================\n");
    }
}
