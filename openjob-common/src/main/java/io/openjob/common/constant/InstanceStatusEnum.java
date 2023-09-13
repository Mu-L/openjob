package io.openjob.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;
import scala.Int;

import java.util.Arrays;
import java.util.List;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
@Getter
@AllArgsConstructor
public enum InstanceStatusEnum {

    /**
     * Waiting for dispatch.
     */
    WAITING(1, "waiting"),

    /**
     * Running.
     */
    RUNNING(5, "running"),

    /**
     * Success.
     */
    SUCCESS(10, "success"),

    /**
     * Fail.
     */
    FAIL(15, "fail"),

    /**
     * Stop.
     */
    STOP(20, "stop"),

    /**
     * Cancel.
     */
    CANCEL(25, "cancel"),
    ;

    private final Integer status;
    private final String message;

    /**
     * Complete status.
     */
    public static final List<Integer> COMPLETE = Arrays.asList(
            SUCCESS.getStatus(),
            FAIL.getStatus(),
            STOP.getStatus(),
            CANCEL.getStatus()
    );

    /**
     * Not complete status.
     */
    public static final List<Integer> NOT_COMPLETE = Arrays.asList(
            WAITING.getStatus(),
            RUNNING.getStatus()
    );


    /**
     * Is running
     *
     * @param status status
     * @return Boolean
     */
    public static Boolean isRunning(Integer status) {
        return RUNNING.getStatus().equals(status);
    }

    /**
     * Is failed
     *
     * @param status status
     * @return Boolean
     */
    public static Boolean isFailed(Integer status) {
        return FAIL.getStatus().equals(status);
    }
}
