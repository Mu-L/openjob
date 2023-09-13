package io.openjob.server.admin.service.impl;

import io.openjob.common.constant.CommonConstant;
import io.openjob.common.constant.ExecuteTypeEnum;
import io.openjob.common.constant.InstanceStatusEnum;
import io.openjob.common.constant.TaskConstant;
import io.openjob.common.constant.TaskStatusEnum;
import io.openjob.common.constant.TimeExpressionTypeEnum;
import io.openjob.server.admin.request.task.ListChildTaskRequest;
import io.openjob.server.admin.request.task.ListTaskLogRequest;
import io.openjob.server.admin.request.task.ListTaskRequest;
import io.openjob.server.admin.request.task.StopTaskRequest;
import io.openjob.server.admin.service.JobInstanceTaskService;
import io.openjob.server.admin.util.LogFormatUtil;
import io.openjob.server.admin.vo.task.ListChildTaskVO;
import io.openjob.server.admin.vo.task.ListTaskLogVO;
import io.openjob.server.admin.vo.task.ListTaskVO;
import io.openjob.server.admin.vo.task.StopTaskVO;
import io.openjob.server.common.dto.PageDTO;
import io.openjob.server.common.util.BeanMapperUtil;
import io.openjob.server.common.util.PageUtil;
import io.openjob.server.common.vo.PageVO;
import io.openjob.server.log.dao.LogDAO;
import io.openjob.server.log.dto.ProcessorLogDTO;
import io.openjob.server.repository.dao.JobInstanceDAO;
import io.openjob.server.repository.dao.JobInstanceTaskDAO;
import io.openjob.server.repository.dto.TaskGroupCountDTO;
import io.openjob.server.repository.entity.JobInstance;
import io.openjob.server.repository.entity.JobInstanceTask;
import io.openjob.server.scheduler.dto.StopTaskRequestDTO;
import io.openjob.server.scheduler.dto.StopTaskResponseDTO;
import io.openjob.server.scheduler.dto.TaskChildPullRequestDTO;
import io.openjob.server.scheduler.dto.TaskListPullRequestDTO;
import io.openjob.server.scheduler.scheduler.JobInstanceScheduler;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.7
 */
@Service
public class JobInstanceTaskServiceImpl implements JobInstanceTaskService {
    private final JobInstanceTaskDAO jobInstanceTaskDAO;
    private final LogDAO logDAO;
    private final JobInstanceDAO jobInstanceDAO;
    private final JobInstanceScheduler jobInstanceScheduler;

    @Autowired
    public JobInstanceTaskServiceImpl(JobInstanceTaskDAO jobInstanceTaskDAO, LogDAO logDAO, JobInstanceDAO jobInstanceDAO, JobInstanceScheduler jobInstanceScheduler) {
        this.jobInstanceTaskDAO = jobInstanceTaskDAO;
        this.logDAO = logDAO;
        this.jobInstanceDAO = jobInstanceDAO;
        this.jobInstanceScheduler = jobInstanceScheduler;
    }

    @Override
    public PageVO<ListTaskVO> getTaskList(ListTaskRequest request) {
        // Second delay
        if (TimeExpressionTypeEnum.isSecondDelay(request.getTimeExpressionType())) {
            return this.getTaskListBySecond(request);
        }

        return this.getTaskListByOther(request);
    }

    @Override
    public PageVO<ListChildTaskVO> getChildList(ListChildTaskRequest request) {
        if (CommonConstant.YES.equals(request.getPull())) {
            return this.getListChildTaskByPull(request);
        }

        return this.getListChildTaskByQuery(request);
    }

    @Override
    public StopTaskVO stopTask(StopTaskRequest request) {
        StopTaskResponseDTO response = this.jobInstanceScheduler.stopTask(BeanMapperUtil.map(request, StopTaskRequestDTO.class));
        return BeanMapperUtil.map(response, StopTaskVO.class);
    }

    @Override
    public ListTaskLogVO getTaskLogList(ListTaskLogRequest request) {
        List<String> list = new ArrayList<>();
        AtomicLong nextTime = new AtomicLong(0L);
        Integer isComplete = CommonConstant.NO;
        try {
            List<ProcessorLogDTO> processorLogs = this.logDAO.queryByScroll(request.getTaskId(), request.getTime(), request.getSize());

            if (!CollectionUtils.isEmpty(processorLogs)) {
                // Processor list and nextTime.
                processorLogs.forEach(l -> list.add(LogFormatUtil.formatLog(l)));
                nextTime.set(processorLogs.get(processorLogs.size() - 1).getTime());
            } else {
                boolean completeStatus = TaskStatusEnum.FINISH_LIST.contains(request.getStatus());
                if (completeStatus) {
                    isComplete = CommonConstant.YES;
                } else if (CommonConstant.YES.equals(request.getLoading())) {
                    JobInstanceTask jobInstanceTask = this.jobInstanceTaskDAO.getByTaskId(request.getTaskId());
                    if (Objects.nonNull(jobInstanceTask) && TaskStatusEnum.FINISH_LIST.contains(jobInstanceTask.getStatus())) {
                        isComplete = CommonConstant.YES;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ListTaskLogVO listTaskLogVO = new ListTaskLogVO();
        listTaskLogVO.setList(list);
        listTaskLogVO.setTime(nextTime.get());
        listTaskLogVO.setComplete(isComplete);
        return listTaskLogVO;
    }

    /**
     * Get child task by query
     *
     * @param request request
     * @return PageVO
     */
    private PageVO<ListChildTaskVO> getListChildTaskByQuery(ListChildTaskRequest request) {
        PageDTO<JobInstanceTask> pageDTO = this.jobInstanceTaskDAO.getChildList(request.getTaskId(), request.getPage(), request.getSize());

        // Empty
        if (CollectionUtils.isEmpty(pageDTO.getList())) {
            return PageUtil.empty(pageDTO);
        }

        List<String> taskIds = pageDTO.getList().stream().map(JobInstanceTask::getTaskId).collect(Collectors.toList());
        Map<String, Long> countMap = Optional.ofNullable(this.jobInstanceTaskDAO.countByParentTaskIds(taskIds)).orElseGet(ArrayList::new)
                .stream().collect(Collectors.toMap(TaskGroupCountDTO::getParentTaskId, TaskGroupCountDTO::getCount));

        return PageUtil.convert(pageDTO, t -> {
            ListChildTaskVO listSecondVO = BeanMapperUtil.map(t, ListChildTaskVO.class);
            listSecondVO.setChildCount(Optional.ofNullable(countMap.get(t.getTaskId())).orElse(0L));
            listSecondVO.setPull(CommonConstant.NO);
            return listSecondVO;
        });
    }

    /**
     * Get child task by pull
     *
     * @param request request
     * @return PageVO
     */
    private PageVO<ListChildTaskVO> getListChildTaskByPull(ListChildTaskRequest request) {
        JobInstance jobInstance = this.jobInstanceDAO.getById(request.getJobInstanceId());

        // Request data
        TaskChildPullRequestDTO taskChildPullRequestDTO = new TaskChildPullRequestDTO();
        taskChildPullRequestDTO.setJobInstanceId(request.getJobInstanceId());
        taskChildPullRequestDTO.setDispatchVersion(jobInstance.getDispatchVersion());
        taskChildPullRequestDTO.setCircleId(request.getCircleId());
        taskChildPullRequestDTO.setTaskId(request.getTaskId());
        taskChildPullRequestDTO.setWorkerAddress(jobInstance.getWorkerAddress());

        List<ListChildTaskVO> taskList = this.jobInstanceScheduler.pullChildTask(taskChildPullRequestDTO).stream().map(t -> {
            ListChildTaskVO listChildTaskVO = BeanMapperUtil.map(t, ListChildTaskVO.class);

            // Map reduce
            if (ExecuteTypeEnum.isMapReduce(jobInstance.getExecuteType())) {
                listChildTaskVO.setChildCount(1L);
            } else {
                // Sharding or broadcast
                listChildTaskVO.setChildCount(0L);
            }

            listChildTaskVO.setPull(CommonConstant.YES);
            return listChildTaskVO;
        }).collect(Collectors.toList());

        PageVO<ListChildTaskVO> pageVO = new PageVO<>();
        pageVO.setPage(1);
        pageVO.setSize(taskList.size());
        pageVO.setTotal((long) taskList.size());
        pageVO.setList(taskList);
        return pageVO;
    }

    /**
     * Get task list by second
     *
     * @param request request
     * @return PageVO
     */
    private PageVO<ListTaskVO> getTaskListBySecond(ListTaskRequest request) {
        JobInstance jobInstance = this.jobInstanceDAO.getById(request.getJobInstanceId());
        PageDTO<JobInstanceTask> pageDTO = this.jobInstanceTaskDAO.getTaskList(request.getJobInstanceId(), request.getPage(), request.getSize());

        // Convert
        PageVO<ListTaskVO> pageVO = PageUtil.convert(pageDTO, t -> {
            ListTaskVO listTaskVO = BeanMapperUtil.map(t, ListTaskVO.class);
            listTaskVO.setChildCount(this.getChildCount(jobInstance.getTimeExpressionType(), jobInstance.getExecuteType(), listTaskVO.getTaskName()));
            listTaskVO.setPull(CommonConstant.NO);
            return listTaskVO;
        });

        // Pull task from worker at first page
        if (NumberUtils.INTEGER_ZERO.equals(request.getPage() - 1)) {
            Long pullCircleId = 0L;
            JobInstanceTask latestParentTask = this.jobInstanceTaskDAO.getLatestParentTask(request.getJobInstanceId(), TaskConstant.DEFAULT_PARENT_ID);
            if (Objects.nonNull(latestParentTask)) {
                pullCircleId = latestParentTask.getCircleId();
            }

            // Add pull tasks
            List<ListTaskVO> taskList = this.pullTaskListFromWorker(pullCircleId, jobInstance);
            pageVO.getList().addAll(0, taskList);
        }
        return pageVO;
    }


    /**
     * Get task list by other
     *
     * @param request request
     * @return PageVO
     */
    private PageVO<ListTaskVO> getTaskListByOther(ListTaskRequest request) {
        JobInstance jobInstance = this.jobInstanceDAO.getById(request.getJobInstanceId());

        // Pull from worker
        if (InstanceStatusEnum.isRunning(jobInstance.getStatus())) {
            List<ListTaskVO> taskList = this.pullTaskListFromWorker(1L, jobInstance);

            PageVO<ListTaskVO> pageVO = new PageVO<>();
            pageVO.setPage(1);
            pageVO.setSize(taskList.size());
            pageVO.setTotal((long) taskList.size());
            pageVO.setList(taskList);
            return pageVO;
        }

        // Query from instance task
        PageDTO<JobInstanceTask> pageDTO = this.jobInstanceTaskDAO.getTaskList(request.getJobInstanceId(), request.getPage(), request.getSize());

        // Empty
        if (CollectionUtils.isEmpty(pageDTO.getList())) {
            return PageUtil.empty(pageDTO);
        }

        // Convert
        return PageUtil.convert(pageDTO, t -> {
            ListTaskVO listTaskVO = BeanMapperUtil.map(t, ListTaskVO.class);
            listTaskVO.setChildCount(this.getChildCount(jobInstance.getTimeExpressionType(), jobInstance.getExecuteType(), listTaskVO.getTaskName()));
            listTaskVO.setPull(CommonConstant.NO);
            return listTaskVO;
        });
    }

    /**
     * Pull task list from worker
     *
     * @param circleId    circleId
     * @param jobInstance jobInstance
     * @return List
     */
    private List<ListTaskVO> pullTaskListFromWorker(Long circleId, JobInstance jobInstance) {
        TaskListPullRequestDTO taskListPullRequestDTO = new TaskListPullRequestDTO();
        taskListPullRequestDTO.setJobInstanceId(jobInstance.getId());
        taskListPullRequestDTO.setCircleId(circleId);
        return this.jobInstanceScheduler.pullTaskList(taskListPullRequestDTO)
                .stream().map(t -> {
                    ListTaskVO listTaskVO = BeanMapperUtil.map(t, ListTaskVO.class);
                    listTaskVO.setChildCount(this.getChildCount(jobInstance.getTimeExpressionType(), jobInstance.getExecuteType(), listTaskVO.getTaskName()));
                    listTaskVO.setPull(CommonConstant.YES);
                    return listTaskVO;
                }).collect(Collectors.toList());
    }

    /**
     * Get child count
     *
     * @param timeExpressionType timeExpressionType
     * @param executeType        executeType
     * @param taskName           taskName
     * @return Long
     */
    private Long getChildCount(String timeExpressionType, String executeType, String taskName) {
        // Second delay
        if (TimeExpressionTypeEnum.isSecondDelay(timeExpressionType)) {
            if (ExecuteTypeEnum.isStandalone(executeType)) {
                return 0L;
            }
            return 1L;
        }

        // Not MR
        if (!ExecuteTypeEnum.isMapReduce(executeType)) {
            return 0L;
        }

        // MR reduce task
        if (TaskConstant.MAP_TASK_REDUCE_NAME.equals(taskName)) {
            return 0L;
        }

        // MR root task
        return 1L;
    }
}
