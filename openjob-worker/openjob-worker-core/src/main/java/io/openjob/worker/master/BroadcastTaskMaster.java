package io.openjob.worker.master;

import akka.actor.ActorContext;
import akka.actor.ActorSelection;
import io.openjob.common.constant.TaskConstant;
import io.openjob.common.response.WorkerResponse;
import io.openjob.common.util.FutureUtil;
import io.openjob.common.util.TaskUtil;
import io.openjob.worker.context.JobContext;
import io.openjob.worker.dto.JobInstanceDTO;
import io.openjob.worker.init.WorkerContext;
import io.openjob.worker.processor.ProcessResult;
import io.openjob.worker.processor.ProcessorHandler;
import io.openjob.worker.processor.TaskResult;
import io.openjob.worker.request.MasterStartContainerRequest;
import io.openjob.worker.util.ProcessorUtil;
import io.openjob.worker.util.ThreadLocalUtil;
import io.openjob.worker.util.WorkerUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
@Slf4j
public class BroadcastTaskMaster extends AbstractDistributeTaskMaster {
    /**
     * Default max task num
     */
    private static final Long DEFAULT_MAX_TASK_NUM = 1024L;

    public BroadcastTaskMaster(JobInstanceDTO jobInstanceDTO, ActorContext actorContext) {
        super(jobInstanceDTO, actorContext);
    }

    @Override
    public void completeTask() throws InterruptedException {
        // Post process
        this.postProcess();

        // complete task
        super.completeTask();
    }

    @Override
    public void doSubmit() {
        // Second delay to persist circle task
        if (this.isSecondDelay()) {
            this.persistCircleTask();
        }

        // Dispatch tasks
        AtomicLong index = new AtomicLong(1L);
        WorkerContext.getOnlineWorkers().forEach(workerAddress -> {
            ActorSelection workerSelection = WorkerUtil.getWorkerContainerActor(workerAddress);
            MasterStartContainerRequest startRequest = this.getMasterStartContainerRequest();
            startRequest.setTaskName(TaskUtil.getBroadcastTaskName(index.get()));

            try {
                // Dispatch task
                FutureUtil.mustAsk(workerSelection, startRequest, WorkerResponse.class, 3000L);

                // Dispatch success to persist task.
                this.persistTasks(workerAddress, Collections.singletonList(startRequest));
                index.getAndIncrement();
            } catch (Exception e) {
                log.warn(String.format("Broadcast failed! workerAddress=%s", workerAddress));
            }
        });

        // Add task manager
        this.addTask2Manager();
    }

    @Override
    public void stop(Integer type) {
        // Stop scheduled thread poll
        this.scheduledService.shutdown();

        // Stop master
        super.stop(type);
    }

    @Override
    public void destroyTaskContainer() {
        // Stop scheduled thread poll
        this.scheduledService.shutdown();

        // Destroy task container
        super.destroyTaskContainer();
    }

    protected void postProcess() {
        // Not find
        ProcessorHandler processorHandler = ProcessorUtil.getProcessor(this.jobInstanceDTO.getProcessorInfo());
        if (Objects.isNull(processorHandler) || Objects.isNull(processorHandler.getBaseProcessor())) {
            log.error("Not find processor! processorInfo={}", this.jobInstanceDTO.getProcessorInfo());
            return;
        }

        // Post process
        JobContext jobContext = getBroadcastPostJobContext();
        ProcessResult processResult = new ProcessResult(false);
        try {
            ThreadLocalUtil.setJobContext(jobContext);
            processResult = processorHandler.postProcess(jobContext);
        } catch (Throwable ex) {
            processResult.setResult(ex.toString());
        } finally {
            ThreadLocalUtil.removeJobContext();
        }

        // Persist post process task
        this.persistProcessResultTask(TaskConstant.BROADCAST_POST_NAME, processResult);
    }

    protected JobContext getBroadcastPostJobContext() {
        JobContext jobContext = this.getBaseJobContext();
        jobContext.setTaskName(TaskConstant.BROADCAST_POST_NAME);
        jobContext.setTaskResultList(this.getBroadcastTaskResultList());
        return jobContext;
    }

    protected List<TaskResult> getBroadcastTaskResultList() {
        return this.taskDAO.getList(this.jobInstanceDTO.getJobInstanceId(), this.circleIdGenerator.get(), DEFAULT_MAX_TASK_NUM)
                .stream().filter((t) -> {
                    // Second delay exclude default root parent
                    if (this.isSecondDelay()) {
                        return !TaskConstant.DEFAULT_PARENT_ID.equals(t.getTaskParentId());
                    }
                    return true;
                })
                .map(this::convertTaskToTaskResult).collect(Collectors.toList());
    }
}
