package io.openjob.server.openapi.executor;

import io.openjob.common.task.TaskQueue;
import io.openjob.server.openapi.request.WorkerHeartbeatRequest;
import io.openjob.server.openapi.task.WorkerHeartConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.3
 */
@Slf4j
@Component
public class WorkerHeartbeatExecutor {
    private final TaskQueue<WorkerHeartbeatRequest> queue;

    /**
     * New
     */
    public WorkerHeartbeatExecutor() {
        this.queue = new TaskQueue<>(0L, 64);

        //Consumer
        WorkerHeartConsumer consumer = new WorkerHeartConsumer(
                0L,
                1,
                16,
                "Openjob-heartbeat-executor",
                50,
                "Openjob-heartbeat-consumer",
                this.queue
        );
        consumer.start();
    }

    /**
     * Submit request
     *
     * @param request request
     */
    public void submit(WorkerHeartbeatRequest request) {
        try {
            this.queue.submit(request);
        } catch (InterruptedException e) {
            log.error("Worker heartbeat submit failed!", e);
        }
    }
}
