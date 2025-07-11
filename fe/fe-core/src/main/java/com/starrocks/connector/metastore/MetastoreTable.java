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

package com.starrocks.connector.metastore;

import com.starrocks.credential.CloudConfiguration;

public class MetastoreTable {
    private final String dbName;
    private final String tableName;
    private final String tableLocation;
    private final long createTime;
    private final CloudConfiguration cloudConfiguration;

    public MetastoreTable(String dbName, String tableName, String tableLocation, long createTime) {
        this(dbName, tableName, tableLocation, createTime, null);
    }

    public MetastoreTable(String dbName, String tableName, String tableLocation, long createTime,
                          CloudConfiguration cloudConfiguration) {
        this.dbName = dbName;
        this.tableName = tableName;
        this.tableLocation = tableLocation;
        this.createTime = createTime;
        this.cloudConfiguration = cloudConfiguration;
    }

    public String getDbName() {
        return dbName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getTableLocation() {
        return tableLocation;
    }

    public long getCreateTime() {
        return createTime;
    }

    public CloudConfiguration getCloudConfiguration() {
        return cloudConfiguration;
    }
}
