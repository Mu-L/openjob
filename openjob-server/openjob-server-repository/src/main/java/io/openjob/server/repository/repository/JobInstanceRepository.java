package io.openjob.server.repository.repository;

import io.openjob.server.repository.dto.GroupCountDTO;
import io.openjob.server.repository.entity.JobInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
public interface JobInstanceRepository extends JpaRepository<JobInstance, Long>, JpaSpecificationExecutor<JobInstance> {

    /**
     * Update for status.
     *
     * @param id           id
     * @param status       status
     * @param failStatus   failStatus
     * @param completeTime completeTime
     * @param updateTime   updateTime
     * @return Integer
     */
    @Transactional(rollbackFor = Exception.class)
    @Modifying
    @Query(value = "update JobInstance as j set j.status=?2,j.failStatus=?3,j.completeTime=?4,j.updateTime=?5 where j.id=?1")
    Integer updateStatus(Long id, Integer status, Integer failStatus, Long completeTime, Long updateTime);

    /**
     * Update for last report time.
     *
     * @param ids            ids
     * @param lastReportTime lastReportTime
     * @return Integer
     */
    @Transactional(rollbackFor = Exception.class)
    @Modifying
    @Query(value = "update JobInstance as j set j.lastReportTime=?2,j.updateTime=?2 where j.id in (?1)")
    Integer updateLastReportTimeByIds(List<Long> ids, Long lastReportTime);

    /**
     * Update by running
     *
     * @param id              id
     * @param workerAddress   worker address.
     * @param status          status
     * @param lastReportTime  last report time.
     * @param dispatchVersion dispatchVersion
     * @return Integer
     */
    @Transactional(rollbackFor = Exception.class)
    @Modifying
    @Query(value = "update JobInstance as j set j.workerAddress=?2,j.status=?3,j.updateTime=?4,j.lastReportTime=?4,j.dispatchVersion=?5 where j.id=?1")
    Integer updateByRunning(Long id, String workerAddress, Integer status, Long lastReportTime, Long dispatchVersion);

    /**
     * Update dispatch version
     *
     * @param id              id
     * @param dispatchVersion dispatchVersion
     * @return Integer
     */
    @Transactional(rollbackFor = Exception.class)
    @Modifying
    @Query(value = "update JobInstance  as j set j.dispatchVersion=?2 where j.id=?1")
    Integer updateDispatchVersion(Long id, Long dispatchVersion);


    /**
     * Find failover list.
     *
     * @param lastReportTime last report time
     * @param slotsIds       slots ids
     * @param status         status
     * @param type           type
     * @param deleted        deleted
     * @return List
     */
    List<JobInstance> findByLastReportTimeLessThanAndSlotsIdInAndStatusAndTimeExpressionTypeNotAndDeleted(
            Long lastReportTime, Set<Long> slotsIds, Integer status, String type, Integer deleted);

    /**
     * Find not dispatch list.
     *
     * @param executeTime execute time
     * @param slotsIds    slots ids.
     * @param status      status.
     * @param deleted     deleted
     * @return list
     */
    List<JobInstance> findByExecuteTimeLessThanAndSlotsIdInAndStatusAndDeleted(Long executeTime, Set<Long> slotsIds, Integer status, Integer deleted);

    /**
     * Find first by id and status.
     *
     * @param jobId       jobId
     * @param id          id
     * @param statusList  statusList
     * @param deleted     deleted
     * @param executeOnce executeOnce
     * @return JobInstance
     */
    JobInstance findFirstByJobIdAndIdNotAndStatusInAndDeletedAndExecuteOnce(Long jobId, Long id, List<Integer> statusList, Integer deleted, Integer executeOnce);

    /**
     * Find first by job id and deleted
     *
     * @param jobId   jobId
     * @param deleted deleted
     * @return JobInstance
     */
    JobInstance findFirstByJobIdAndDeleted(Long jobId, Integer deleted);

    /**
     * Find first by job id and status and deleted
     *
     * @param jobId       jobId
     * @param statusList  statusList
     * @param deleted     deleted
     * @param executeOnce executeOnce
     * @return JobInstance
     */
    List<JobInstance> findByJobIdAndStatusInAndDeletedAndExecuteOnce(Long jobId, List<Integer> statusList, Integer deleted, Integer executeOnce);

    /**
     * Count total
     *
     * @param namespaceId namespaceId
     * @param deleted     deleted
     * @return Long
     */
    Long countByNamespaceIdAndDeleted(Long namespaceId, Integer deleted);

    /**
     * Delete sever by create time and status
     *
     * @param lastTime   lastTime
     * @param statusList statusList
     * @return Long
     */
    @Modifying
    @Transactional(rollbackFor = Exception.class)
    Long deleteByCreateTimeLessThanEqualAndStatusIn(Long lastTime, List<Integer> statusList);

    /**
     * Group by hour time
     *
     * @param namespaceId namespaceId
     * @param startTime   startTime
     * @param endTime     endTime
     * @param status      status
     * @param deleted     deleted
     * @return List
     */
    @Query(value = "SELECT new io.openjob.server.repository.dto.GroupCountDTO(j.createTimeHour, count(j.id)) from JobInstance as j "
            + "where j.namespaceId=?1 and j.createTime >= ?2 and j.createTime<=?3 and j.status=?4 and j.deleted=?5 GROUP BY j.createTimeHour ")
    List<GroupCountDTO> getJobInstanceGroupByHour(Long namespaceId, Long startTime, Long endTime, Integer status, Integer deleted);

    /**
     * Group by date time
     *
     * @param namespaceId namespaceId
     * @param startTime   startTime
     * @param endTime     endTime
     * @param status      status
     * @param deleted     deleted
     * @return List
     */
    @Query(value = "SELECT new io.openjob.server.repository.dto.GroupCountDTO(j.createTimeDate, count(j.id)) from JobInstance as j "
            + "where j.namespaceId=?1 and j.createTime >= ?2 and j.createTime<=?3 and j.status=?4 and j.deleted=?5 GROUP BY j.createTimeDate ")
    List<GroupCountDTO> getJobInstanceGroupByDate(Long namespaceId, Long startTime, Long endTime, Integer status, Integer deleted);

    /**
     * Group by status
     *
     * @param namespaceId namespaceId
     * @param startTime   startTime
     * @param endTime     endTime
     * @param deleted     deleted
     * @return List
     */
    @Query(value = "SELECT new io.openjob.server.repository.dto.GroupCountDTO(j.status, count(j.id)) from JobInstance as j "
            + "where j.namespaceId=?1 and j.createTime >= ?2 and j.createTime<=?3 and j.deleted=?4 GROUP BY j.status ")
    List<GroupCountDTO> getJobInstanceGroupStatus(Long namespaceId, Long startTime, Long endTime, Integer deleted);
}
