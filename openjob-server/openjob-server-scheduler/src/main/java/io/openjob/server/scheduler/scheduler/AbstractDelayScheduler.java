package io.openjob.server.scheduler.scheduler;

import com.google.common.collect.Maps;
import io.openjob.server.scheduler.contract.DelayScheduler;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
public abstract class AbstractDelayScheduler implements DelayScheduler {
    protected ThreadPoolExecutor executorService;
    protected final Map<Long, AbstractRunnable> runnableList = Maps.newConcurrentMap();

    /**
     * Refresh slots.
     *
     * @param slots    slots
     * @param function function
     */
    protected void refreshSlots(List<Long> slots, Function<Long, AbstractRunnable> function) {
        Set<Long> currentSlots = new HashSet<>(slots);
        Set<Long> runningSlots = this.runnableList.keySet();
        int originRunnableSize = runningSlots.size();

        // Remove slots.
        Set<Long> removeSlots = new HashSet<>(runningSlots);
        removeSlots.removeAll(currentSlots);
        removeSlots.forEach(rs -> Optional.ofNullable(this.runnableList.get(rs)).ifPresent(l -> l.setFinish(true)));

        // Add slots.
        Set<Long> addSlots = new HashSet<>(currentSlots);
        addSlots.removeAll(runningSlots);

        // Refresh current slots to empty
        if (CollectionUtils.isEmpty(slots)) {
            if (Objects.nonNull(this.executorService)) {
                this.stop();

                // Rest executor and runnable list
                this.executorService = null;
                this.runnableList.clear();
            }
            return;
        }

        // When executor is not initialized to start executor.
        if (Objects.isNull(this.executorService)) {
            if (!CollectionUtils.isEmpty(addSlots)) {
                this.start();
            }
            return;
        }

        addSlots.forEach(as -> {
            AbstractRunnable listRunnable = Optional.ofNullable(this.runnableList.get(as))
                    .orElseGet(() -> function.apply(as));

            // Set finish false.
            listRunnable.setFinish(false);
            runnableList.put(as, listRunnable);
            executorService.submit(listRunnable);
        });

        // Reset executor.
        if (slots.size() > originRunnableSize) {
            this.executorService.setMaximumPoolSize(slots.size());
            this.executorService.setCorePoolSize(slots.size());
        } else if (slots.size() < originRunnableSize) {
            this.executorService.setCorePoolSize(slots.size());
            this.executorService.setMaximumPoolSize(slots.size());
        }
    }

    /**
     * Abstract runnable.
     */
    abstract static class AbstractRunnable implements Runnable {
        protected final Long currentSlotId;
        protected final AtomicBoolean finish = new AtomicBoolean(false);

        /**
         * Abstract runnable.
         *
         * @param currentSlotId current slot id.
         */
        public AbstractRunnable(Long currentSlotId) {
            this.currentSlotId = currentSlotId;
        }

        /**
         * Set finish.
         *
         * @param finish finish
         */
        public void setFinish(Boolean finish) {
            this.finish.set(finish);
        }

        /**
         * Fail sleep
         */
        protected void failSleep() {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
