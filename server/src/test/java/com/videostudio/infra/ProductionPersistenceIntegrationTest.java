package com.videostudio.infra;

import com.videostudio.config.AppProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class ProductionPersistenceIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    @Container static GenericContainer<?> redis=new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    @Test void schemaMigratesAndOutboxRollsBackWithJob() {
        var datasource=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        var flyway=Flyway.configure().dataSource(datasource).load(); flyway.migrate(); flyway.validate();
        var jdbc=new JdbcTemplate(datasource); var transactions=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        assertThrows(IllegalStateException.class,()->transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO analysis_job(asset_id,mode,status,stage,progress,degraded,created_at,updated_at) VALUES (1,'GENERAL','PENDING','QUEUED',0,FALSE,NOW(3),NOW(3))");
            jdbc.update("INSERT INTO job_outbox(job_id,generation,status,next_attempt_at,created_at) VALUES (LAST_INSERT_ID(),1,'PENDING',NOW(3),NOW(3))");
            throw new IllegalStateException("simulated transaction failure");
        }));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM analysis_job",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM job_outbox",Long.class));
    }
    @Test void redisCounterUsesTokenAmountsAndLockOwnerCannotReleaseSomeoneElsesLock() {
        var factory=new LettuceConnectionFactory(redis.getHost(),redis.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        try {
            var template=new StringRedisTemplate(factory);
            var shared=new RedisSupport(new AppProperties(),template);
            assertEquals(120,shared.add("budget:test",120,Duration.ofMinutes(5)));
            assertEquals(240,shared.add("budget:test",120,Duration.ofMinutes(5)));
            assertTrue(template.getExpire("budget:test")>0);
            var token=shared.tryLock("test:lock",Duration.ofMinutes(1)).orElseThrow();
            shared.unlock("test:lock","wrong-owner");
            assertTrue(shared.tryLock("test:lock",Duration.ofMinutes(1)).isEmpty());
            shared.unlock("test:lock",token);
            assertTrue(shared.tryLock("test:lock",Duration.ofMinutes(1)).isPresent());
        } finally { factory.destroy(); }
    }
}
