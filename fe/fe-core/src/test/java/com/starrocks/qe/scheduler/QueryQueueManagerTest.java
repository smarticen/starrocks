// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.qe.scheduler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.starrocks.catalog.ResourceGroup;
import com.starrocks.common.Config;
import com.starrocks.common.ExceptionChecker;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.metric.MetricRepo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.DefaultCoordinator;
import com.starrocks.qe.GlobalVariable;
import com.starrocks.qe.ShowExecutor;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.qe.scheduler.slot.BaseSlotManager;
import com.starrocks.qe.scheduler.slot.LogicalSlot;
import com.starrocks.qe.scheduler.slot.SlotManager;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.sql.ast.ShowRunningQueriesStmt;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.Frontend;
import com.starrocks.thrift.TFinishSlotRequirementRequest;
import com.starrocks.thrift.TFinishSlotRequirementResponse;
import com.starrocks.thrift.TReleaseSlotRequest;
import com.starrocks.thrift.TReleaseSlotResponse;
import com.starrocks.thrift.TRequireSlotRequest;
import com.starrocks.thrift.TRequireSlotResponse;
import com.starrocks.thrift.TResourceGroupUsage;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.thrift.TWorkGroup;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class QueryQueueManagerTest extends SchedulerTestBase {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private boolean prevQueueEnableSelect;
    private boolean prevQueueEnableStatistic;
    private boolean prevQueueEnableLoad;
    private boolean prevEnableGroupLevelQueue;
    private int prevQueueConcurrencyHardLimit;
    private double prevQueueMemUsedPctHardLimit;
    private int prevQueuePendingTimeoutSecond;
    private int prevQueueTimeoutSecond;
    private int prevQueueMaxQueuedQueries;

    @BeforeAll
    public static void beforeClass() throws Exception {
        SchedulerTestBase.beforeClass();

        MetricRepo.init();
    }

    @BeforeEach
    public void before() {
        prevQueueEnableSelect = GlobalVariable.isEnableQueryQueueSelect();
        prevQueueEnableStatistic = GlobalVariable.isEnableQueryQueueStatistic();
        prevQueueEnableLoad = GlobalVariable.isEnableQueryQueueLoad();
        prevEnableGroupLevelQueue = GlobalVariable.isEnableGroupLevelQueryQueue();
        prevQueueConcurrencyHardLimit = GlobalVariable.getQueryQueueConcurrencyLimit();
        prevQueueMemUsedPctHardLimit = GlobalVariable.getQueryQueueMemUsedPctLimit();
        prevQueuePendingTimeoutSecond = GlobalVariable.getQueryQueuePendingTimeoutSecond();
        prevQueueTimeoutSecond = connectContext.getSessionVariable().getQueryTimeoutS();
        prevQueueMaxQueuedQueries = GlobalVariable.getQueryQueueMaxQueuedQueries();

        GlobalVariable.setEnableGroupLevelQueryQueue(true);

        GlobalVariable.setQueryQueuePendingTimeoutSecond(Config.max_load_timeout_second);
        connectContext.getSessionVariable().setQueryTimeoutS(Config.max_load_timeout_second);

        mockFrontends(FRONTENDS);

        mockFrontendService(new MockFrontendServiceClient());

        SlotManager slotManager = new SlotManager(GlobalStateMgr.getCurrentState().getResourceUsageMonitor());
        slotManager.start();
        new MockUp<GlobalStateMgr>() {
            @Mock
            public BaseSlotManager getSlotManager() {
                return slotManager;
            }
        };

        MetricRepo.COUNTER_QUERY_QUEUE_PENDING.increase(-MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());

        connectContext.setStartTime();
    }

    @AfterEach
    public void after() {
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> 0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().isEmpty());

        // Reset query queue configs.
        GlobalVariable.setEnableQueryQueueSelect(prevQueueEnableSelect);
        GlobalVariable.setEnableQueryQueueStatistic(prevQueueEnableStatistic);
        GlobalVariable.setEnableQueryQueueLoad(prevQueueEnableLoad);
        GlobalVariable.setEnableGroupLevelQueryQueue(prevEnableGroupLevelQueue);
        GlobalVariable.setQueryQueueConcurrencyLimit(prevQueueConcurrencyHardLimit);
        GlobalVariable.setQueryQueueMemUsedPctLimit(prevQueueMemUsedPctHardLimit);
        GlobalVariable.setQueryQueuePendingTimeoutSecond(prevQueuePendingTimeoutSecond);
        connectContext.getSessionVariable().setQueryTimeoutS(prevQueueTimeoutSecond);
        GlobalVariable.setQueryQueueMaxQueuedQueries(prevQueueMaxQueuedQueries);
    }

    @Test
    public void testNotWait() throws Exception {
        {
            //  Case 1: Coordinator needn't check queue.
            GlobalVariable.setEnableQueryQueueSelect(true);
            DefaultCoordinator coordinator =
                    getSchedulerWithQueryId("select TABLE_CATALOG from information_schema.tables");
            manager.maybeWait(connectContext, coordinator);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
        }

        {
            //  Case 1: Coordinator needn't check queue.
            GlobalVariable.setEnableQueryQueueSelect(false);
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coordinator);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
        }
    }

    @Test
    public void testNeedCheckQueue() throws Exception {
        {
            // 1. ScanNodes is empty.
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select 1");
            Assertions.assertFalse(coordinator.getJobSpec().isNeedQueued());
            Assertions.assertFalse(coordinator.getJobSpec().isJobNeedCheckQueue());
        }

        {
            // 2. ScanNodes only contain SchemaNode.
            DefaultCoordinator coordinator =
                    getSchedulerWithQueryId("select TABLE_CATALOG from information_schema.tables");
            Assertions.assertFalse(coordinator.getJobSpec().isNeedQueued());
            Assertions.assertFalse(coordinator.getJobSpec().isJobNeedCheckQueue());
        }

        {
            // 3. ScanNodes include non-SchemaNode.
            DefaultCoordinator coordinator = getSchedulerWithQueryId(
                    "select TABLE_CATALOG from information_schema.tables UNION ALL select count(1) from lineitem");
            Assertions.assertTrue(coordinator.getJobSpec().isNeedQueued());
            Assertions.assertTrue(coordinator.getJobSpec().isJobNeedCheckQueue());
        }

        {
            // 4. set connectContext.needQueued to false.
            connectContext.setNeedQueued(false);
            DefaultCoordinator coordinator = getSchedulerWithQueryId(
                    "select TABLE_CATALOG from information_schema.tables UNION ALL select count(1) from lineitem");
            Assertions.assertFalse(coordinator.getJobSpec().isNeedQueued());
            connectContext.setNeedQueued(true);
        }
    }

    @Test
    public void testEnableQueue() throws Exception {
        {
            // 1. Load type.
            GlobalVariable.setEnableQueryQueueLoad(false);
            DefaultCoordinator coordinator = getSchedulerWithQueryId("insert into lineitem select * from lineitem");
            Assertions.assertFalse(coordinator.getJobSpec().isEnableQueue());

            GlobalVariable.setEnableQueryQueueLoad(true);
            coordinator = getSchedulerWithQueryId("insert into lineitem select * from lineitem");
            Assertions.assertTrue(coordinator.getJobSpec().isEnableQueue());
            GlobalVariable.setEnableQueryQueueLoad(false);
        }

        {
            // 2. Query for select.
            GlobalVariable.setEnableQueryQueueSelect(false);
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertFalse(coordinator.getJobSpec().isEnableQueue());

            GlobalVariable.setEnableQueryQueueSelect(true);
            coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertTrue(coordinator.getJobSpec().isEnableQueue());
            GlobalVariable.setEnableQueryQueueSelect(false);
        }

        {
            // 3. Query for statistic.
            connectContext.setStatisticsContext(false);
            connectContext.setStatisticsJob(true); // Mock statistics job.
            GlobalVariable.setEnableQueryQueueStatistic(false);
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertFalse(coordinator.getJobSpec().isEnableQueue());

            GlobalVariable.setEnableQueryQueueStatistic(true);
            coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertTrue(coordinator.getJobSpec().isEnableQueue());

            connectContext.setStatisticsJob(false);
            GlobalVariable.setEnableQueryQueueStatistic(true);
            coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertFalse(coordinator.getJobSpec().isEnableQueue());

            connectContext.setStatisticsContext(true);
            GlobalVariable.setEnableQueryQueueStatistic(false);
            coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertFalse(coordinator.getJobSpec().isEnableQueue());

            GlobalVariable.setEnableQueryQueueStatistic(true);
            coordinator = getSchedulerWithQueryId("select * from lineitem");
            Assertions.assertTrue(coordinator.getJobSpec().isEnableQueue());
            connectContext.setStatisticsContext(false);
            GlobalVariable.setEnableQueryQueueStatistic(false);
        }
    }

    @Test
    public void testGlobalQueueNormal() throws Exception {
        final int concurrencyLimit = 3;
        final int numPendingCoordinators = concurrencyLimit * 5 + 1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueMaxQueuedQueries(numPendingCoordinators);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        // 2. Then run `numPendingCoordinators` queries, and they should be queued.
        List<DefaultCoordinator> coords = new ArrayList<>(numPendingCoordinators);
        List<Thread> threads = new ArrayList<>();
        Map<TUniqueId, Throwable> queryIdToShouldThrow = new HashMap<>();
        for (int i = 0; i < numPendingCoordinators; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            coords.add(coord);
            threads.add(new Thread(() -> {
                try {
                    manager.maybeWait(connectContext, coord);
                } catch (StarRocksException | InterruptedException e) {
                    Throwable expected = queryIdToShouldThrow.get(coord.getQueryId());
                    if (expected == null) {
                        throw new RuntimeException(e);
                    } else {
                        Assertions.assertEquals(expected.getClass(), e.getClass());
                        assertThat(e.getMessage()).contains(expected.getMessage());
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> numPendingCoordinators == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        coords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState()));

        // 3. The coming queries exceed the query queue capacity.
        for (int i = 0; i < 10; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Assertions.assertThrows(StarRocksException.class,
                    () -> manager.maybeWait(connectContext, coord),
                    "the number of pending queries exceeds capacity");
        }

        // 4. Finish the first `concurrencyLimit` queries.
        runningCoords.forEach(DefaultCoordinator::onFinished);
        runningCoords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));

        // 5. Each loop dequeues `concurrencyLimit` queries, and cancel one pending queries.
        List<DefaultCoordinator> resetCoords = coords;
        while (!resetCoords.isEmpty()) {
            final int numResetCoords = resetCoords.size();
            int expectedAllocatedCoords = Math.min(numResetCoords, concurrencyLimit);
            // 5.1 `concurrencyLimit` queries become allocated.
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> numResetCoords - expectedAllocatedCoords ==
                            MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            List<DefaultCoordinator> allocatedCoords =
                    coords.stream().filter(coord -> coord.getSlot().getState() == LogicalSlot.State.ALLOCATED)
                            .collect(Collectors.toList());
            Assertions.assertEquals(expectedAllocatedCoords, allocatedCoords.size());

            // 5.2 Cancel one pending query.
            resetCoords =
                    resetCoords.stream().filter(coord -> coord.getSlot().getState() != LogicalSlot.State.ALLOCATED)
                            .collect(Collectors.toList());
            resetCoords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState()));

            if (!resetCoords.isEmpty()) {
                queryIdToShouldThrow.put(resetCoords.get(0).getQueryId(), new StarRocksException("Cancelled"));
                resetCoords.get(0).cancel("Cancel by test");
                Assertions.assertEquals(LogicalSlot.State.CANCELLED, resetCoords.get(0).getSlot().getState());
                resetCoords.remove(0);
            }

            // 4.3 Finish these new allocated queries.
            allocatedCoords.forEach(DefaultCoordinator::onFinished);
            allocatedCoords.forEach(
                    coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));
        }
    }

    @Test
    public void testDisableGroupLevelQueue() throws Exception {
        final int concurrencyLimit = 100;
        final int groupConcurrencyLimit = 1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setEnableGroupLevelQueryQueue(false);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        TWorkGroup group10 = new TWorkGroup().setId(10L).setConcurrency_limit(groupConcurrencyLimit);

        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            mockResourceGroup(group10);
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        runningCoords.forEach(DefaultCoordinator::onFinished);
        runningCoords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));
    }

    /**
     * FIXME(liuzihe): This case is unstable, should fix it and enable it in the future.
     */
    @Disabled
    @Test
    public void testGroupQueueNormal() throws Exception {
        final int concurrencyLimit = 2;
        final int numGroups = 5;
        final int numGroupPendingCoords = concurrencyLimit * 2 + 1;
        // Each group and non-group has `numGroupPendingCoords` coordinators.
        final int numPendingCoords = numGroupPendingCoords * (numGroups + 1);

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueMaxQueuedQueries(numPendingCoords);

        TWorkGroup group0 = new TWorkGroup().setId(0L).setConcurrency_limit(concurrencyLimit - 1);
        TWorkGroup group1 = new TWorkGroup().setId(1L).setConcurrency_limit(0);
        TWorkGroup group2 = new TWorkGroup().setId(2L).setConcurrency_limit(ABSENT_CONCURRENCY_LIMIT);
        TWorkGroup group3 = new TWorkGroup().setId(3L).setConcurrency_limit(concurrencyLimit);
        TWorkGroup group4 = new TWorkGroup().setId(4L).setConcurrency_limit(concurrencyLimit + 1);
        TWorkGroup nonGroup = new TWorkGroup().setId(LogicalSlot.ABSENT_GROUP_ID);
        List<TWorkGroup> groups = ImmutableList.of(group0, group1, group2, group3, group4, nonGroup);

        // 1. Run `concurrencyLimit` non-group queries first, and they shouldn't be queued.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            mockResourceGroup(null);
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        // 2. Then run `numPendingCoordinators` queries, and they should be queued.
        List<DefaultCoordinator> coords = new ArrayList<>(numPendingCoords);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < numGroupPendingCoords; i++) {
            for (TWorkGroup group : groups) {
                if (group.getId() == LogicalSlot.ABSENT_GROUP_ID) {
                    mockResourceGroup(null);
                } else {
                    mockResourceGroup(group);
                }
                DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
                coords.add(coord);

                threads.add(new Thread(() -> {
                    try {
                        manager.maybeWait(connectContext, coord);
                    } catch (StarRocksException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> numPendingCoords == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        coords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState()));
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().size() ==
                        numPendingCoords + concurrencyLimit);

        // 3. The coming queries exceed the query queue capacity.
        for (int i = 0; i < 10; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Assertions.assertThrows(StarRocksException.class,
                    () -> manager.maybeWait(connectContext, coord),
                    "the number of pending queries exceeds capacity");
        }

        // 4. Finish the first `concurrencyLimit` non-group queries.
        runningCoords.forEach(DefaultCoordinator::onFinished);
        runningCoords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));

        // 5. Each loop dequeues `concurrencyLimit` queries, and cancel one pending queries.
        List<DefaultCoordinator> resetCoords = coords;
        while (!resetCoords.isEmpty()) {
            final int numResetCoords = resetCoords.size();
            int expectedAllocatedCoords = Math.min(numResetCoords, concurrencyLimit);
            // 5.1 `concurrencyLimit` queries become allocated.
            Awaitility.await().atMost(10, TimeUnit.SECONDS)
                    .until(() -> numResetCoords - expectedAllocatedCoords ==
                            MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            List<DefaultCoordinator> allocatedCoords =
                    coords.stream().filter(coord -> coord.getSlot().getState() == LogicalSlot.State.ALLOCATED)
                            .collect(Collectors.toList());
            Assertions.assertEquals(expectedAllocatedCoords, allocatedCoords.size());

            resetCoords =
                    resetCoords.stream().filter(coord -> coord.getSlot().getState() != LogicalSlot.State.ALLOCATED)
                            .collect(Collectors.toList());

            // 5.2 Finish these new allocated queries.
            allocatedCoords.forEach(DefaultCoordinator::onFinished);
            allocatedCoords.forEach(
                    coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));
        }
    }

    @Test
    public void testGroupQueueNormalWithoutGlobalLimit() throws Exception {
        final int concurrencyLimit = 4;
        final int numGroups = 4;
        final int numGroupPendingCoords = concurrencyLimit * 2 + 1;
        // Each group and non-group has `numGroupPendingCoords` coordinators.
        final int numPendingCoords = numGroupPendingCoords * (numGroups + 1);

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(0);
        GlobalVariable.setQueryQueueMaxQueuedQueries(numPendingCoords);

        TWorkGroup group0 = new TWorkGroup().setId(0L).setConcurrency_limit(concurrencyLimit - 1);
        TWorkGroup group1 = new TWorkGroup().setId(1L).setConcurrency_limit(0);
        TWorkGroup group2 = new TWorkGroup().setId(2L).setConcurrency_limit(concurrencyLimit);
        TWorkGroup group3 = new TWorkGroup().setId(3L).setConcurrency_limit(concurrencyLimit + 1);
        TWorkGroup nonGroup = new TWorkGroup().setId(LogicalSlot.ABSENT_GROUP_ID);
        List<TWorkGroup> groups = ImmutableList.of(group0, group1, group2, group3, nonGroup);

        Map<TWorkGroup, List<DefaultCoordinator>> groupToCoords =
                groups.stream().collect(Collectors.toMap(Function.identity(), (group) -> new ArrayList<>()));
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < numGroupPendingCoords; i++) {
            for (TWorkGroup group : groups) {
                if (group.getId() == LogicalSlot.ABSENT_GROUP_ID) {
                    mockResourceGroup(null);
                } else {
                    mockResourceGroup(group);
                }
                DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
                groupToCoords.get(group).add(coord);

                threads.add(new Thread(() -> {
                    try {
                        manager.maybeWait(connectContext, coord);
                    } catch (StarRocksException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
        }
        threads.forEach(Thread::start);

        boolean allFinished = false;
        while (!allFinished) {
            groupToCoords.forEach((group, coords) -> {
                int limit = group.getConcurrency_limit();
                if (limit <= 0) {
                    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> coords.stream()
                            .allMatch(coord -> coord.getSlot().getState() == LogicalSlot.State.ALLOCATED));
                    coords.forEach(DefaultCoordinator::onFinished);
                    groupToCoords.put(group, Collections.emptyList());
                    return;
                }

                // In each loop, each group allocates `limit` slots.
                int numAllocatedCoords = Math.min(limit, coords.size());
                Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                        coords.stream().filter(coord -> coord.getSlot().getState() == LogicalSlot.State.ALLOCATED)
                                .count() ==
                                numAllocatedCoords);

                List<DefaultCoordinator> allocatedCoords =
                        coords.stream().filter(coord -> coord.getSlot().getState() == LogicalSlot.State.ALLOCATED)
                                .collect(Collectors.toList());

                List<DefaultCoordinator> pendingCoords =
                        coords.stream().filter(coord -> coord.getSlot().getState() != LogicalSlot.State.ALLOCATED)
                                .collect(Collectors.toList());
                groupToCoords.put(group, pendingCoords);

                allocatedCoords.forEach(DefaultCoordinator::onFinished);
            });

            allFinished = groupToCoords.values().stream().allMatch(List::isEmpty);
        }

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().isEmpty());
    }

    @Test
    public void testPendingTimeout() throws Exception {
        final int concurrencyLimit = 3;
        final int pendingTimeoutSecond = 2;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueuePendingTimeoutSecond(pendingTimeoutSecond);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        {
            // 2.1 The coming query pending timeout, query_timeout (300) > pending_timeout (2).
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Assertions.assertThrows(StarRocksException.class,
                    () -> manager.maybeWait(connectContext, coord),
                    "pending timeout");
            ExceptionChecker.expectThrowsWithMsg(StarRocksException.class,
                    "the session variable [query_queue_pending_timeout_second]",
                    () -> manager.maybeWait(connectContext, coord));
        }

        {
            // 2.2 The coming query query_timeout (2) < pending_timeout (300).
            GlobalVariable.setQueryQueuePendingTimeoutSecond(300);
            DefaultCoordinator coord =
                    getSchedulerWithQueryId("select /*+SET_VAR(query_timeout=2)*/ count(1) from lineitem");
            // query should not be timeout even query_timeout is small because pending time is not in the query_timeout.
            manager.maybeWait(connectContext, coord);
            runningCoords.add(coord);
        }

        {
            // 2.3 The coming query pending timeout but failed to releaseSlot,
            // and then SlotManager should clear this expired pending query.
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TReleaseSlotResponse releaseSlot(TReleaseSlotRequest request) throws TException {
                    throw new TException("mocked-release-slot-exception");
                }
            });
            GlobalVariable.setQueryQueuePendingTimeoutSecond(2);
            DefaultCoordinator coord =
                    getSchedulerWithQueryId("select /*+SET_VAR(query_timeout=2)*/ count(1) from lineitem");
            Assertions.assertThrows(StarRocksException.class,
                    () -> manager.maybeWait(connectContext, coord),
                    "pending timeout");
            mockFrontendService(new MockFrontendServiceClient());
        }
        {
            // 2.4 timeout by CheckTimer
            Instant fakeStart = Instant.now().minusSeconds(2);
            connectContext.setStartTime(fakeStart);
            DefaultCoordinator coord =
                    getSchedulerWithQueryId("select /*+SET_VAR(query_timeout=5)*/ count(1) from lineitem");

            // cancel the execution to simulate timeout
            new MockUp<LogicalSlot>() {
                private boolean first = true;

                @Mock
                public boolean isPendingTimeout() {
                    boolean res = !first;
                    first = false;
                    return res;
                }
            };
            scheduler.schedule(() -> {
                coord.cancel("simulate timeout");
            }, 1, TimeUnit.SECONDS);
            Assertions.assertThrows(StarRocksException.class,
                    () -> manager.maybeWait(connectContext, coord),
                    "pending timeout");
        }

        // 3. Finish the first `concurrencyLimit` non-group queries.
        runningCoords.forEach(DefaultCoordinator::onFinished);
        runningCoords.forEach(
                coordinator -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coordinator.getSlot().getState()));

        // SlotManager should clear this expired pending query.
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().isEmpty());
    }

    @Test
    public void testAllocatedSlotTimeout() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued. And they will be pending time-out and then
        // required slots are released.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        GlobalVariable.setQueryQueuePendingTimeoutSecond(2);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord =
                    getSchedulerWithQueryId("select /*+SET_VAR(query_timeout=2)*/ count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }
        GlobalVariable.setQueryQueuePendingTimeoutSecond(300);

        // 2. The coming query is allocated slots, after the previous queries with allocated slot is expired.
        DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
        manager.maybeWait(connectContext, coord);

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> 0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

        // 3. Finish this query.
        coord.onFinished();
        Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState());
    }

    @Test
    public void testRequireSlotFromManagerFailed() throws Exception {
        mockFrontendService(new MockFrontendServiceClient() {
            @Override
            public TRequireSlotResponse requireSlotAsync(TRequireSlotRequest request) throws TException {
                throw new TException("mock-require-slot-async-exception");
            }
        });

        GlobalVariable.setEnableQueryQueueSelect(true);

        DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
        Assertions.assertThrows(StarRocksException.class,
                () -> manager.maybeWait(connectContext, coord),
                "mock-require-slot-async-exception");
    }

    @Test
    public void testLowerVersionLeaderAtUpgrading() throws Exception {
        {
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TRequireSlotResponse requireSlotAsync(TRequireSlotRequest request) throws TException {
                    throw new TApplicationException(TApplicationException.UNKNOWN_METHOD, "mock-invalid-method");
                }
            });

            GlobalVariable.setEnableQueryQueueSelect(true);

            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState());
        }

        {
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TRequireSlotResponse requireSlotAsync(TRequireSlotRequest request) throws TException {
                    throw new TApplicationException(TApplicationException.UNKNOWN, "mock-not-invalid-method");
                }
            });

            GlobalVariable.setEnableQueryQueueSelect(true);

            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Assertions.assertThrows(StarRocksException.class,
                    () -> manager.maybeWait(connectContext, coord),
                    "mock-not-invalid-method");
        }
    }

    @Test
    public void testLeaderChangeWhenPending() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        // 2. The coming query is pending.
        DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
        Thread thread = new Thread(() -> {
            try {
                manager.maybeWait(connectContext, coord);
            } catch (StarRocksException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue() > 0);
        Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState());

        // 2. The leader is changed, so the query can get slot from the new leader.
        BaseSlotManager oldSlotManager = GlobalStateMgr.getCurrentState().getSlotManager();
        SlotManager slotManager = new SlotManager(GlobalStateMgr.getCurrentState().getResourceUsageMonitor());
        slotManager.start();
        new MockUp<GlobalStateMgr>() {
            @Mock
            public BaseSlotManager getSlotManager() {
                return slotManager;
            }
        };
        changeLeader(FRONTENDS.get(1));
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

        // 3. Finish this query.
        coord.onFinished();
        Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState());

        new MockUp<GlobalStateMgr>() {
            @Mock
            public BaseSlotManager getSlotManager() {
                return oldSlotManager;
            }
        };
        runningCoords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testFinishSlotRequirementForCancelledQuery() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        {
            // 2. The coming query is pending.
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Thread thread = new Thread(
                    () -> Assertions.assertThrows(StarRocksException.class,
                            () -> manager.maybeWait(connectContext, coord),
                            "Cancelled"));
            thread.start();
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> 1 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState());

            // 3. Cancel this query, and failed to releaseSlot due to exception.
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TReleaseSlotResponse releaseSlot(TReleaseSlotRequest request) throws TException {
                    throw new TException("mocked-release-slot-exception");
                }
            });
            coord.cancel("Cancel by test");
            mockFrontendService(new MockFrontendServiceClient());
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() ->
                    0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.CANCELLED, coord.getSlot().getState());
        }

        {
            // 2. The coming query is pending.
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Thread thread = new Thread(
                    () -> Assertions.assertThrows(StarRocksException.class,
                            () -> manager.maybeWait(connectContext, coord),
                            "Cancelled"));
            thread.start();
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> 1 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState());

            // 3. Cancel this query, and failed to releaseSlot due to error status without msg.
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TReleaseSlotResponse releaseSlot(TReleaseSlotRequest request) throws TException {
                    TReleaseSlotResponse res = new TReleaseSlotResponse();
                    TStatus status = new TStatus(TStatusCode.INTERNAL_ERROR);
                    res.setStatus(status);
                    return res;
                }
            });
            coord.cancel("Cancel by test");
            mockFrontendService(new MockFrontendServiceClient());
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> 0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.CANCELLED, coord.getSlot().getState());
        }

        {
            // 2. The coming query is pending.
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Thread thread = new Thread(
                    () -> Assertions.assertThrows(StarRocksException.class,
                            () -> manager.maybeWait(connectContext, coord),
                            "Cancelled"));
            thread.start();
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> 1 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState());

            // 3. Cancel this query, and failed to releaseSlot due to error status with msg.
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TReleaseSlotResponse releaseSlot(TReleaseSlotRequest request) throws TException {
                    TReleaseSlotResponse res = new TReleaseSlotResponse();
                    TStatus status = new TStatus(TStatusCode.INTERNAL_ERROR);
                    status.setError_msgs(ImmutableList.of("msg1", "msg2"));
                    res.setStatus(status);
                    return res;
                }
            });
            coord.cancel("Cancel by test");
            mockFrontendService(new MockFrontendServiceClient());
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> 0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.CANCELLED, coord.getSlot().getState());
        }

        // 4. Finish the first `concurrencyLimit` queries.
        runningCoords.forEach(DefaultCoordinator::onFinished);
        runningCoords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));

        // 5. SlotManager sends the RPC `finishSlotRequirement` and failed due to the cancelled query,
        // so there shouldn't be any slot in SlotManager anymore.
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().isEmpty());
    }

    @Test
    public void testFinishSlotRequirementFailed() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        // 1. Run `concurrencyLimit-1` queries first, and they shouldn't be queued.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit - 1; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());

            runningCoords.add(coord);
        }

        {
            // 2. `finishSlotRequirement` for the coming query failed.
            mockFrontendService(new MockFrontendServiceClient() {
                @Override
                public TFinishSlotRequirementResponse finishSlotRequirement(TFinishSlotRequirementRequest request)
                        throws TException {
                    throw new TException("mocked-finish-slot-requirement-exception");
                }
            });
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            Thread thread = new Thread(
                    () -> Assertions.assertThrows(StarRocksException.class,
                            () -> manager.maybeWait(connectContext, coord),
                            "Cancelled"));
            thread.start();
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> 1 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
            Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState());

            // The slot should be removed after failing to `finishSlotRequirement`.
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().size() ==
                            concurrencyLimit - 1);

            coord.cancel("Cancel by test");
        }

        {
            // 3. `finishSlotRequirement` for the coming query success.
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            mockFrontendService(new MockFrontendServiceClient());
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());
            runningCoords.add(coord);
        }

        // 4. Finish the first `concurrencyLimit` queries.
        runningCoords.forEach(DefaultCoordinator::onFinished);
        runningCoords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.RELEASED, coord.getSlot().getState()));
    }

    @Test
    public void testFrontendDead() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued.
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());
        }

        // 2. Then run `numPendingCoordinators` queries, and they should be queued.
        List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            coords.add(coord);
            threads.add(new Thread(
                    () -> Assertions.assertThrows(StarRocksException.class,
                            () -> manager.maybeWait(connectContext, coord),
                            "Cancelled")));
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> concurrencyLimit == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        coords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState()));

        // 3. The frontend of the allocated and pending slots becomes dead, the slots should be released.
        for (int i = 0; i <= Config.heartbeat_retry_times; i++) {
            handleHbResponse(LOCAL_FRONTEND, System.currentTimeMillis(), false);
        }
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().isEmpty());

        // 4. Then the next `concurrencyLimit` queries shouldn't be queued.
        List<DefaultCoordinator> coords2 = new ArrayList<>(concurrencyLimit);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());
            coords2.add(coord);
        }
        coords2.forEach(DefaultCoordinator::onFinished);

        coords.forEach((coor -> coor.cancel("Cancel by test")));
    }

    @Test
    public void testFrontendRestart() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        // 1. Run `concurrencyLimit` queries first, and they shouldn't be queued.
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());
        }

        // 2. Restart LOCAL_FRONTEND by updating FE.startTime.
        handleHbResponse(LOCAL_FRONTEND, System.currentTimeMillis(), true);

        // 3. Then run `numPendingCoordinators` queries, and they shouldn't be queued.
        List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
            Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());
            coords.add(coord);
        }

        coords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testSlotRequirementFromOldFeStartTime() throws Exception {
        final int concurrencyLimit = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        mockFrontendService(new MockFrontendServiceClient() {
            @Override
            public TRequireSlotResponse requireSlotAsync(TRequireSlotRequest request) throws TException {
                // Mock FE restarting between creating slot requirement and sending RPC.
                handleHbResponse(LOCAL_FRONTEND, System.currentTimeMillis(), true);
                return super.requireSlotAsync(request);
            }
        });

        DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
        Assertions.assertThrows(StarRocksException.class,
                () -> manager.maybeWait(connectContext, coord),
                "FeStartTime is not the latest");
        Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
        Assertions.assertEquals(LogicalSlot.State.CANCELLED, coord.getSlot().getState());
    }

    @Test
    public void testSlotRequirementFromUnknownFe() throws Exception {
        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueuePendingTimeoutSecond(1);

        // Make LOCAL_FRONTEND is unknown.
        List<Frontend> frontendsByName = ImmutableList.of(FRONTENDS.get(0), FRONTENDS.get(2));
        new MockUp<NodeMgr>() {
            @Mock
            public Frontend getFeByName(String name) {
                return frontendsByName.stream().filter(fe -> name.equals(fe.getNodeName())).findAny().orElse(null);
            }

            @Mock
            public Frontend getFeByHost(String host) {
                return FRONTENDS.stream().filter(fe -> host.equals(fe.getHost())).findAny().orElse(null);
            }

            @Mock
            public Pair<String, Integer> getSelfIpAndRpcPort() {
                return Pair.create(LOCAL_FRONTEND.getHost(), LOCAL_FRONTEND.getRpcPort());
            }
        };

        DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
        Assertions.assertThrows(StarRocksException.class,
                () -> manager.maybeWait(connectContext, coord),
                "pending timeout");
    }

    @Test
    public void testResourceUsageEmptyWorker() throws Exception {
        final int concurrencyLimit = 3;
        final int cpuUsagePermilleLimit = 10;
        final double memUsagePctLimit = 0.1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueCpuUsedPermilleLimit(cpuUsagePermilleLimit);
        GlobalVariable.setQueryQueueMemUsedPctLimit(memUsagePctLimit);

        // Empty backend needn't check resource usage.
        {
            List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
            for (int i = 0; i < concurrencyLimit; i++) {
                DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
                coords.add(coord);
                manager.maybeWait(connectContext, coord);
                Assertions.assertEquals(0L, MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue().longValue());
                Assertions.assertEquals(LogicalSlot.State.ALLOCATED, coord.getSlot().getState());
            }
            coords.forEach(DefaultCoordinator::onFinished);
        }
    }

    @Test
    public void testResourceUsageCpuPermilleLimit() throws Exception {
        final int concurrencyLimit = 3;
        final int cpuUsagePermilleLimit = 10;
        final double memUsagePctLimit = 0.1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueCpuUsedPermilleLimit(cpuUsagePermilleLimit);
        GlobalVariable.setQueryQueueMemUsedPctLimit(memUsagePctLimit);

        List<Backend> backends = ImmutableList.of(new Backend(0L, "be0-host", 8030), new Backend(1L, "be1-host", 8030),
                new Backend(2L, "be2-host", 8030));
        List<ComputeNode> computeNodes =
                ImmutableList.of(new ComputeNode(3L, "cn3-host", 8030), new ComputeNode(4L, "cn4-host", 8030),
                        new ComputeNode(5L, "cn5-host", 8030));
        Stream.concat(backends.stream(), computeNodes.stream()).forEach(cn -> cn.setAlive(true));
        backends.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addBackend);
        computeNodes.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addComputeNode);

        // 1. Queries are queued, due to CPU usage exceeds cpuUsagePermilleLimit.
        backends.get(0).setMemLimitBytes(100L);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 0, 10, null);

        List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
        List<Thread> threads = new ArrayList<>(concurrencyLimit);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            coords.add(coord);
            threads.add(new Thread(() -> {
                try {
                    manager.maybeWait(connectContext, coord);
                } catch (StarRocksException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.REQUIRING == coord.getSlot().getState()));

        // 2. Queries are not queued anymore, after CPU usage doesn't exceed cpuUsagePermilleLimit.
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 0, 1, null);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.ALLOCATED == coord.getSlot().getState()));
        coords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testResourceUsageMemUsagePctLimit() throws Exception {
        final int concurrencyLimit = 3;
        final int cpuUsagePermilleLimit = 10;
        final double memUsagePctLimit = 0.1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueCpuUsedPermilleLimit(cpuUsagePermilleLimit);
        GlobalVariable.setQueryQueueMemUsedPctLimit(memUsagePctLimit);

        List<Backend> backends = ImmutableList.of(new Backend(0L, "be0-host", 8030), new Backend(1L, "be1-host", 8030),
                new Backend(2L, "be2-host", 8030));
        List<ComputeNode> computeNodes =
                ImmutableList.of(new ComputeNode(3L, "cn3-host", 8030), new ComputeNode(4L, "cn4-host", 8030),
                        new ComputeNode(5L, "cn5-host", 8030));
        Stream.concat(backends.stream(), computeNodes.stream()).forEach(cn -> cn.setAlive(true));
        backends.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addBackend);
        computeNodes.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addComputeNode);

        // 1. Queries are queued, due to mem usage exceeds memUsagePctLimit.
        backends.get(0).setMemLimitBytes(100L);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, null);

        List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
        List<Thread> threads = new ArrayList<>(concurrencyLimit);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            coords.add(coord);
            threads.add(new Thread(() -> {
                try {
                    manager.maybeWait(connectContext, coord);
                } catch (StarRocksException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.REQUIRING == coord.getSlot().getState()));

        // 2. Queries are not queued anymore, after mem usage doesn't exceed cpuUsagePermilleLimit.
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 0, 0, null);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.ALLOCATED == coord.getSlot().getState()));
        coords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testResourceGroupMaxCpuCores() throws Exception {
        final int numGroupsWithEffectiveMaxCores = 2;
        final int numQueriesPerGroup = 3;

        GlobalVariable.setEnableQueryQueueSelect(true);

        TWorkGroup group0 = new TWorkGroup().setId(0L).setMax_cpu_cores(1);
        TWorkGroup group1 = new TWorkGroup().setId(1L).setMax_cpu_cores(0);
        TWorkGroup group2 = new TWorkGroup().setId(2L).setMax_cpu_cores(ABSENT_MAX_CPU_CORES);
        TWorkGroup group3 = new TWorkGroup().setId(3L).setMax_cpu_cores(2);
        TWorkGroup nonGroup = new TWorkGroup().setId(LogicalSlot.ABSENT_GROUP_ID);
        List<TWorkGroup> groups = ImmutableList.of(group0, group1, group2, group3, nonGroup);
        groups.forEach(this::mockResourceGroup);

        List<Backend> backends = ImmutableList.of(
                new Backend(0L, "be0-host", 8030),
                new Backend(1L, "be1-host", 8030),
                new Backend(2L, "be2-host", 8030));
        List<ComputeNode> computeNodes = ImmutableList.of(
                new ComputeNode(3L, "cn3-host", 8030),
                new ComputeNode(4L, "cn4-host", 8030),
                new ComputeNode(5L, "cn5-host", 8030));
        Stream.concat(backends.stream(), computeNodes.stream()).forEach(cn -> cn.setAlive(true));
        backends.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addBackend);
        computeNodes.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addComputeNode);

        // 1. Queries of group #0 and #3 will be pending.
        List<TResourceGroupUsage> groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(0L).setCpu_core_used_permille(1000).setMem_used_bytes(10),
                new TResourceGroupUsage().setGroup_id(1L).setCpu_core_used_permille(10000),
                new TResourceGroupUsage().setGroup_id(2L).setCpu_core_used_permille(10000),
                new TResourceGroupUsage().setGroup_id(3L).setCpu_core_used_permille(3000)
        );
        backends.get(0).setMemLimitBytes(100L);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, groupUsages);

        List<Thread> threads = new ArrayList<>();
        List<DefaultCoordinator> coords = new ArrayList<>();
        for (int i = 0; i < numQueriesPerGroup; i++) {
            for (TWorkGroup group : groups) {
                mockResourceGroup(group);

                DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
                coords.add(coord);

                threads.add(new Thread(() -> {
                    try {
                        manager.maybeWait(connectContext, coord);
                    } catch (StarRocksException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
        }

        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(
                () -> numGroupsWithEffectiveMaxCores * numQueriesPerGroup ==
                        MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());

        // 2. Group #0 is not overloaded anymore.
        groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(0L).setCpu_core_used_permille(900),
                new TResourceGroupUsage().setGroup_id(3L).setCpu_core_used_permille(3500)
        );
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, groupUsages);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(
                () -> numQueriesPerGroup == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());

        // 3. Group #3 is not overloaded anymore.
        groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(0L).setCpu_core_used_permille(900)
        );
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, groupUsages);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(
                () -> 0 == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());

        coords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testResourceUsageFreshInterval() throws Exception {
        final int concurrencyLimit = 3;
        final int cpuUsagePermilleLimit = 10;
        final double memUsagePctLimit = 0.1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueCpuUsedPermilleLimit(cpuUsagePermilleLimit);
        GlobalVariable.setQueryQueueMemUsedPctLimit(memUsagePctLimit);

        List<Backend> backends = ImmutableList.of(new Backend(0L, "be0-host", 8030), new Backend(1L, "be1-host", 8030),
                new Backend(2L, "be2-host", 8030));
        List<ComputeNode> computeNodes =
                ImmutableList.of(new ComputeNode(3L, "cn3-host", 8030), new ComputeNode(4L, "cn4-host", 8030),
                        new ComputeNode(5L, "cn5-host", 8030));
        Stream.concat(backends.stream(), computeNodes.stream()).forEach(cn -> cn.setAlive(true));
        backends.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addBackend);
        computeNodes.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addComputeNode);

        // 1. Queries are queued, due to mem usage exceeds memUsagePctLimit.
        backends.get(0).setMemLimitBytes(100L);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, null);

        List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
        List<Thread> threads = new ArrayList<>(concurrencyLimit);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            coords.add(coord);
            threads.add(new Thread(() -> {
                try {
                    manager.maybeWait(connectContext, coord);
                } catch (StarRocksException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.REQUIRING == coord.getSlot().getState()));

        // 2. Queries are not queued anymore, because the overloaded BE doesn't report in resourceUsageIntervalMs.
        GlobalVariable.setQueryQueueResourceUsageIntervalMs(1000);
        Thread.sleep(2000);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(1L, 0, 0, 0, null);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.ALLOCATED == coord.getSlot().getState()));
        coords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testResourceUsageBackendDead() throws Exception {
        final int concurrencyLimit = 3;
        final int cpuUsagePermilleLimit = 10;
        final double memUsagePctLimit = 0.1;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);
        GlobalVariable.setQueryQueueCpuUsedPermilleLimit(cpuUsagePermilleLimit);
        GlobalVariable.setQueryQueueMemUsedPctLimit(memUsagePctLimit);

        List<Backend> backends = ImmutableList.of(new Backend(0L, "be0-host", 8030), new Backend(1L, "be1-host", 8030),
                new Backend(2L, "be2-host", 8030));
        List<ComputeNode> computeNodes =
                ImmutableList.of(new ComputeNode(3L, "cn3-host", 8030), new ComputeNode(4L, "cn4-host", 8030),
                        new ComputeNode(5L, "cn5-host", 8030));
        Stream.concat(backends.stream(), computeNodes.stream()).forEach(cn -> cn.setAlive(true));
        backends.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addBackend);
        computeNodes.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addComputeNode);

        // 1. Queries are queued, due to mem usage exceeds memUsagePctLimit.
        backends.get(0).setMemLimitBytes(100L);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, null);

        List<DefaultCoordinator> coords = new ArrayList<>(concurrencyLimit);
        List<Thread> threads = new ArrayList<>(concurrencyLimit);
        for (int i = 0; i < concurrencyLimit; i++) {
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            coords.add(coord);
            threads.add(new Thread(() -> {
                try {
                    manager.maybeWait(connectContext, coord);
                } catch (StarRocksException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.REQUIRING == coord.getSlot().getState()));

        // 2. Queries are not queued anymore, because the overloaded BE becomes dead.
        backends.get(0).setAlive(false);
        GlobalStateMgr.getCurrentState().getResourceUsageMonitor().notifyBackendDead();
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> coords.stream()
                        .allMatch(coord -> coord.getSlot() != null &&
                                LogicalSlot.State.ALLOCATED == coord.getSlot().getState()));
        coords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testShowRunningQueriesEmpty() throws Exception {
        ConnectContext ctx = starRocksAssert.getCtx();
        String sql = "show running queries;";
        ShowRunningQueriesStmt showStmt = (ShowRunningQueriesStmt) UtFrameUtils.parseStmtWithNewParser(sql, ctx);
        ShowResultSet res = ShowExecutor.execute(showStmt, ctx);
        Assertions.assertEquals(showStmt.getMetaData().getColumns(), res.getMetaData().getColumns());
        Assertions.assertTrue(res.getResultRows().isEmpty());
    }

    @Test
    public void testShowRunningQueries() throws Exception {
        final int concurrencyLimit = 2;

        GlobalVariable.setEnableQueryQueueSelect(true);
        GlobalVariable.setQueryQueueConcurrencyLimit(concurrencyLimit);

        TWorkGroup group0 = new TWorkGroup().setId(0L).setConcurrency_limit(concurrencyLimit - 1);
        TWorkGroup group1 = new TWorkGroup().setId(1L).setConcurrency_limit(concurrencyLimit);
        TWorkGroup nonGroup = new TWorkGroup().setId(LogicalSlot.ABSENT_GROUP_ID);
        List<TWorkGroup> groups = ImmutableList.of(nonGroup, group0, group1);

        final int numPendingCoords = groups.size() * concurrencyLimit;

        // 1. Run `concurrencyLimit` queries.
        List<DefaultCoordinator> runningCoords = new ArrayList<>();
        mockResourceGroup(null);
        runningCoords.add(runNoPendingQuery());
        mockResourceGroup(group0);
        runningCoords.add(runNoPendingQuery());

        // 2. Each group has `concurrencyLimit` pending queries.
        List<DefaultCoordinator> coords = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < concurrencyLimit; i++) {
            for (TWorkGroup group : groups) {
                if (group.getId() == LogicalSlot.ABSENT_GROUP_ID) {
                    mockResourceGroup(null);
                } else {
                    mockResourceGroup(group);
                }
                DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
                coords.add(coord);

                threads.add(new Thread(() -> Assertions.assertThrows(StarRocksException.class,
                        () -> manager.maybeWait(connectContext, coord),
                        "Cancelled")));
            }
        }
        threads.forEach(Thread::start);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> numPendingCoords == MetricRepo.COUNTER_QUERY_QUEUE_PENDING.getValue());
        coords.forEach(coord -> Assertions.assertEquals(LogicalSlot.State.REQUIRING, coord.getSlot().getState()));
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> GlobalStateMgr.getCurrentState().getSlotManager().getSlots().size() ==
                        numPendingCoords + concurrencyLimit);

        {
            // 3. show running queries.
            ConnectContext ctx = starRocksAssert.getCtx();
            String sql = "show running queries;";
            ShowRunningQueriesStmt showStmt = (ShowRunningQueriesStmt) UtFrameUtils.parseStmtWithNewParser(sql, ctx);
            ShowResultSet res = ShowExecutor.execute(showStmt, ctx);
            Assertions.assertEquals(showStmt.getMetaData().getColumns(), res.getMetaData().getColumns());

            final int groupIndex = 2;
            final int stateIndex = 6;
            Map<String, Map<String, Integer>> groupToStateToCount =
                    res.getResultRows().stream().collect(Collectors.groupingBy(
                            row -> row.get(groupIndex),
                            Collectors.groupingBy(
                                    row -> row.get(stateIndex),
                                    Collectors.summingInt(row -> 1)
                            )
                    ));
            Map<String, Map<String, Integer>> expectedGroupToStateToCount = ImmutableMap.of(
                    "0", ImmutableMap.of("RUNNING", 1, "PENDING", 2),
                    "1", ImmutableMap.of("PENDING", 2),
                    "-", ImmutableMap.of("RUNNING", 1, "PENDING", 2)
            );
            assertThat(groupToStateToCount).containsExactlyInAnyOrderEntriesOf(expectedGroupToStateToCount);
        }

        {
            // 4. show running queries with limit.
            ConnectContext ctx = starRocksAssert.getCtx();
            String sql = "show running queries limit 4;";
            ShowRunningQueriesStmt showStmt = (ShowRunningQueriesStmt) UtFrameUtils.parseStmtWithNewParser(sql, ctx);
            ShowResultSet res = ShowExecutor.execute(showStmt, ctx);
            Assertions.assertEquals(showStmt.getMetaData().getColumns(), res.getMetaData().getColumns());
            Assertions.assertEquals(4, res.getResultRows().size());
        }

        coords.forEach(coor -> coor.cancel("Cancel by test"));
        runningCoords.forEach(DefaultCoordinator::onFinished);
    }

    @Test
    public void testShowResourceGroupUsage() throws Exception {
        TWorkGroup defaultGroup =
                new TWorkGroup().setId(ResourceGroup.DEFAULT_WG_ID).setName(ResourceGroup.DEFAULT_RESOURCE_GROUP_NAME);
        TWorkGroup defaultMvGroup =
                new TWorkGroup().setId(ResourceGroup.DEFAULT_MV_WG_ID)
                        .setName(ResourceGroup.DEFAULT_MV_RESOURCE_GROUP_NAME);
        TWorkGroup group0 = new TWorkGroup().setId(10L).setName("wg0");
        TWorkGroup group1 = new TWorkGroup().setId(11L).setName("wg1");
        TWorkGroup group2 = new TWorkGroup().setId(12L).setName("wg2");
        TWorkGroup group3 = new TWorkGroup().setId(13L).setName("wg3");
        TWorkGroup nonGroup = new TWorkGroup().setId(LogicalSlot.ABSENT_GROUP_ID);
        List<TWorkGroup> groups =
                ImmutableList.of(defaultGroup, defaultMvGroup, group0, group1, group2, group3, nonGroup);
        groups.forEach(this::mockResourceGroup);

        List<Backend> backends = ImmutableList.of(
                new Backend(0L, "be0-host", 8030),
                new Backend(1L, "be1-host", 8030));
        backends.forEach(cn -> cn.setAlive(true));
        backends.forEach(GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo()::addBackend);

        {
            String res = starRocksAssert.executeShowResourceUsageSql("SHOW USAGE RESOURCE GROUPS;");
            assertThat(res).isEqualTo("Name|Id|Backend|BEInUseCpuCores|BEInUseMemBytes|BERunningQueries\n");
        }

        List<TResourceGroupUsage> groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(ResourceGroup.DEFAULT_WG_ID).setCpu_core_used_permille(3112)
                        .setMem_used_bytes(39).setNum_running_queries(38),
                new TResourceGroupUsage().setGroup_id(10L).setCpu_core_used_permille(112).setMem_used_bytes(9)
                        .setNum_running_queries(8),
                new TResourceGroupUsage().setGroup_id(11L).setCpu_core_used_permille(100),
                new TResourceGroupUsage().setGroup_id(12L).setCpu_core_used_permille(120).setMem_used_bytes(7)
                        .setNum_running_queries(6),
                new TResourceGroupUsage().setGroup_id(13L).setCpu_core_used_permille(30)
        );
        backends.get(0).setMemLimitBytes(100L);
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, groupUsages);
        groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(ResourceGroup.DEFAULT_MV_WG_ID).setCpu_core_used_permille(4110)
                        .setMem_used_bytes(49).setNum_running_queries(48),
                new TResourceGroupUsage().setGroup_id(10L).setCpu_core_used_permille(1110).setMem_used_bytes(19)
                        .setNum_running_queries(18),
                new TResourceGroupUsage().setGroup_id(11L).setCpu_core_used_permille(1100),
                new TResourceGroupUsage().setGroup_id(12L).setCpu_core_used_permille(1120).setMem_used_bytes(17)
                        .setNum_running_queries(16),
                new TResourceGroupUsage().setGroup_id(13L).setCpu_core_used_permille(130)
        );
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(1L, 0, 30, 0, groupUsages);

        {
            String res = starRocksAssert.executeShowResourceUsageSql("SHOW USAGE RESOURCE GROUPS;");
            assertThat(res).isEqualTo("Name|Id|Backend|BEInUseCpuCores|BEInUseMemBytes|BERunningQueries\n" +
                    "default_wg|2|be0-host|3.112|39|38\n" +
                    "default_mv_wg|3|be1-host|4.11|49|48\n" +
                    "wg0|10|be0-host|0.112|9|8\n" +
                    "wg0|10|be1-host|1.11|19|18\n" +
                    "wg1|11|be0-host|0.1|0|0\n" +
                    "wg1|11|be1-host|1.1|0|0\n" +
                    "wg2|12|be0-host|0.12|7|6\n" +
                    "wg2|12|be1-host|1.12|17|16\n" +
                    "wg3|13|be0-host|0.03|0|0\n" +
                    "wg3|13|be1-host|0.13|0|0");
        }

        groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(10L).setCpu_core_used_permille(210).setMem_used_bytes(29)
                        .setNum_running_queries(28),
                new TResourceGroupUsage().setGroup_id(11L).setCpu_core_used_permille(200)
        );
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(0L, 0, 30, 0, groupUsages);
        groupUsages = ImmutableList.of(
                new TResourceGroupUsage().setGroup_id(12L).setCpu_core_used_permille(1220).setMem_used_bytes(27)
                        .setNum_running_queries(26),
                new TResourceGroupUsage().setGroup_id(13L).setCpu_core_used_permille(230)
        );
        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().updateResourceUsage(1L, 0, 30, 0, groupUsages);

        {
            String res = starRocksAssert.executeShowResourceUsageSql("SHOW USAGE RESOURCE GROUPS;");
            assertThat(res).isEqualTo("Name|Id|Backend|BEInUseCpuCores|BEInUseMemBytes|BERunningQueries\n" +
                    "wg0|10|be0-host|0.21|29|28\n" +
                    "wg1|11|be0-host|0.2|0|0\n" +
                    "wg2|12|be1-host|1.22|27|26\n" +
                    "wg3|13|be1-host|0.23|0|0");
        }
    }

    @Test
    public void testTimeoutCheck() throws Exception {
        GlobalVariable.setQueryQueuePendingTimeoutSecond(1);
        Thread.sleep(2000L);

        {
            GlobalVariable.setEnableQueryQueueSelect(true);
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select count(1) from lineitem");
            assertThatThrownBy(() -> manager.maybeWait(connectContext, coordinator))
                    .isInstanceOf(StarRocksException.class)
                    .hasMessageContaining("Failed to allocate resource to query: pending timeout");
        }

        {
            GlobalVariable.setEnableQueryQueueSelect(false);
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coordinator);
        }
    }

    @Test
    public void testSlotPendingTimeout() throws Exception {
        GlobalVariable.setQueryQueuePendingTimeoutSecond(1);
        GlobalVariable.setEnableQueryQueueSelect(true);
        Thread.sleep(2000L);

        {
            DefaultCoordinator coordinator = getSchedulerWithQueryId("select count(1) from lineitem");
            assertThatThrownBy(() -> manager.maybeWait(connectContext, coordinator))
                    .isInstanceOf(StarRocksException.class)
                    .hasMessageContaining("Failed to allocate resource to query: pending timeout");
            assertThat(connectContext.getPendingTimeSecond() == 1);
        }


        {
            GlobalVariable.setQueryQueuePendingTimeoutSecond(300);
            // 2. The coming query is allocated slots, after the previous queries with allocated slot is expired.
            DefaultCoordinator coord = getSchedulerWithQueryId("select count(1) from lineitem");
            manager.maybeWait(connectContext, coord);
            ConnectContext context = coord.getConnectContext();
            Assertions.assertTrue(context.getExecTimeout() == context.getPendingTimeSecond()
                    + context.getSessionVariable().getQueryTimeoutS());

            coord.onFinished();
        }
    }
}
