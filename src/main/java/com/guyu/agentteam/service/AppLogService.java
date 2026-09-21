package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.repository.AppLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 运行日志落库：业务线程只入内存队列，后台单线程批量写库，
 * 避免 SQLite 单连接被日志写入阻塞消息落库等主流程；队列满时丢弃最旧日志（保最新）。
 * 保留 30 天，每次批量写入后低频触发过期清理。
 */
@Service
public class AppLogService {

    private static final Logger log = LoggerFactory.getLogger(AppLogService.class);

    private static final int QUEUE_CAPACITY = 5000;
    private static final int BATCH_SIZE = 100;
    private static final Duration RETENTION = Duration.ofDays(30);
    /** 过期清理的最小间隔，避免每次批量写都扫表 */
    private static final long RETENTION_INTERVAL = Duration.ofHours(1).toMillis();

    private final AppLogRepository logs;
    private final TransactionTemplate tx;
    private final BlockingQueue<AppLog> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "app-log-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private long lastRetentionAt = 0;
    private long lastDropWarnAt = 0;

    public AppLogService(AppLogRepository logs, TransactionTemplate tx) {
        this.logs = logs;
        this.tx = tx;
    }

    @PostConstruct
    void start() {
        writer.submit(this::drainLoop);
    }

    @PreDestroy
    void stop() {
        running = false;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        // 尾部日志显式排空：等待期排不完的（SQLite 单连接写慢）直接同步落库，退出时不再丢尾
        List<AppLog> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            try {
                tx.executeWithoutResult(status -> logs.saveAll(rest));
            } catch (Exception e) {
                log.warn("退出时尾部日志落库失败（丢弃 {} 条）: {}", rest.size(), e.getMessage());
            }
        }
    }

    /** 记录一条运行日志（异步落库，失败静默，不影响业务） */
    public void record(String type, String conversationId, String agentId, String content) {
        if (!running) {
            return;
        }
        AppLog l = new AppLog();
        l.setId(Ids.next());
        l.setType(type);
        l.setConversationId(conversationId);
        l.setAgentId(agentId);
        l.setContent(content == null ? "" : content);
        l.setCreatedAt(System.currentTimeMillis());
        // 队列满时丢最旧腾位（出错时恰是日志高峰，丢最新的代价最大），避免自身刷屏
        while (!queue.offer(l)) {
            if (queue.poll() == null) {
                break;
            }
            dropWarn();
        }
    }

    private void drainLoop() {
        final List<AppLog> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                AppLog first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<AppLog> batch) {
        try {
            tx.executeWithoutResult(status -> logs.saveAll(batch));
        } catch (Exception e) {
            log.warn("日志落库失败（丢弃 {} 条）: {}", batch.size(), e.getMessage());
        }
        cleanupIfNeeded();
    }

    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRetentionAt < RETENTION_INTERVAL) {
            return;
        }
        lastRetentionAt = now;
        try {
            int removed = tx.execute(status -> logs.deleteByCreatedAtBefore(now - RETENTION.toMillis()));
            if (removed > 0) {
                log.info("已清理 {} 条过期运行日志", removed);
            }
        } catch (Exception e) {
            log.warn("过期日志清理失败: {}", e.getMessage());
        }
    }

    private synchronized void dropWarn() {
        if (System.currentTimeMillis() - lastDropWarnAt > 60_000) {
            lastDropWarnAt = System.currentTimeMillis();
            log.warn("日志队列已满，新日志将被丢弃");
        }
    }
}
