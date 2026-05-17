package com.manga.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 海带 AI 内容智能创作平台 - 主启动类
 *
 * @author manga-ai
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.manga.ai.*.mapper")
public class MangaAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MangaAiApplication.class, args);
    }
}
