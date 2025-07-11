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

package com.starrocks.lake;

import com.staros.proto.FileStoreInfo;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.ExceptionChecker;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ShowExecutor;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.sql.ast.CreateDbStmt;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.ShowCreateTableStmt;
import com.starrocks.storagevolume.StorageVolume;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CreateLakeTableTest {
    private static ConnectContext connectContext;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster(RunMode.SHARED_DATA);
        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        // create database
        String createDbStmtStr = "create database lake_test;";
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseStmtWithNewParser(createDbStmtStr, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().createDb(createDbStmt.getFullDbName());
    }

    @AfterAll
    public static void afterClass() {
    }

    private static void createTable(String sql) throws Exception {
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().createTable(createTableStmt);
    }

    private void checkLakeTable(String dbName, String tableName) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), tableName);
        Assertions.assertTrue(table.isCloudNativeTable());
    }

    private LakeTable getLakeTable(String dbName, String tableName) {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbName);
        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), tableName);
        Assertions.assertTrue(table.isCloudNativeTable());
        return (LakeTable) table;
    }

    private String getDefaultStorageVolumeFullPath() {
        StorageVolume sv = GlobalStateMgr.getCurrentState().getStorageVolumeMgr().getDefaultStorageVolume();
        StarOSAgent starOSAgent = GlobalStateMgr.getCurrentState().getStarOSAgent();
        FileStoreInfo fsInfo = sv.toFileStoreInfo();
        String serviceId = "";
        try {
            serviceId = (String) FieldUtils.readField(starOSAgent, "serviceId", true);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Fail to access StarOSAgent.serviceId");
        }
        return String.format("%s/%s", fsInfo.getLocations(0), serviceId);
    }

    @Test
    public void testCreateLakeTable() throws StarRocksException {
        // normal
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.single_partition_duplicate_key (key1 int, key2 varchar(10))\n" +
                        "distributed by hash(key1) buckets 3\n" +
                        "properties('replication_num' = '1');"));
        checkLakeTable("lake_test", "single_partition_duplicate_key");

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.multi_partition_aggregate_key (key1 date, key2 varchar(10), v bigint sum)\n" +
                        "partition by range(key1)\n" +
                        "(partition p1 values less than (\"2022-03-01\"),\n" +
                        " partition p2 values less than (\"2022-04-01\"))\n" +
                        "distributed by hash(key2) buckets 2\n" +
                        "properties('replication_num' = '1');"));
        checkLakeTable("lake_test", "multi_partition_aggregate_key");

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.multi_partition_unique_key (key1 int, key2 varchar(10), v bigint)\n" +
                        "unique key (key1, key2)\n" +
                        "partition by range(key1)\n" +
                        "(partition p1 values less than (\"10\"),\n" +
                        " partition p2 values less than (\"20\"))\n" +
                        "distributed by hash(key2) buckets 1\n" +
                        "properties('replication_num' = '1');"));
        checkLakeTable("lake_test", "multi_partition_unique_key");

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("lake_test");
        LakeTable table = getLakeTable("lake_test", "multi_partition_unique_key");
        String defaultFullPath = getDefaultStorageVolumeFullPath();
        String defaultTableFullPath = String.format("%s/db%d/%d", defaultFullPath, db.getId(), table.getId());
        Assertions.assertEquals(defaultTableFullPath, Objects.requireNonNull(table.getDefaultFilePathInfo()).getFullPath());
        Assertions.assertEquals(defaultTableFullPath + "/100",
                Objects.requireNonNull(table.getPartitionFilePathInfo(100)).getFullPath());
        Assertions.assertEquals(2, table.getMaxColUniqueId());
        Assertions.assertEquals(0, table.getColumn("key1").getUniqueId());
        Assertions.assertEquals(1, table.getColumn("key2").getUniqueId());
        Assertions.assertEquals(2, table.getColumn("v").getUniqueId());
    }

    @Test
    public void testCreateLakeTableWithStorageCache() throws StarRocksException {
        // normal
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.single_partition_duplicate_key_cache (key1 int, key2 varchar(10))\n" +
                        "distributed by hash(key1) buckets 3\n" +
                        "properties('datacache.enable' = 'true');"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "single_partition_duplicate_key_cache");
            // check table property
            StorageInfo storageInfo = lakeTable.getTableProperty().getStorageInfo();
            Assertions.assertTrue(storageInfo.isEnableDataCache());
            // check partition property
            long partitionId = lakeTable.getPartition("single_partition_duplicate_key_cache").getId();
            DataCacheInfo partitionDataCacheInfo = lakeTable.getPartitionInfo().getDataCacheInfo(partitionId);
            Assertions.assertTrue(partitionDataCacheInfo.isEnabled());
            Assertions.assertFalse(partitionDataCacheInfo.isAsyncWriteBack());
        }

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.multi_partition_aggregate_key_cache \n" +
                        "(key1 date, key2 varchar(10), v bigint sum)\n" +
                        "partition by range(key1)\n" +
                        "(partition p1 values less than (\"2022-03-01\"),\n" +
                        " partition p2 values less than (\"2022-04-01\"))\n" +
                        "distributed by hash(key2) buckets 2\n" +
                        "properties('datacache.enable' = 'true','enable_async_write_back' = 'false');"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "multi_partition_aggregate_key_cache");
            // check table property
            StorageInfo storageInfo = lakeTable.getTableProperty().getStorageInfo();
            Assertions.assertTrue(storageInfo.isEnableDataCache());
            // check partition property
            long partition1Id = lakeTable.getPartition("p1").getId();
            DataCacheInfo partition1DataCacheInfo =
                    lakeTable.getPartitionInfo().getDataCacheInfo(partition1Id);
            Assertions.assertTrue(partition1DataCacheInfo.isEnabled());
            long partition2Id = lakeTable.getPartition("p2").getId();
            DataCacheInfo partition2DataCacheInfo =
                    lakeTable.getPartitionInfo().getDataCacheInfo(partition2Id);
            Assertions.assertTrue(partition2DataCacheInfo.isEnabled());
            Assertions.assertFalse(partition2DataCacheInfo.isAsyncWriteBack());
        }

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.multi_partition_unique_key_cache (key1 int, key2 varchar(10), v bigint)\n" +
                        "unique key (key1, key2)\n" +
                        "partition by range(key1)\n" +
                        "(partition p1 values less than (\"10\"),\n" +
                        " partition p2 values less than (\"20\") ('datacache.enable' = 'false'))\n" +
                        "distributed by hash(key2) buckets 1\n" +
                        "properties('replication_num' = '1');"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "multi_partition_unique_key_cache");
            // check table property
            StorageInfo storageInfo = lakeTable.getTableProperty().getStorageInfo();
            // enabled by default if property key `datacache.enable` is absent
            Assertions.assertTrue(storageInfo.isEnableDataCache());
            // check partition property
            long partition1Id = lakeTable.getPartition("p1").getId();
            DataCacheInfo partition1DataCacheInfo =
                    lakeTable.getPartitionInfo().getDataCacheInfo(partition1Id);
            Assertions.assertTrue(partition1DataCacheInfo.isEnabled());
            long partition2Id = lakeTable.getPartition("p2").getId();
            DataCacheInfo partition2DataCacheInfo =
                    lakeTable.getPartitionInfo().getDataCacheInfo(partition2Id);
            Assertions.assertFalse(partition2DataCacheInfo.isEnabled());
        }

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.auto_partition (key1 date, key2 varchar(10), key3 int)\n" +
                        "partition by date_trunc(\"day\", key1) distributed by hash(key2) buckets 3;"));

        // `day` function is not supported
        ExceptionChecker.expectThrows(AnalysisException.class, () -> createTable(
                "create table lake_test.auto_partition (key1 date, key2 varchar(10), key3 int)\n" +
                        "partition by day(key1) distributed by hash(key2) buckets 3;"));
    }

    @Test
    public void testCreateLakeTableEnablePersistentIndex() throws Exception {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.table_with_persistent_index\n" +
                        "(c0 int, c1 string, c2 int, c3 bigint)\n" +
                        "PRIMARY KEY(c0)\n" +
                        "distributed by hash(c0) buckets 2\n" +
                        "properties('enable_persistent_index' = 'true');"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "table_with_persistent_index");
            // check table persistentIndex
            boolean enablePersistentIndex = lakeTable.enablePersistentIndex();
            Assertions.assertTrue(enablePersistentIndex);
            // check table persistentIndexType
            String indexType = lakeTable.getPersistentIndexTypeString();
            Assertions.assertEquals(indexType, "CLOUD_NATIVE");

            String sql = "show create table lake_test.table_with_persistent_index";
            ShowCreateTableStmt showCreateTableStmt =
                    (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
            ShowResultSet resultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);

            Assertions.assertFalse(resultSet.getResultRows().isEmpty());
        }

        UtFrameUtils.addMockComputeNode(50001);
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "Cannot create cloud native table with local persistent index",
                () -> createTable(
                "create table lake_test.table_with_persistent_index2\n" +
                        "(c0 int, c1 string, c2 int, c3 bigint)\n" +
                        "PRIMARY KEY(c0)\n" +
                        "distributed by hash(c0) buckets 2\n" +
                        "properties('enable_persistent_index' = 'true', 'persistent_index_type' = 'LOCAL');"));

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.table_in_be_and_cn\n" +
                        "(c0 int, c1 string, c2 int, c3 bigint)\n" +
                        "PRIMARY KEY(c0)\n" +
                        "distributed by hash(c0) buckets 2"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "table_in_be_and_cn");
            // check table persistentIndex
            boolean enablePersistentIndex = lakeTable.enablePersistentIndex();
            Assertions.assertTrue(enablePersistentIndex);

            String sql = "show create table lake_test.table_in_be_and_cn";
            ShowCreateTableStmt showCreateTableStmt =
                    (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
            ShowResultSet resultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);

            Assertions.assertNotEquals(0, resultSet.getResultRows().size());
        }
    }

    @Test
    public void testCreateLakeTableException() {
        // storage_cache disabled but enable_async_write_back = true
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "enable_async_write_back is disabled since version 3.1.4",
                () -> createTable(
                        "create table lake_test.single_partition_invalid_cache_property (key1 int, key2 varchar(10))\n" +
                                "distributed by hash(key1) buckets 3\n" +
                                " properties('datacache.enable' = 'false', 'enable_async_write_back' = 'true');"));

        // enable_async_write_back disabled
        ExceptionChecker.expectThrowsWithMsg(DdlException.class,
                "enable_async_write_back is disabled since version 3.1.4",
                () -> createTable(
                        "create table lake_test.single_partition_invalid_cache_property (key1 int, key2 varchar(10))\n" +
                                "distributed by hash(key1) buckets 3\n" +
                                " properties('datacache.enable' = 'true', 'enable_async_write_back' = 'true');"));
    }

    @Test
    public void testExplainRowCount() throws Exception {
        new MockUp<Partition>() {
            @Mock
            public boolean hasData() {
                return true;
            }
        };

        new MockUp<LakeTablet>() {
            @Mock
            public long getRowCount(long version) {
                return 2L;
            }
        };

        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.duplicate_key_rowcount (key1 int, key2 varchar(10))\n" +
                        "distributed by hash(key1) buckets 3 properties('replication_num' = '1');"));
        checkLakeTable("lake_test", "duplicate_key_rowcount");

        // check explain result
        String sql = "select * from lake_test.duplicate_key_rowcount";
        String plan = UtFrameUtils.getVerboseFragmentPlan(connectContext, sql);
        Assertions.assertTrue(plan.contains("actualRows=6"));
    }

    @Test
    public void testCreateLakeTableListPartition() throws StarRocksException {
        // list partition
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.list_partition (dt date not null, key2 varchar(10))\n" +
                        "PARTITION BY LIST (dt) (PARTITION p1 VALUES IN ((\"2022-04-01\")),\n" +
                        "PARTITION p2 VALUES IN ((\"2022-04-02\")),\n" +
                        "PARTITION p3 VALUES IN ((\"2022-04-03\")))\n" +
                        "distributed by hash(dt) buckets 3;"));
        checkLakeTable("lake_test", "list_partition");

        // auto list partition
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.auto_list_partition (dt date not null, key2 varchar(10))\n" +
                        "PARTITION BY (dt) \n" +
                        "distributed by hash(dt) buckets 3;"));
        checkLakeTable("lake_test", "auto_list_partition");
    }

    @Test
    public void testCreateLakeTableEnableCloudNativePersistentIndex() throws Exception {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.table_with_cloud_native_persistent_index\n" +
                        "(c0 int, c1 string, c2 int, c3 bigint)\n" +
                        "PRIMARY KEY(c0)\n" +
                        "distributed by hash(c0) buckets 2\n" +
                        "properties('enable_persistent_index' = 'true', 'persistent_index_type' = 'cloud_native');"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "table_with_cloud_native_persistent_index");
            // check table persistentIndex
            boolean enablePersistentIndex = lakeTable.enablePersistentIndex();
            Assertions.assertTrue(enablePersistentIndex);
            // check table persistentIndexType
            String indexType = lakeTable.getPersistentIndexTypeString();
            Assertions.assertEquals("CLOUD_NATIVE", indexType);

            String sql = "show create table lake_test.table_with_cloud_native_persistent_index";
            ShowCreateTableStmt showCreateTableStmt =
                    (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
            ShowResultSet resultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);

            Assertions.assertNotEquals(0, resultSet.getResultRows().size());
        }
    }

    @Test
    public void testCreateTableWithRollUp() throws Exception {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.table_with_rollup\n" +
                        "(c0 int, c1 string, c2 int, c3 bigint)\n" +
                        "DUPLICATE KEY(c0)\n" +
                        "distributed by hash(c0) buckets 2\n" +
                        "ROLLUP (mv1 (c0, c1));"));
        {
            LakeTable lakeTable = getLakeTable("lake_test", "table_with_rollup");
            Assertions.assertEquals(2, lakeTable.getShardGroupIds().size());

            Assertions.assertEquals(2, lakeTable.getAllPartitions().stream().findAny().
                    get().getDefaultPhysicalPartition().getMaterializedIndices(MaterializedIndex.IndexExtState.ALL).size());

        }
    }
    @Test
    public void testRestoreColumnUniqueId() throws Exception {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.test_unique_id\n" +
                        "(c0 int, c1 string, c2 int, c3 bigint)\n" +
                        "PRIMARY KEY(c0)\n" +
                        "distributed by hash(c0) buckets 2\n" +
                        "properties('enable_persistent_index' = 'true', 'persistent_index_type' = 'cloud_native');"));
        LakeTable lakeTable = getLakeTable("lake_test", "test_unique_id");
        {
            // case 1:
            // table created on version v3.2, then upgraded to v3.3 upwards,
            // all unique ids include max unique id is -1
            lakeTable.setMaxColUniqueId(-1);
            for (Column column : lakeTable.getColumns()) {
                column.setUniqueId(-1);
            }
            lakeTable.gsonPostProcess();
            Assertions.assertEquals(3, lakeTable.getMaxColUniqueId());
            Assertions.assertEquals(0, lakeTable.getColumn("c0").getUniqueId());
            Assertions.assertEquals(1, lakeTable.getColumn("c1").getUniqueId());
            Assertions.assertEquals(2, lakeTable.getColumn("c2").getUniqueId());
            Assertions.assertEquals(3, lakeTable.getColumn("c3").getUniqueId());
        }

        {
            // case 1:
            // 1. table created on version v3.3
            // 2. cluster downgraded to v3.2
            // 3. add one column on version v3.2, the column's unique id is -1
            // 4. cluster upgraded to v3.3
            lakeTable.setMaxColUniqueId(2);
            lakeTable.getColumns().get(3).setUniqueId(-1);

            lakeTable.gsonPostProcess();
            Assertions.assertEquals(3, lakeTable.getMaxColUniqueId());
            Assertions.assertEquals(0, lakeTable.getColumn("c0").getUniqueId());
            Assertions.assertEquals(1, lakeTable.getColumn("c1").getUniqueId());
            Assertions.assertEquals(2, lakeTable.getColumn("c2").getUniqueId());
            Assertions.assertEquals(3, lakeTable.getColumn("c3").getUniqueId());
        }
    }

    @Test
    public void testCreateTableWithUKFK() {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE lake_test.region (\n" +
                        "  r_regionkey  INT NOT NULL,\n" +
                        "  r_name       VARCHAR(25) NOT NULL,\n" +
                        "  r_comment    VARCHAR(152)\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`r_regionkey`)\n" +
                        "DISTRIBUTED BY HASH(`r_regionkey`) BUCKETS 1\n" +
                        "PROPERTIES (\n" +
                        " 'replication_num' = '1',\n " +
                        " 'unique_constraints' = 'r_regionkey'\n" +
                        ");"));
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "CREATE TABLE lake_test.nation (\n" +
                        "  n_nationkey INT(11) NOT NULL,\n" +
                        "  n_name      VARCHAR(25) NOT NULL,\n" +
                        "  n_regionkey INT(11) NOT NULL,\n" +
                        "  n_comment   VARCHAR(152) NULL\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`N_NATIONKEY`)\n" +
                        "DISTRIBUTED BY HASH(`N_NATIONKEY`) BUCKETS 1\n" +
                        "PROPERTIES (\n" +
                        " 'replication_num' = '1',\n" +
                        " 'unique_constraints' = 'n_nationkey',\n" +
                        " 'foreign_key_constraints' = '(n_regionkey) references region(r_regionkey)'\n" +
                        ");"));
        LakeTable region = getLakeTable("lake_test", "region");
        LakeTable nation = getLakeTable("lake_test", "nation");
        Map<String, String> regionProps = region.getProperties();
        Assertions.assertTrue(regionProps.containsKey(PropertyAnalyzer.PROPERTIES_UNIQUE_CONSTRAINT));
        Map<String, String> nationProps = nation.getProperties();
        Assertions.assertTrue(nationProps.containsKey(PropertyAnalyzer.PROPERTIES_UNIQUE_CONSTRAINT));
        Assertions.assertTrue(nationProps.containsKey(PropertyAnalyzer.PROPERTIES_FOREIGN_KEY_CONSTRAINT));
    }

    @Test
    public void testCreateTableWithFileBundling() throws Exception {
        ExceptionChecker.expectThrowsNoException(() -> createTable(
                "create table lake_test.dup_test_file_bundling (key1 int, key2 varchar(10))\n" +
                        "distributed by hash(key1) buckets 3\n" +
                        "properties('replication_num' = '1', 'file_bundling' = 'true');"));
        checkLakeTable("lake_test", "dup_test_file_bundling");

        String sql = "show create table lake_test.dup_test_file_bundling";
        ShowCreateTableStmt showCreateTableStmt =
                (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        ShowResultSet resultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);
        Assertions.assertFalse(resultSet.getResultRows().isEmpty());
    }

    @Test
    public void testRangeTableWithRetentionCondition2() throws Exception {
        ExceptionChecker.expectThrowsNoException(() -> createTable("CREATE TABLE lake_test.r1 \n" +
                "(\n" +
                "    dt date,\n" +
                "    k2 int,\n" +
                "    v1 int \n" +
                ")\n" +
                "PARTITION BY RANGE(dt)\n" +
                "(\n" +
                "    PARTITION p0 values [('2024-01-29'),('2024-01-30')),\n" +
                "    PARTITION p1 values [('2024-01-30'),('2024-01-31')),\n" +
                "    PARTITION p2 values [('2024-01-31'),('2024-02-01')),\n" +
                "    PARTITION p3 values [('2024-02-01'),('2024-02-02')) \n" +
                ")\n" +
                "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "'replication_num' = '1',\n" +
                "'partition_retention_condition' = 'dt > current_date() - interval 1 month'\n" +
                ")"));
        LakeTable r1 = getLakeTable("lake_test", "r1");
        String retentionCondition = r1.getTableProperty().getPartitionRetentionCondition();
        Assertions.assertEquals("dt > current_date() - interval 1 month", retentionCondition);

        String sql = "show create table lake_test.r1";
        ShowCreateTableStmt showCreateTableStmt =
                (ShowCreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        ShowResultSet resultSet = ShowExecutor.execute(showCreateTableStmt, connectContext);
        List<List<String>> result = resultSet.getResultRows();
        Assertions.assertTrue(result.size() == 1);
        Assertions.assertTrue(result.get(0).size() == 2);
        final String expect = "CREATE TABLE `r1` (\n" +
                "  `dt` date NULL COMMENT \"\",\n" +
                "  `k2` int(11) NULL COMMENT \"\",\n" +
                "  `v1` int(11) NULL COMMENT \"\"\n" +
                ") ENGINE=OLAP \n" +
                "DUPLICATE KEY(`dt`, `k2`, `v1`)\n" +
                "COMMENT \"OLAP\"\n" +
                "PARTITION BY RANGE(`dt`)\n" +
                "(PARTITION p0 VALUES [(\"2024-01-29\"), (\"2024-01-30\")),\n" +
                "PARTITION p1 VALUES [(\"2024-01-30\"), (\"2024-01-31\")),\n" +
                "PARTITION p2 VALUES [(\"2024-01-31\"), (\"2024-02-01\")),\n" +
                "PARTITION p3 VALUES [(\"2024-02-01\"), (\"2024-02-02\")))\n" +
                "DISTRIBUTED BY HASH(`k2`) BUCKETS 3 \n" +
                "PROPERTIES (\n" +
                "\"compression\" = \"LZ4\",\n" +
                "\"datacache.enable\" = \"true\",\n" +
                "\"enable_async_write_back\" = \"false\",\n" +
                "\"file_bundling\" = \"true\",\n" +
                "\"partition_retention_condition\" = \"dt > current_date() - interval 1 month\",\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"storage_volume\" = \"builtin_storage_volume\"\n" +
                ");";
        Assertions.assertTrue(result.get(0).get(1).equals(expect));
    }
}
