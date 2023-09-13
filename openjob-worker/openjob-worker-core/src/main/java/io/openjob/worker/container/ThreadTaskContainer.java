package io.openjob.worker.container;

import io.openjob.common.constant.FailStatusEnum;
import io.openjob.common.constant.JobInstanceStopEnum;
import io.openjob.common.constant.TaskStatusEnum;
import io.openjob.worker.context.JobContext;
import io.openjob.worker.request.ContainerTaskStatusRequest;
import io.openjob.worker.request.MasterStartContainerRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
public class ThreadTaskContainer extends BaseTaskContainer {

    protected ExecutorService executorService;

    /**
     * New thread task container.
     *
     * @param startRequest start request.
     */
    public ThreadTaskContainer(MasterStartContainerRequest startRequest) {
        super(startRequest);

        executorService = new ThreadPoolExecutor(
                this.startRequest.getConcurrency(),
                this.startRequest.getConcurrency(),
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<>(),
                r -> new Thread(r, "Openjob-container-thread")
        );
    }

    @Override
    public void execute(JobContext jobContext) {
        // Submit future
        Future<?> future = this.executorService.submit(new ThreadTaskProcessor(jobContext));

        // Add task future
        TaskContainerManager.INSTANCE.addTask(jobContext.getTaskUniqueId(), future);
    }

    @Override
    public void stop(Integer type) {
        // stop
        this.executorService.shutdownNow();

        // report status.
        this.reportStopStatus(type);

        // remove from pool
        TaskContainerPool.remove(startRequest.getJobInstanceId());
    }

    @Override
    public void destroy() {
        // stop
        this.executorService.shutdownNow();

        // remove from pool
        TaskContainerPool.remove(startRequest.getJobInstanceId());
    }

    private void reportStopStatus(Integer type) {
        ContainerTaskStatusRequest request = new ContainerTaskStatusRequest();
        request.setJobId(startRequest.getJobId());
        request.setJobInstanceId(startRequest.getJobInstanceId());
        request.setTaskId(startRequest.getTaskId());
        request.setWorkerAddress("");
        request.setMasterActorPath(startRequest.getMasterAkkaPath());

        if (JobInstanceStopEnum.isNormal(type)) {
            request.setStatus(TaskStatusEnum.FAILED.getStatus());
            request.setFailStatus(FailStatusEnum.NONE.getStatus());
            request.setResult("stopped");
        } else {
            request.setStatus(TaskStatusEnum.FAILED.getStatus());
            request.setFailStatus(FailStatusEnum.EXECUTE_TIMEOUT.getStatus());
            request.setResult("timeout");
        }

        TaskStatusReporter.report(request);
    }
}
