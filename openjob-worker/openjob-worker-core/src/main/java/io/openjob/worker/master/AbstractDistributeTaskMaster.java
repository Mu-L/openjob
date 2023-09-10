package io.openjob.worker.master;

import akka.actor.ActorContext;
import akka.actor.ActorSelection;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.openjob.common.constant.CommonConstant;
import io.openjob.common.constant.TaskConstant;
import io.openjob.common.constant.TaskStatusEnum;
import io.openjob.common.response.WorkerResponse;
import io.openjob.common.util.FutureUtil;
import io.openjob.common.util.TaskUtil;
import io.openjob.worker.constant.WorkerConstant;
import io.openjob.worker.context.JobContext;
import io.openjob.worker.dao.TaskDAO;
import io.openjob.worker.dto.JobInstanceDTO;
import io.openjob.worker.entity.Task;
import io.openjob.worker.processor.ProcessResult;
import io.openjob.worker.processor.TaskResult;
import io.openjob.worker.request.MasterBatchStartContainerRequest;
import io.openjob.worker.request.MasterCheckContainerRequest;
import io.openjob.worker.request.MasterStartContainerRequest;
import io.openjob.worker.util.AddressUtil;
import io.openjob.worker.util.WorkerUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractDistributeTaskMaster extends AbstractTaskMaster {

    protected ScheduledExecutorService scheduledService;
    protected AtomicBoolean submitting = new AtomicBoolean(false);

    public AbstractDistributeTaskMaster(JobInstanceDTO jobInstanceDTO, ActorContext actorContext) {
        super(jobInstanceDTO, actorContext);
    }

    @Override
    protected void init() {
        super.init();

        this.scheduledService = new ScheduledThreadPoolExecutor(
                1,
                new ThreadFactoryBuilder().setNameFormat("Openjob-heartbeat-thread").build(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // Check task complete status.
        this.scheduledService.scheduleWithFixedDelay(new TaskSubmitterAndChecker(this), 1, 3L, TimeUnit.SECONDS);

        // Pull failover task to redispatch.
        this.scheduledService.scheduleWithFixedDelay(new TaskFailover(this), 1, 3L, TimeUnit.SECONDS);
    }

    @Override
    public void submit() {
        // Async submit many tasks
        this.submitting.set(true);
    }

    @Override
    protected void doCircleSecondStatus() {
        this.taskDAO.updateStatusByTaskId(this.circleTaskUniqueId, this.getCircleTaskStatus());
        super.doCircleSecondStatus();
    }

    @Override
    protected Boolean isTaskComplete(Long instanceId, Long circleId) {
        Integer nonFinishCount = taskDAO.countTaskAndExcludeId(instanceId, circleId, TaskStatusEnum.NON_FINISH_LIST, this.circleTaskUniqueId);
        return nonFinishCount <= 0;
    }

    /**
     * Do submit
     */
    protected void doSubmit() {

    }

    /**
     * Do check worker container
     */
    protected void doCheckWorkerContainer() {
        Set<String> failWorkers = Sets.newConcurrentHashSet();

        // Do check worker container
        this.containerWorkers.stream()
                .filter((w) -> !AddressUtil.getWorkerAddressByLocal(this.localWorkerAddress).equals(w))
                .forEach(wd -> {
                    ActorSelection checkSelection = WorkerUtil.getWorkerContainerActor(wd);

                    for (int i = 0; i < WorkerConstant.CHECK_WORKER_RETRY_TIMES; i++) {
                        try {
                            MasterCheckContainerRequest checkRequest = new MasterCheckContainerRequest();
                            checkRequest.setJobId(this.jobInstanceDTO.getJobId());
                            checkRequest.setJobInstanceId(this.jobInstanceDTO.getJobInstanceId());
                            FutureUtil.mustAsk(checkSelection, checkRequest, WorkerResponse.class, 3000L);
                            break;
                        } catch (Throwable throwable) {
                            failWorkers.add(wd);
                            log.warn("Task worker container check failed!", throwable);
                        }
                    }
                });

        if (CollectionUtils.isEmpty(failWorkers)) {
            return;
        }

        // Do failover
        Integer count = TaskDAO.INSTANCE.batchUpdateFailoverByWorkerAddress(new ArrayList<>(failWorkers));
        log.info("Do task worker container failover success! workers={} count={}", failWorkers, count);
    }

    /**
     * Dispatch tasks.
     *
     * @param startRequests start requests.
     * @param isFailover    is failover
     * @param failWorkers   fail workers
     */
    public void dispatchTasks(List<MasterStartContainerRequest> startRequests, Boolean isFailover, Set<String> failWorkers) {
        String workerAddress = WorkerUtil.selectOneWorker(failWorkers);
        if (Objects.isNull(workerAddress)) {
            log.error("Not available worker to dispatch! tasks={} failover={}", startRequests, isFailover);
            return;
        }

        try {
            this.doDispatchTasks(workerAddress, startRequests, isFailover);
        } catch (Throwable e) {
            // Add fail workers.
            failWorkers.add(workerAddress);

            // Select worker address.
            this.dispatchTasks(startRequests, isFailover, failWorkers);
        }
    }

    /**
     * Dispatch tasks.
     *
     * @param workerAddress worker address
     * @param startRequests start requests.
     * @param isFailover    is failover
     */
    public void doDispatchTasks(String workerAddress, List<MasterStartContainerRequest> startRequests, Boolean isFailover) {
        ActorSelection workerSelection = WorkerUtil.getWorkerContainerActor(workerAddress);

        // Add container workers.
        this.containerWorkers.add(workerAddress);

        // Not failover to persist tasks.
        if (!isFailover) {
            this.persistTasks(workerAddress, startRequests);
        }

        MasterBatchStartContainerRequest batchRequest = new MasterBatchStartContainerRequest();
        batchRequest.setJobId(this.jobInstanceDTO.getJobId());
        batchRequest.setJobInstanceId(this.jobInstanceDTO.getJobInstanceId());
        batchRequest.setStartContainerRequests(startRequests);

        FutureUtil.mustAsk(workerSelection, batchRequest, WorkerResponse.class, 3000L);

        // Failover to update status.
        if (isFailover) {
            List<String> taskIds = startRequests.stream().map(MasterStartContainerRequest::getTaskUniqueId).collect(Collectors.toList());
            this.taskDAO.batchUpdateStatusAndWorkerAddressByTaskId(taskIds, TaskStatusEnum.INIT.getStatus(), workerAddress);
        }
    }

    protected void persistTasks(String workerAddress, List<MasterStartContainerRequest> startRequests) {
        List<Task> taskList = startRequests.stream().filter(s -> CommonConstant.YES.equals(s.getPersistent()))
                .map(m -> this.convertToTask(m, workerAddress)).collect(Collectors.toList());

        // Batch add task.
        taskDAO.batchAdd(taskList);
    }

    protected JobContext getBaseJobContext() {
        JobContext jobContext = new JobContext();
        jobContext.setJobId(this.jobInstanceDTO.getJobId());
        jobContext.setJobInstanceId(this.jobInstanceDTO.getJobInstanceId());
        jobContext.setCircleId(this.circleIdGenerator.get());
        jobContext.setDispatchVersion(this.jobInstanceDTO.getDispatchVersion());
        jobContext.setTaskId(this.acquireTaskId());
        jobContext.setJobParamType(this.jobInstanceDTO.getJobParamType());
        jobContext.setJobParams(this.jobInstanceDTO.getJobParams());
        jobContext.setJobExtendParamsType(this.jobInstanceDTO.getJobExtendParamsType());
        jobContext.setJobExtendParams(this.jobInstanceDTO.getJobExtendParams());
        jobContext.setProcessorType(this.jobInstanceDTO.getProcessorType());
        jobContext.setProcessorInfo(this.jobInstanceDTO.getProcessorInfo());
        jobContext.setFailRetryInterval(this.jobInstanceDTO.getFailRetryInterval());
        jobContext.setFailRetryTimes(this.jobInstanceDTO.getFailRetryTimes());
        jobContext.setExecuteType(this.jobInstanceDTO.getExecuteType());
        jobContext.setConcurrency(this.jobInstanceDTO.getConcurrency());
        jobContext.setTimeExpression(this.jobInstanceDTO.getTimeExpression());
        jobContext.setTimeExpressionType(this.jobInstanceDTO.getTimeExpressionType());
        return jobContext;
    }

    /**
     * Persist parent circle task
     */
    protected void persistCircleTask() {
        MasterStartContainerRequest startRequest = this.getMasterStartContainerRequest();
        Task task = this.convertToTask(startRequest, AddressUtil.getWorkerAddressByLocal(this.localWorkerAddress));

        // Parent task name
        task.setTaskName("");
        task.setStatus(TaskStatusEnum.INIT.getStatus());
        task.setTaskParentId(startRequest.getParentTaskUniqueId());
        taskDAO.add(task);

        // Circle task id
        this.circleTaskUniqueId = task.getTaskId();

        // Parent task id
        this.circleTaskId = this.taskIdGenerator.get();
    }

    protected void persistProcessResultTask(String taskName, ProcessResult processResult) {
        long jobId = this.jobInstanceDTO.getJobId();
        long instanceId = this.jobInstanceDTO.getJobInstanceId();
        long version = this.jobInstanceDTO.getDispatchVersion();
        long circleId = this.circleIdGenerator.get();

        Task task = new Task();
        task.setJobId(this.jobInstanceDTO.getJobId());
        task.setInstanceId(this.jobInstanceDTO.getJobInstanceId());
        task.setDispatchVersion(version);
        task.setMapTaskId(0L);
        task.setCircleId(this.circleIdGenerator.get());
        String uniqueId = TaskUtil.getRandomUniqueId(jobId, instanceId, version, circleId, this.taskIdGenerator.get());
        task.setTaskId(uniqueId);
        task.setTaskName(taskName);
        task.setWorkerAddress(AddressUtil.getWorkerAddressByLocal(this.localWorkerAddress));

        // Second delay
        if (this.isSecondDelay()) {
            task.setTaskParentId(this.circleTaskUniqueId);
        } else {
            task.setTaskParentId(TaskConstant.DEFAULT_PARENT_ID);
        }

        task.setStatus(processResult.getStatus().getStatus());
        task.setResult(processResult.getResult());
        TaskDAO.INSTANCE.batchAdd(Collections.singletonList(task));
    }

    protected TaskResult convertTaskToTaskResult(Task task) {
        TaskResult taskResult = new TaskResult();
        taskResult.setJobInstanceId(task.getInstanceId());
        taskResult.setCircleId(task.getCircleId());
        taskResult.setTaskId(task.getTaskId());
        taskResult.setParentTaskId(task.getTaskParentId());
        taskResult.setResult(task.getResult());
        taskResult.setTaskName(task.getTaskName());
        taskResult.setStatus(task.getStatus());
        return taskResult;
    }

    @Override
    protected MasterStartContainerRequest getMasterStartContainerRequest() {
        MasterStartContainerRequest startRequest = super.getMasterStartContainerRequest();
        if (Objects.nonNull(this.circleTaskId)) {
            startRequest.setParentTaskId(this.circleTaskId);
        }
        return startRequest;
    }

    protected static class TaskSubmitterAndChecker implements Runnable {
        private final AbstractDistributeTaskMaster taskMaster;

        public TaskSubmitterAndChecker(AbstractDistributeTaskMaster taskMaster) {
            this.taskMaster = taskMaster;
        }

        @Override
        public void run() {
            try {
                // First to submit task, then run check task status
                if (this.taskMaster.submitting.get()) {
                    // Async to submit task
                    this.submitTask();
                } else {
                    // Check task status
                    this.checkTaskStatus();
                }
            } catch (Throwable throwable) {
                log.error("Task status checker failed!", throwable);
            }
        }

        protected void submitTask() {
            // First to do submit
            this.taskMaster.doSubmit();

            // Then running status.
            this.taskMaster.running.set(true);

            // Submit status
            this.taskMaster.submitting.set(false);
        }

        protected void checkTaskStatus() {
            // When task is running to check status.
            if (!this.taskMaster.running.get()) {
                return;
            }

            long instanceId = this.taskMaster.jobInstanceDTO.getJobInstanceId();

            // Dispatch fail task.
            boolean isComplete = this.taskMaster.isTaskComplete(instanceId, taskMaster.circleIdGenerator.get());
            if (isComplete) {
                try {
                    this.taskMaster.completeTask();
                } catch (InterruptedException e) {
                    log.error("TaskSubmitterAndChecker completeTask failed!", e);
                }
            }
        }
    }

    protected static class TaskFailover implements Runnable {

        protected TaskDAO taskDAO = TaskDAO.INSTANCE;
        private final AbstractDistributeTaskMaster taskMaster;

        public TaskFailover(AbstractDistributeTaskMaster taskMaster) {
            this.taskMaster = taskMaster;
        }

        @Override
        public void run() {
            try {
                // Check task worker container.
                this.checkTaskWorkerContainer();

                // Dispatch failover task
                this.dispatchPullFailoverTask();
            } catch (Throwable throwable) {
                log.error("Task failover puller failed!", throwable);
            }
        }

        protected void checkTaskWorkerContainer() {
            this.taskMaster.doCheckWorkerContainer();
        }

        protected void dispatchPullFailoverTask() {
            // When task is running to check status.
            if (!this.taskMaster.running.get()) {
                return;
            }

            long size = 100;
            long instanceId = this.taskMaster.jobInstanceDTO.getJobInstanceId();
            while (true) {
                List<Task> taskList = this.taskDAO.pullFailoverListBySize(instanceId, size);
                if (CollectionUtils.isEmpty(taskList)) {
                    break;
                }

                List<MasterStartContainerRequest> startRequests = taskList.stream()
                        .map(this.taskMaster::convertToMasterStartContainerRequest)
                        .collect(Collectors.toList());

                try {
                    this.taskMaster.dispatchTasks(startRequests, true, Collections.emptySet());
                } catch (Throwable e) {
                    log.error("Task failover dispatch task failed! message={}", e.getMessage());
                }
            }
        }
    }
}
