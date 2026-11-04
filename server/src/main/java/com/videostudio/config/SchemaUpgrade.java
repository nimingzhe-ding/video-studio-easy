package com.videostudio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量结构升级：schema.sql 用的是 {@code CREATE TABLE IF NOT EXISTS}，对已存在的表不会补列。
 * <p>这里用 JDBC 元数据判断列是否存在，缺失时才 ALTER——既能在旧库上平滑升级，
 * 又能在每次启动时安全重复执行（不像 MySQL 的 ADD COLUMN 那样没有 IF NOT EXISTS）。
 */
@Component
public class SchemaUpgrade implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrade.class);

    private record Column(String table, String name, String ddl) {
    }

    private static final List<Column> COLUMNS = new ArrayList<>(List.of(
            new Column("analysis_job", "mode_source", "VARCHAR(16)"),
            new Column("analysis_job", "mode_reason", "VARCHAR(255)")
    ));

    private final JdbcTemplate jdbc;

    public SchemaUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Column column : COLUMNS) {
            try {
                if (!hasColumn(column)) {
                    jdbc.execute("ALTER TABLE %s ADD COLUMN %s %s".formatted(
                            column.table(), column.name(), column.ddl()));
                    log.info("数据库结构升级：{}.{} 已补充", column.table(), column.name());
                }
            } catch (RuntimeException e) {
                log.warn("数据库结构升级失败：{}.{} - {}", column.table(), column.name(), e.getMessage());
            }
        }
    }

    private boolean hasColumn(Column column) {
        try {
            DatabaseMetaData meta = jdbc.getDataSource().getConnection().getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, column.table().toUpperCase(), column.name().toUpperCase())) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("读取数据库元数据失败，跳过 {}.{} 的检查：{}", column.table(), column.name(), e.getMessage());
            return true;
        }
    }
}
