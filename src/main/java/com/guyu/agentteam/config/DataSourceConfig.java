package com.guyu.agentteam.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 自定义 DataSource：替代自动配置，在连接池创建后、任何 Bean 使用数据库前执行版本化迁移，
 * 保证启动早期的 @PostConstruct 数据库访问（如工作区设置加载）已能看到完整表结构。
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(DataSourceProperties properties, Environment env) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(env).bind("spring.datasource.hikari", Bindable.ofInstance(ds));
        DatabaseMigrator.migrate(ds);
        return ds;
    }
}
