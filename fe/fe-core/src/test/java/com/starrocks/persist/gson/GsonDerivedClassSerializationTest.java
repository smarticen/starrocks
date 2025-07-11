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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/persist/gson/GsonDerivedClassSerializationTest.java

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

package com.starrocks.persist.gson;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.gson.GsonUtils.HiddenAnnotationExclusionStrategy;
import com.starrocks.persist.gson.GsonUtils.ProcessHookTypeAdapterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

/*
 * This unit test shows how to serialize and deserialize inherited class.
 *
 * ParentClass is the parent class of 2 derived classes:
 *      ChildClassA
 *      ChildClassB
 *
 * User need to create a RuntimeTypeAdapterFactory for ParentClass and
 * register 2 derived classes to the factory. And then register the factory
 * to the GsonBuilder to create GSON instance.
 *
 *
 *
 */
public class GsonDerivedClassSerializationTest {
    private static String fileName = "./GsonDerivedClassSerializationTest";

    @AfterEach
    public void tearDown() {
        File file = new File(fileName);
        file.delete();
    }

    public static class ParentClass implements Writable {
        @SerializedName(value = "flag")
        public int flag = 0;

        public ParentClass(int flag) {
            this.flag = flag;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = TEST_GSON.toJson(this);
            System.out.println("write: " + json);
            Text.writeString(out, json);
        }

        public static ParentClass read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println("read: " + json);
            return TEST_GSON.fromJson(json, ParentClass.class);
        }
    }

    public static class ChildClassA extends ParentClass implements GsonPostProcessable {
        @SerializedName(value = "tag")
        public String tagA;

        public String postTagA;

        public ChildClassA(int flag, String tag) {
            super(flag);
            this.tagA = tag;
        }

        @Override
        public void gsonPostProcess() {
            this.postTagA = "after post";

        }
    }

    public static class ChildClassB extends ParentClass {
        @SerializedName(value = "mapB")
        public Map<Long, String> mapB = Maps.newConcurrentMap();

        public ChildClassB(int flag) {
            super(flag);
            this.mapB.put(1L, "B1");
            this.mapB.put(2L, "B2");
        }
    }

    public static class WrapperClass implements Writable {
        @SerializedName(value = "tag")
        public ParentClass clz;

        public WrapperClass() {
            clz = new ChildClassA(1, "child1");
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = TEST_GSON.toJson(this);
            System.out.println("write: " + json);
            Text.writeString(out, json);
        }

        public static WrapperClass read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println("read: " + json);
            return TEST_GSON.fromJson(json, WrapperClass.class);
        }
    }

    // Class A = ParentClass + ChildClassA
    public static class ClassA implements Writable {
        @SerializedName(value = "flag")
        public int flag = 0;
        @SerializedName(value = "tag")
        public String tagA;

        public String postTagA = "post tag a";

        public ClassA(int flag, String tag) {
            this.flag = flag;
            this.tagA = tag;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            String json = TEST_GSON.toJson(this);
            System.out.println("write ClassA: " + json);
            Text.writeString(out, json);
        }

        public static ClassA read(DataInput in) throws IOException {
            String json = Text.readString(in);
            System.out.println("read ClassA: " + json);
            return TEST_GSON.fromJson(json, ClassA.class);
        }
    }

    private static RuntimeTypeAdapterFactory<ParentClass> runtimeTypeAdapterFactory = RuntimeTypeAdapterFactory
            // the "clazz" is a custom defined name
            .of(ParentClass.class, "clazz")
            // register 2 derived classes, the second parameter will be the value of "clazz"
            .registerSubtype(ChildClassA.class, ChildClassA.class.getSimpleName(), true)
            .registerSubtype(ChildClassB.class, ChildClassB.class.getSimpleName());

    private static Gson TEST_GSON = new GsonBuilder()
            .addSerializationExclusionStrategy(new HiddenAnnotationExclusionStrategy())
            .enableComplexMapKeySerialization()
            // register the RuntimeTypeAdapterFactory
            .registerTypeAdapterFactory(runtimeTypeAdapterFactory)
            .registerTypeAdapterFactory(new ProcessHookTypeAdapterFactory())
            .create();

    @Test
    public void testDerivedClassA() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        ChildClassA childClassA = new ChildClassA(1, "A");
        childClassA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        ParentClass parentClass = ParentClass.read(in);
        Assertions.assertTrue(parentClass instanceof ChildClassA);
        Assertions.assertEquals(1, ((ChildClassA) parentClass).flag);
        Assertions.assertEquals("A", ((ChildClassA) parentClass).tagA);
        Assertions.assertEquals("after post", ((ChildClassA) parentClass).postTagA);
    }

    @Test
    public void testDerivedClassB() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        ChildClassB childClassB = new ChildClassB(2);
        childClassB.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        ParentClass parentClass = ParentClass.read(in);
        Assertions.assertTrue(parentClass instanceof ChildClassB);
        Assertions.assertEquals(2, ((ChildClassB) parentClass).flag);
        Assertions.assertEquals(2, ((ChildClassB) parentClass).mapB.size());
        Assertions.assertEquals("B1", ((ChildClassB) parentClass).mapB.get(1L));
        Assertions.assertEquals("B2", ((ChildClassB) parentClass).mapB.get(2L));
    }

    @Test
    public void testWrapperClass() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        WrapperClass wrapperClass = new WrapperClass();
        wrapperClass.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        WrapperClass readWrapperClass = WrapperClass.read(in);
        Assertions.assertEquals(1, ((ChildClassA) readWrapperClass.clz).flag);
    }

    @Test
    public void testDeserializeChildClassAFromClassA() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        ClassA classA = new ClassA(2, "B");
        classA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        ParentClass parentClass = ParentClass.read(in);
        Assertions.assertTrue(parentClass instanceof ChildClassA);
        Assertions.assertEquals(2, ((ChildClassA) parentClass).flag);
        Assertions.assertEquals("B", ((ChildClassA) parentClass).tagA);
        Assertions.assertEquals("after post", ((ChildClassA) parentClass).postTagA);
    }

    @Test
    public void testDeserializeClassAFromChildClassA() throws IOException {
        // 1. Write objects to file
        File file = new File(fileName);
        file.createNewFile();
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));

        ChildClassA childClassA = new ChildClassA(3, "C");
        childClassA.write(out);
        out.flush();
        out.close();

        // 2. Read objects from file
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        ClassA classA = ClassA.read(in);
        Assertions.assertEquals(3, classA.flag);
        Assertions.assertEquals("C", classA.tagA);
        Assertions.assertEquals(null, classA.postTagA);
    }
}
