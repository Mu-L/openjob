package io.openjob.common.request;

import lombok.Data;

import java.io.Serializable;

/**
 * Second delay to pull the latest instance task
 *
 * @author stelin swoft@qq.com
 * @since 1.0.7
 */
@Data
public class ServerInstanceTaskListPullRequest implements Serializable {

    /**
     * Job instance id
     */
    private Long jobInstanceId;

    /**
     * Job dispatch version
     */
    private Long dispatchVersion;

    /**
     * Circle id
     */
    private Long circleId;
}
