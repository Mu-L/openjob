package io.openjob.server.openapi.service.impl;

import io.openjob.server.openapi.request.DelayInstanceAddRequest;
import io.openjob.server.openapi.service.OpenDelayInstanceService;
import io.openjob.server.openapi.vo.DelayInstanceAddVO;
import io.openjob.server.scheduler.dto.DelayInstanceAddRequestDTO;
import io.openjob.server.scheduler.dto.DelayInstanceAddResponseDTO;
import io.openjob.server.scheduler.scheduler.DelayInstanceScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
@Service
public class OpenDelayInstanceServiceImpl implements OpenDelayInstanceService {
    private final DelayInstanceScheduler delayInstanceScheduler;

    @Autowired
    public OpenDelayInstanceServiceImpl(DelayInstanceScheduler delayInstanceScheduler) {
        this.delayInstanceScheduler = delayInstanceScheduler;
    }

    @Override
    public DelayInstanceAddVO add(DelayInstanceAddRequest addRequest) {
        DelayInstanceAddRequestDTO addRequestDTO = new DelayInstanceAddRequestDTO();
        addRequestDTO.setTaskId(addRequest.getTaskId());
        addRequestDTO.setTopic(addRequest.getTopic());
        addRequestDTO.setParams(addRequest.getParams());
        addRequestDTO.setExtra(addRequest.getExtra());
        addRequestDTO.setExecuteTime(addRequest.getExecuteTime());

        DelayInstanceAddResponseDTO addResponseDTO = this.delayInstanceScheduler.add(addRequestDTO);
        DelayInstanceAddVO delayInstanceAddVO = new DelayInstanceAddVO();
        delayInstanceAddVO.setTaskId(addResponseDTO.getTaskId());
        return delayInstanceAddVO;
    }
}
