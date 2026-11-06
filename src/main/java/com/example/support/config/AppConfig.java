package com.example.support.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    ExecutorService fanOutExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(PlatformProperties props) throws IOException {
        Path file = Path.of(props.dbPath()).toAbsolutePath();
        Files.createDirectories(file.getParent());
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + file + "?busy_timeout=5000&journal_mode=WAL&foreign_keys=true");
        cfg.setMaximumPoolSize(4);
        return new HikariDataSource(cfg);
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> customerEtagFilter() {
        var reg = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        reg.addUrlPatterns("/v1/customers/*");
        return reg;
    }
}
