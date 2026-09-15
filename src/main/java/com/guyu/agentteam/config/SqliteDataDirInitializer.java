package com.guyu.agentteam.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SQLite 私有化部署支持：sqlite-jdbc 不会创建数据库文件的父目录，
 * 这里在连接池初始化前根据 spring.datasource.url 提前建好目录；MySQL 部署不受影响。
 */
@Configuration(proxyBeanMethods = false)
public class SqliteDataDirInitializer {

    @Bean
    static BeanPostProcessor sqliteDataDirCreator(Environment environment) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (url.startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            if (!path.isBlank() && !path.startsWith(":memory:")) {
                Path dbFile = Paths.get(path).toAbsolutePath().normalize();
                try {
                    Files.createDirectories(dbFile.getParent());
                } catch (IOException e) {
                    throw new IllegalStateException("无法创建 SQLite 数据目录：" + dbFile.getParent(), e);
                }
            }
        }
        return new BeanPostProcessor() {};
    }
}
