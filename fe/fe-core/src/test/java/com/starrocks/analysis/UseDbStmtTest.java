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

package com.starrocks.analysis;

import com.starrocks.catalog.Database;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.CatalogMgr;
import com.starrocks.server.MetadataMgr;
import com.starrocks.sql.analyzer.AnalyzeTestUtil;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class UseDbStmtTest {
    private static StarRocksAssert starRocksAssert;
    private static ConnectContext ctx;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        AnalyzeTestUtil.init();
        starRocksAssert = new StarRocksAssert();
        starRocksAssert.withDatabase("db1").useDatabase("tbl1");
        starRocksAssert.withDatabase("db");
        ctx = new ConnectContext(null);
        ctx.setGlobalStateMgr(AccessTestUtil.fetchAdminCatalog());
    }

    @Test
    public void testParserAndAnalyzer() {
        String sql = "USE db1";
        AnalyzeTestUtil.analyzeSuccess(sql);

        String sql_2 = "USE default_catalog.db1";
        AnalyzeTestUtil.analyzeSuccess(sql_2);

        String sql_3 = "USE hive_catalog.hive_db";
        AnalyzeTestUtil.analyzeSuccess(sql_3);

        String sql_4 = "USE hive_catalog.hive_db.hive_table";
        AnalyzeTestUtil.analyzeFail(sql_4);
    }

    @Test
    public void testUse(@Mocked CatalogMgr catalogMgr,
                        @Mocked MetadataMgr metadataMgr) throws Exception {
        Database db = new Database(1, "db");
        new Expectations() {
            {
                CatalogMgr.isInternalCatalog("default_catalog");
                result = true;

                catalogMgr.catalogExists("default_catalog");
                result = true;
                minTimes = 0;

                metadataMgr.getDb((ConnectContext) any, "default_catalog", "db");
                result = db;
                minTimes = 0;
            }
        };

        ctx.setQueryId(UUIDUtil.genUUID());
        ctx.setCurrentUserIdentity(UserIdentity.ROOT);
        ctx.setCurrentRoleIds(UserIdentity.ROOT);
        StatementBase statement = SqlParser.parseSingleStatement("use default_catalog.db",
                ctx.getSessionVariable().getSqlMode());

        StmtExecutor executor = new StmtExecutor(ctx, statement);
        executor.execute();

        Assertions.assertEquals("default_catalog", ctx.getCurrentCatalog());
        Assertions.assertEquals("db", ctx.getDatabase());
    }
}