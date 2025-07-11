// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

public class ConfigTest {
    private final Config config = new Config();

    private static class ConfigForTest extends ConfigBase {
        @ConfField(mutable = true, aliases = {"schedule_slot_num_per_path", "schedule_slot_num_per_path_only_for_test"})
        public static int tablet_sched_slot_num_per_path = 2;
    }

    @BeforeEach
    public void setUp() throws Exception {
        URL resource = getClass().getClassLoader().getResource("conf/config_test.properties");
        assert resource != null;
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
    }

    @Test
    public void testGetConfigFromPropertyFile() throws DdlException {
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("tablet_sched_slot_num_per_path", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("3", configs.get(0).get(2));
    }

    @Test
    public void testConfigGetCompatibleWithOldName() throws Exception {
        URL resource = getClass().getClassLoader().getResource("conf/config_test2.properties");
        assert resource != null;
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("schedule_slot_num_per_path", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals(1, configs.size());
        Assertions.assertEquals("3", configs.get(0).get(2));
        Assertions.assertEquals(3, Config.tablet_sched_slot_num_per_path);
        Assertions.assertEquals("tablet_sched_slot_num_per_path", configs.get(0).get(0));
        Assertions.assertTrue(configs.get(0).get(1).contains("schedule_slot_num_per_path"));
    }

    @Test
    public void testMultiAlias() throws Exception {
        ConfigForTest configForTest = new ConfigForTest();
        URL resource = getClass().getClassLoader().getResource("conf/config_test3.properties");
        assert resource != null;
        configForTest.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("schedule_slot_num_per_path_only_for_test", false);
        List<List<String>> configs = ConfigForTest.getConfigInfo(matcher);
        Assertions.assertEquals(1, configs.size());
        Assertions.assertEquals("5", configs.get(0).get(2));
        Assertions.assertEquals(5, ConfigForTest.tablet_sched_slot_num_per_path);
        Assertions.assertTrue(configs.get(0).get(1).contains("schedule_slot_num_per_path_only_for_test"));
    }

    @Test
    public void testConfigSetCompatibleWithOldName() throws Exception {
        Config.setMutableConfig("schedule_slot_num_per_path", "4", false, "");
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("schedule_slot_num_per_path", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("4", configs.get(0).get(2));
        Assertions.assertEquals(4, Config.tablet_sched_slot_num_per_path);
    }

    @Test
    public void testMutableConfig() throws Exception {
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("adaptive_choose_instances_threshold", false);
        List<List<String>> configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("99", configs.get(0).get(2));

        PatternMatcher matcher2 = PatternMatcher.createMysqlPattern("agent_task_resend_wait_time_ms", false);
        List<List<String>> configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("998", configs2.get(0).get(2));

        Config.setMutableConfig("adaptive_choose_instances_threshold", "98", true, "root");
        configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("98", configs.get(0).get(2));
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);

        Config.setMutableConfig("agent_task_resend_wait_time_ms", "999", true, "root");
        configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("999", configs2.get(0).get(2));
        Assertions.assertEquals(999, Config.agent_task_resend_wait_time_ms);
        // Write config twice
        Config.setMutableConfig("agent_task_resend_wait_time_ms", "1000", true, "root");
        configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("1000", configs2.get(0).get(2));
        Assertions.assertEquals(1000, Config.agent_task_resend_wait_time_ms);

        // Reload from file
        URL resource = getClass().getClassLoader().getResource("conf/config_test.properties");
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        configs = Config.getConfigInfo(matcher);
        configs2 = Config.getConfigInfo(matcher2);
        Assertions.assertEquals("98", configs.get(0).get(2));
        Assertions.assertEquals("1000", configs2.get(0).get(2));
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);
        Assertions.assertEquals(1000, Config.agent_task_resend_wait_time_ms);
    }

    @Test
    public void testDisableStoreConfig() throws Exception {
        Config.setMutableConfig("adaptive_choose_instances_threshold", "98", false, "");
        PatternMatcher matcher = PatternMatcher.createMysqlPattern("adaptive_choose_instances_threshold", false);
        List<List<String>>  configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("98", configs.get(0).get(2));
        Assertions.assertEquals(98, Config.adaptive_choose_instances_threshold);

        // Reload from file
        URL resource = getClass().getClassLoader().getResource("conf/config_test.properties");
        config.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        configs = Config.getConfigInfo(matcher);
        Assertions.assertEquals("99", configs.get(0).get(2));
        Assertions.assertEquals(99, Config.adaptive_choose_instances_threshold);
    }

    private static class ConfigForArray extends ConfigBase {

        @ConfField(mutable = true)
        public static short[] prop_array_short = new short[] {1, 1};
        @ConfField(mutable = true)
        public static int[] prop_array_int = new int[] {2, 2};
        @ConfField(mutable = true)
        public static long[] prop_array_long = new long[] {3L, 3L};
        @ConfField(mutable = true)
        public static double[] prop_array_double = new double[] {1.1, 1.1};
        @ConfField(mutable = true)
        public static String[] prop_array_string = new String[] {"1", "2"};
    }

    @Test
    public void testConfigArray() throws Exception {
        ConfigForArray configForArray = new ConfigForArray();
        URL resource = getClass().getClassLoader().getResource("conf/config_test3.properties");
        assert resource != null;
        configForArray.init(Paths.get(resource.toURI()).toFile().getAbsolutePath());
        List<List<String>> configs = ConfigForArray.getConfigInfo(null);
        Assertions.assertEquals("[1, 1]", configs.get(0).get(2));
        Assertions.assertEquals("short[]", configs.get(0).get(3));
        Assertions.assertEquals("[2, 2]", configs.get(1).get(2));
        Assertions.assertEquals("int[]", configs.get(1).get(3));
        Assertions.assertEquals("[3, 3]", configs.get(2).get(2));
        Assertions.assertEquals("long[]", configs.get(2).get(3));
        Assertions.assertEquals("[1.1, 1.1]", configs.get(3).get(2));
        Assertions.assertEquals("double[]", configs.get(3).get(3));
        Assertions.assertEquals("[1, 2]", configs.get(4).get(2));
        Assertions.assertEquals("String[]", configs.get(4).get(3));

        // check set an empty array works
        ConfigForArray.setConfigField(ConfigForArray.getAllMutableConfigs().get("prop_array_long"), "");
        configs = ConfigForArray.getConfigInfo(null);
        Assertions.assertEquals("[]", configs.get(2).get(2));
    }
}