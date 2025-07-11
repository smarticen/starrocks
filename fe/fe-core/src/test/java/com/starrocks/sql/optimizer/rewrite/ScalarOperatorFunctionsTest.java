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

package com.starrocks.sql.optimizer.rewrite;

import com.google.common.collect.Lists;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScalarOperatorFunctionsTest {
    private static ConstantOperator O_DT_20101102_183010;

    private static ConstantOperator O_DT_20101202_023010;

    private static ConstantOperator O_DT_20150323_092355;

    private static ConstantOperator O_TI_10;
    private static ConstantOperator O_SI_10;
    private static ConstantOperator O_INT_1;
    private static ConstantOperator O_INT_10;
    private static ConstantOperator O_FLOAT_100;
    private static ConstantOperator O_DOUBLE_100;
    private static ConstantOperator O_BI_100;
    private static ConstantOperator O_BI_3;
    private static ConstantOperator O_BI_10;
    private static ConstantOperator O_BI_131;
    private static ConstantOperator O_BI_NEG_3;
    private static ConstantOperator O_LI_100;
    private static ConstantOperator O_LI_NEG_100;
    private static ConstantOperator O_DECIMAL_100;
    private static ConstantOperator O_DECIMAL32P7S2_100;
    private static ConstantOperator O_DECIMAL32P9S0_100;
    private static ConstantOperator O_DECIMAL64P18S15_100;
    private static ConstantOperator O_DECIMAL64P15S10_100;
    private static ConstantOperator O_DECIMAL128P38S20_100;
    private static ConstantOperator O_DECIMAL128P30S2_100;

    @BeforeEach
    public void setUp() throws AnalysisException {
        O_DT_20101102_183010 = ConstantOperator.createDatetime(LocalDateTime.of(2010, 11, 2, 18, 30, 10));
        O_DT_20101202_023010 = ConstantOperator.createDatetime(LocalDateTime.of(2010, 12, 2, 2, 30, 10));
        O_DT_20150323_092355 = ConstantOperator.createDatetime(LocalDateTime.of(2015, 3, 23, 9, 23, 55));
        O_TI_10 = ConstantOperator.createTinyInt((byte) 10);
        O_SI_10 = ConstantOperator.createSmallInt((short) 10);
        O_INT_1 = ConstantOperator.createInt(1);
        O_INT_10 = ConstantOperator.createInt(10);
        O_FLOAT_100 = ConstantOperator.createFloat(100);
        O_DOUBLE_100 = ConstantOperator.createFloat(100);
        O_BI_100 = ConstantOperator.createBigint(100);
        O_BI_3 = ConstantOperator.createBigint(3);
        O_BI_10 = ConstantOperator.createBigint(10);
        O_BI_131 = ConstantOperator.createBigint(131);
        O_BI_NEG_3 = ConstantOperator.createBigint(-3);
        O_LI_100 = ConstantOperator.createLargeInt(new BigInteger("100"));
        O_LI_NEG_100 = ConstantOperator.createLargeInt(new BigInteger("-100"));
        O_DECIMAL_100 = ConstantOperator.createDecimal(new BigDecimal(100), Type.DECIMALV2);
        O_DECIMAL32P7S2_100 = ConstantOperator.createDecimal(new BigDecimal(100),
                ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL32, 7, 2));
        O_DECIMAL32P9S0_100 = ConstantOperator.createDecimal(new BigDecimal(100),
                ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL32, 9, 0));
        O_DECIMAL64P15S10_100 = ConstantOperator.createDecimal(new BigDecimal(100),
                ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL64, 15, 10));
        O_DECIMAL64P18S15_100 = ConstantOperator.createDecimal(new BigDecimal(100),
                ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL64, 18, 15));
        O_DECIMAL128P38S20_100 = ConstantOperator.createDecimal(new BigDecimal(100),
                ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL128, 38, 20));
        O_DECIMAL128P30S2_100 = ConstantOperator.createDecimal(new BigDecimal(100),
                ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL128, 30, 2));
    }

    @Test
    public void xxHash64() {
        ConstantOperator operator = ScalarOperatorFunctions.xxHash64(ConstantOperator.createNull(Type.VARCHAR));
        assertTrue(operator.isNull());
        assertEquals(Type.BIGINT, operator.getType());

        assertEquals(-2612172575022167352L, ScalarOperatorFunctions.xxHash64(
                ConstantOperator.createVarchar("NULL")).getBigint());

        assertEquals(8354710922730016039L, ScalarOperatorFunctions.xxHash64(
                ConstantOperator.createVarchar("41c630d2-e339-380b-a65a-f295ca422070")).getBigint());

        assertEquals(2897331577432926379L, ScalarOperatorFunctions.xxHash64(
                ConstantOperator.createVarchar("41c630d2-e339-380b-a65a-f295ca422070"),
                ConstantOperator.createVarchar("cd824fbe-8134-8015-7f4a-000004ffffff")).getBigint());
    }

    @Test
    public void timeDiff() {
        assertEquals(-2534400.0, ScalarOperatorFunctions.timeDiff(O_DT_20101102_183010, O_DT_20101202_023010).getTime(),
                1);
    }

    @Test
    public void dateDiff() {
        assertEquals(-1602,
                ScalarOperatorFunctions.dateDiff(O_DT_20101102_183010, O_DT_20150323_092355).getInt());

        assertEquals(-1572, ScalarOperatorFunctions.dateDiff(O_DT_20101202_023010, O_DT_20150323_092355).getInt());
    }

    @Test
    public void to_days() {
        assertEquals(734443, ScalarOperatorFunctions.to_days(O_DT_20101102_183010).getInt());
    }

    @Test
    public void dayofweek() {
        ConstantOperator testDate = ConstantOperator.createDatetime(LocalDateTime.of(2024, 2, 3, 13, 4, 5));
        assertEquals(7,
                ScalarOperatorFunctions.dayofweek(testDate).getInt());

        testDate = ConstantOperator.createDatetime(LocalDateTime.of(2024, 2, 4, 13, 4, 5));
        assertEquals(1,
                ScalarOperatorFunctions.dayofweek(testDate).getInt());

        testDate = ConstantOperator.createDatetime(LocalDateTime.of(2024, 2, 5, 13, 4, 5));
        assertEquals(2,
                ScalarOperatorFunctions.dayofweek(testDate).getInt());
    }

    @Test
    public void yearsAdd() {
        assertEquals("2025-03-23T09:23:55",
                ScalarOperatorFunctions.yearsAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void quartersAdd() {
        assertEquals("2015-06-23T09:23:55",
                ScalarOperatorFunctions.quartersAdd(O_DT_20150323_092355, O_INT_1).getDatetime().toString());
    }

    @Test
    public void monthsAdd() {
        assertEquals("2016-01-23T09:23:55",
                ScalarOperatorFunctions.monthsAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void weeksAdd() {
        assertEquals("2015-06-01T09:23:55",
                ScalarOperatorFunctions.weeksAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void daysAdd() {
        assertEquals("2015-04-02T09:23:55",
                ScalarOperatorFunctions.daysAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void hoursAdd() {
        assertEquals("2015-03-23T19:23:55",
                ScalarOperatorFunctions.hoursAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void minutesAdd() {
        assertEquals("2015-03-23T09:33:55",
                ScalarOperatorFunctions.minutesAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void secondsAdd() {
        assertEquals("2015-03-23T09:24:05",
                ScalarOperatorFunctions.secondsAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void millisecondsAdd() {
        assertEquals("2015-03-23T09:23:55.010",
                ScalarOperatorFunctions.millisecondsAdd(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void dateTrunc() {
        String[][] testCases = {
                {"second", "2015-03-23 09:23:55", "2015-03-23T09:23:55"},
                {"minute", "2015-03-23 09:23:55", "2015-03-23T09:23"},
                {"hour", "2015-03-23 09:23:55", "2015-03-23T09:00"},
                {"day", "2015-03-23 09:23:55", "2015-03-23T00:00"},
                {"month", "2015-03-23 09:23:55", "2015-03-01T00:00"},
                {"year", "2015-03-23 09:23:55", "2015-01-01T00:00"},
                {"week", "2015-01-01 09:23:55", "2014-12-29T00:00"},
                {"week", "2015-03-22 09:23:55", "2015-03-16T00:00"},
                {"week", "2015-03-23 09:23:55", "2015-03-23T00:00"},
                {"week", "2015-03-24 09:23:55", "2015-03-23T00:00"},
                {"week", "2020-02-29 09:23:55", "2020-02-24T00:00"},
                {"quarter", "2015-01-01 09:23:55", "2015-01-01T00:00"},
                {"quarter", "2015-03-23 09:23:55", "2015-01-01T00:00"},
                {"quarter", "2015-04-01 09:23:55", "2015-04-01T00:00"},
                {"quarter", "2015-05-23 09:23:55", "2015-04-01T00:00"},
                {"quarter", "2015-07-01 09:23:55", "2015-07-01T00:00"},
                {"quarter", "2015-07-23 09:23:55", "2015-07-01T00:00"},
                {"quarter", "2015-10-01 09:23:55", "2015-10-01T00:00"},
                {"quarter", "2015-11-23 09:23:55", "2015-10-01T00:00"},

                // The following cases are migrated from BE UT.
                {"day", "2020-01-01 09:23:55", "2020-01-01T00:00"},
                {"day", "2020-02-02 09:23:55", "2020-02-02T00:00"},
                {"day", "2020-03-06 09:23:55", "2020-03-06T00:00"},
                {"day", "2020-04-08 09:23:55", "2020-04-08T00:00"},
                {"day", "2020-05-09 09:23:55", "2020-05-09T00:00"},
                {"day", "2020-11-03 09:23:55", "2020-11-03T00:00"},

                {"month", "2020-01-01 09:23:55", "2020-01-01T00:00"},
                {"month", "2020-02-02 09:23:55", "2020-02-01T00:00"},
                {"month", "2020-03-06 09:23:55", "2020-03-01T00:00"},
                {"month", "2020-04-08 09:23:55", "2020-04-01T00:00"},
                {"month", "2020-05-09 09:23:55", "2020-05-01T00:00"},
                {"month", "2020-11-03 09:23:55", "2020-11-01T00:00"},

                {"year", "2020-01-01 09:23:55", "2020-01-01T00:00"},
                {"year", "2020-02-02 09:23:55", "2020-01-01T00:00"},
                {"year", "2020-03-06 09:23:55", "2020-01-01T00:00"},
                {"year", "2020-04-08 09:23:55", "2020-01-01T00:00"},
                {"year", "2020-05-09 09:23:55", "2020-01-01T00:00"},
                {"year", "2020-11-03 09:23:55", "2020-01-01T00:00"},

                {"week", "2020-01-01 09:23:55", "2019-12-30T00:00"},
                {"week", "2020-02-02 09:23:55", "2020-01-27T00:00"},
                {"week", "2020-03-06 09:23:55", "2020-03-02T00:00"},
                {"week", "2020-04-08 09:23:55", "2020-04-06T00:00"},
                {"week", "2020-05-09 09:23:55", "2020-05-04T00:00"},
                {"week", "2020-11-03 09:23:55", "2020-11-02T00:00"},

                {"quarter", "2020-01-01 09:23:55", "2020-01-01T00:00"},
                {"quarter", "2020-02-02 09:23:55", "2020-01-01T00:00"},
                {"quarter", "2020-03-06 09:23:55", "2020-01-01T00:00"},
                {"quarter", "2020-04-08 09:23:55", "2020-04-01T00:00"},
                {"quarter", "2020-05-09 09:23:55", "2020-04-01T00:00"},
                {"quarter", "2020-11-03 09:23:55", "2020-10-01T00:00"},
        };

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (String[] tc : testCases) {
            ConstantOperator fmt = ConstantOperator.createVarchar(tc[0]);
            ConstantOperator date = ConstantOperator.createDatetime(LocalDateTime.parse(tc[1], formatter));
            assertEquals(tc[2],
                    ScalarOperatorFunctions.dateTrunc(fmt, date).getDatetime().toString());
        }

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScalarOperatorFunctions.dateTrunc(ConstantOperator.createVarchar("<ERROR>"), O_DT_20150323_092355)
                        .getVarchar(),
                "<ERROR> not supported in date_trunc format string");

    }

    @Test
    public void dateFormat() {
        Locale.setDefault(Locale.ENGLISH);
        ConstantOperator testDate = ConstantOperator.createDatetime(LocalDateTime.of(2001, 1, 9, 13, 4, 5));
        assertEquals("1",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%c")).getVarchar());
        assertEquals("09",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%d")).getVarchar());
        assertEquals("9",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%e")).getVarchar());
        assertEquals("13",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%H")).getVarchar());
        assertEquals("01",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%h")).getVarchar());
        assertEquals("01",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%I")).getVarchar());
        assertEquals("04",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%i")).getVarchar());
        assertEquals("009",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%j")).getVarchar());
        assertEquals("13",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%k")).getVarchar());
        assertEquals("1",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%l")).getVarchar());
        assertEquals("01",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%m")).getVarchar());
        assertEquals("05",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%S")).getVarchar());
        assertEquals("05",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%s")).getVarchar());
        assertEquals("13:04:05",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%T")).getVarchar());
        assertEquals("02",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%v")).getVarchar());
        assertEquals("2001",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%Y")).getVarchar());
        assertEquals("01",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%y")).getVarchar());
        assertEquals("%",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%%")).getVarchar());
        assertEquals("foo",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("foo")).getVarchar());
        assertEquals("g",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%g")).getVarchar());
        assertEquals("4",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%4")).getVarchar());
        assertEquals("02",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%v")).getVarchar());
        assertEquals("yyyy",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("yyyy")).getVarchar());
        assertEquals("20010109",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("yyyyMMdd")).getVarchar());
        assertEquals("yyyyMMdd HH:mm:ss",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("yyyyMMdd HH:mm:ss"))
                        .getVarchar());
        assertEquals("HH:mm:ss",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("HH:mm:ss")).getVarchar());
        assertEquals("2001-01-09",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("yyyy-MM-dd"))
                        .getVarchar());
        assertEquals("2001-01-09 13:04:05",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("yyyy-MM-dd HH:mm:ss"))
                        .getVarchar());

        assertEquals("2001-01-09",
                ScalarOperatorFunctions.dateFormat(ConstantOperator.createDate(LocalDateTime.of(2001, 1, 9, 13, 4, 5)),
                                ConstantOperator.createVarchar("%Y-%m-%d"))
                        .getVarchar());
        assertEquals("123000", ScalarOperatorFunctions
                .dateFormat(ConstantOperator.createDate(LocalDateTime.of(2022, 3, 13, 0, 0, 0, 123000000)),
                        ConstantOperator.createVarchar("%f")).getVarchar());

        assertEquals("asdfafdfsçv",
                ScalarOperatorFunctions.dateFormat(ConstantOperator.createDate(LocalDateTime.of(2020, 2, 21, 13, 4, 5)),
                        ConstantOperator.createVarchar("asdfafdfsçv")).getVarchar());

        Assertions.assertNotEquals("53",
                ScalarOperatorFunctions.dateFormat(ConstantOperator.createDatetime(LocalDateTime.of(2024, 12, 31, 22, 0, 0)),
                        ConstantOperator.createVarchar("%v")).getVarchar());

        assertEquals("01",
                ScalarOperatorFunctions.dateFormat(ConstantOperator.createDatetime(LocalDateTime.of(2024, 12, 31, 22, 0, 0)),
                        ConstantOperator.createVarchar("%v")).getVarchar());

        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%a")).getVarchar(),
                "%a not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%b")).getVarchar(),
                "%b not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%M")).getVarchar(),
                "%M not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%W")).getVarchar(),
                "%W not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%x")).getVarchar(),
                "%x not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%w")).getVarchar(),
                "%w not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%p")).getVarchar(),
                "%p not supported in date format string");
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                        ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("%r")).getVarchar(),
                "%r not supported in date format string");

        Assertions.assertThrows(IllegalArgumentException.class, () -> ScalarOperatorFunctions
                .dateFormat(ConstantOperator.createDate(LocalDateTime.of(2020, 2, 21, 13, 4, 5)),
                        ConstantOperator.createVarchar("%U")).getVarchar());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ScalarOperatorFunctions
                .dateFormat(ConstantOperator.createDate(LocalDateTime.of(2020, 2, 21, 13, 4, 5)),
                        ConstantOperator.createVarchar("%X")).getVarchar());
        assertTrue(ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar(""))
                .isNull());
        assertEquals("  ",
                ScalarOperatorFunctions.dateFormat(testDate, ConstantOperator.createVarchar("  "))
                        .getVarchar());
    }

    @Test
    public void dateParse() {
        assertEquals("2013-05-10T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013,05,10"), ConstantOperator.createVarchar("%Y,%m,%d"))
                .getDatetime().toString());
        assertEquals("2013-05-10T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("   2013,05,10   "),
                        ConstantOperator.createVarchar("%Y,%m,%d"))
                .getDatetime().toString());
        assertEquals("2013-05-17T12:35:10", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-05-17 12:35:10"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s")).getDatetime().toString());

        assertEquals("2013-01-17T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-1-17"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getDatetime().toString());

        assertEquals("2013-12-01T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013121"),
                        ConstantOperator.createVarchar("%Y%m%d")).getDatetime().toString());

        assertEquals("2013-05-17T12:35:10.000123", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-05-17 12:35:10.000123"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s.%f")).getDatetime().toString());

        assertEquals("2013-05-17T12:35:10.000001", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-05-17 12:35:10.000001"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s.%f")).getDatetime().toString());

        assertEquals("2013-05-17T12:35:10", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-05-17 12:35:10.00000"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s.%f")).getDatetime().toString());

        assertEquals("2013-05-17T00:35:10", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-05-17 00:35:10"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s")).getDatetime().toString());
        assertEquals("2013-05-17T23:35:10", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("abc 2013-05-17 fff 23:35:10 xyz"),
                        ConstantOperator.createVarchar("abc %Y-%m-%d fff %H:%i:%s xyz")).getDatetime().toString());
        assertEquals("2019-05-09T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2019,129"), ConstantOperator.createVarchar("%Y,%j"))
                .getDatetime().toString());
        assertEquals("2019-05-09T12:10:45", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("12:10:45-20190509"),
                        ConstantOperator.createVarchar("%T-%Y%m%d")).getDatetime().toString());
        assertEquals("2019-05-09T09:10:45", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("20190509-9:10:45"),
                        ConstantOperator.createVarchar("%Y%m%d-%k:%i:%S")).getDatetime().toString());
        assertEquals("2020-02-21 00:00:00",
                ScalarOperatorFunctions.dateParse(ConstantOperator.createVarchar("2020-02-21"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).toString());
        assertEquals("2020-02-21 00:00:00",
                ScalarOperatorFunctions.dateParse(ConstantOperator.createVarchar("20-02-21"),
                        ConstantOperator.createVarchar("%y-%m-%d")).toString());
        assertEquals("1998-02-21 00:00:00",
                ScalarOperatorFunctions.dateParse(ConstantOperator.createVarchar("98-02-21"),
                        ConstantOperator.createVarchar("%y-%m-%d")).toString());

        Assertions.assertThrows(DateTimeException.class, () -> ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("201905"),
                        ConstantOperator.createVarchar("%Y%m")).getDatetime());

        Assertions.assertThrows(DateTimeException.class, () -> ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("20190507"),
                        ConstantOperator.createVarchar("%Y%m")).getDatetime());

        Assertions.assertThrows(DateTimeParseException.class, () -> ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2019-02-29"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getDatetime());

        Assertions.assertThrows(DateTimeParseException.class, () -> ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2019-02-29 11:12:13"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s")).getDatetime());

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScalarOperatorFunctions.dateParse(ConstantOperator.createVarchar("2020-2-21"),
                        ConstantOperator.createVarchar("%w")).getVarchar());

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScalarOperatorFunctions.dateParse(ConstantOperator.createVarchar("2020-02-21"),
                        ConstantOperator.createVarchar("%w")).getVarchar());
        assertEquals("2013-01-17T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("\t 2013-1-17"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getDate().toString());
        assertEquals("2013-01-17T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("\n  2013-1-17"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getDate().toString());
        assertEquals("2013-01-17T00:00", ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("\r  2013-1-17"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getDate().toString());

        Assertions.assertThrows(DateTimeParseException.class,
                () -> ScalarOperatorFunctions.dateParse(ConstantOperator.createVarchar("\f 2020-02-21"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getVarchar());

        Assertions.assertThrows(DateTimeException.class, () -> ScalarOperatorFunctions
                .dateParse(ConstantOperator.createVarchar("2013-05-17 12:35:10"),
                        ConstantOperator.createVarchar("%Y-%m-%d %h:%i:%s")).getDatetime(), "Unable to obtain LocalDateTime");

        assertEquals("2022-10-18T01:02:03", ScalarOperatorFunctions.dateParse(
                        ConstantOperator.createVarchar("2022-10-18 01:02:03"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s")).
                getDatetime().toString());

        assertEquals("2022-10-18T01:02", ScalarOperatorFunctions.dateParse(
                        ConstantOperator.createVarchar("2022-10-18 01:02:03"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i")).
                getDatetime().toString());

        Assertions.assertThrows(DateTimeException.class,
                () -> ScalarOperatorFunctions.dateParse(
                        ConstantOperator.createVarchar("2022-10-18 01:02:03"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%s")).getDatetime(),
                "Unable to obtain LocalDateTime");
    }

    @Test
    public void str2Date() {
        assertEquals("2013-05-10T00:00", ScalarOperatorFunctions
                .str2Date(ConstantOperator.createVarchar("2013,05,10"), ConstantOperator.createVarchar("%Y,%m,%d"))
                .getDate().toString());
        assertEquals("2013-05-10T00:00", ScalarOperatorFunctions
                .str2Date(ConstantOperator.createVarchar("   2013,05,10  "), ConstantOperator.createVarchar("%Y,%m,%d"))
                .getDate().toString());
        assertEquals("2013-05-17T00:00", ScalarOperatorFunctions
                .str2Date(ConstantOperator.createVarchar("2013-05-17 12:35:10"),
                        ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s")).getDate().toString());
        assertEquals("2013-05-17T00:00", ScalarOperatorFunctions
                .str2Date(ConstantOperator.createVarchar("13-05-17 12:35:10"),
                        ConstantOperator.createVarchar("%y-%m-%d %H:%i:%s")).getDate().toString());
        assertEquals("1998-05-17T00:00", ScalarOperatorFunctions
                .str2Date(ConstantOperator.createVarchar("98-05-17 12:35:10"),
                        ConstantOperator.createVarchar("%y-%m-%d %H:%i:%s")).getDate().toString());

        Assertions.assertThrows(DateTimeParseException.class, () -> ScalarOperatorFunctions
                .str2Date(ConstantOperator.createVarchar("2019-02-29"),
                        ConstantOperator.createVarchar("%Y-%m-%d")).getDatetime());
    }

    @Test
    public void toDate() {
        ConstantOperator result1 = ScalarOperatorFunctions
                .toDate(ConstantOperator.createDatetime(LocalDateTime.of(2001, 1, 9, 13, 4, 5)));
        assertTrue(result1.getType().isDate());
        // when transfer constantOpeartor to DateLiteral, only y/m/d will keep
        assertEquals("2001-01-09T00:00", result1.getDate().toString());

        ConstantOperator result2 = ScalarOperatorFunctions
                .toDate(ConstantOperator.createDate(LocalDateTime.of(2001, 1, 9, 14, 5, 6)));
        assertTrue(result1.compareTo(result2) == 0);
    }

    @Test
    public void yearsSub() {
        assertEquals("2005-03-23T09:23:55",
                ScalarOperatorFunctions.yearsSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void quartersSub() {
        assertEquals("2014-12-23T09:23:55",
                ScalarOperatorFunctions.quartersSub(O_DT_20150323_092355, O_INT_1).getDatetime().toString());
    }

    @Test
    public void monthsSub() {
        assertEquals("2014-05-23T09:23:55",
                ScalarOperatorFunctions.monthsSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void weeksSub() {
        assertEquals("2015-01-12T09:23:55",
                ScalarOperatorFunctions.weeksSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void daysSub() {
        assertEquals("2015-03-13T09:23:55",
                ScalarOperatorFunctions.daysSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void hoursSub() {
        assertEquals("2015-03-22T23:23:55",
                ScalarOperatorFunctions.hoursSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void minutesSub() {
        assertEquals("2015-03-23T09:13:55",
                ScalarOperatorFunctions.minutesSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void secondsSub() {
        assertEquals("2015-03-23T09:23:45",
                ScalarOperatorFunctions.secondsSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void millisecondsSub() {
        assertEquals("2015-03-23T09:23:54.990",
                ScalarOperatorFunctions.millisecondsSub(O_DT_20150323_092355, O_INT_10).getDatetime().toString());
    }

    @Test
    public void year() {
        ConstantOperator date = ConstantOperator.createDatetime(LocalDateTime.of(2000, 10, 21, 12, 0));
        ConstantOperator result = ScalarOperatorFunctions.year(date);

        assertEquals(Type.SMALLINT, result.getType());
        assertEquals(2000, result.getSmallint());
    }

    @Test
    public void month() {
        assertEquals(3, ScalarOperatorFunctions.month(O_DT_20150323_092355).getTinyInt());
    }

    @Test
    public void day() {
        assertEquals(23, ScalarOperatorFunctions.day(O_DT_20150323_092355).getTinyInt());
    }

    @Test
    public void unixTimestamp() {
        ConstantOperator codt = ConstantOperator.createDatetime(LocalDateTime.of(2050, 3, 23, 9, 23, 55));

        assertEquals(2531611435L,
                ScalarOperatorFunctions.unixTimestamp(codt).getBigint());
        assertEquals(1427073835L,
                ScalarOperatorFunctions.unixTimestamp(O_DT_20150323_092355).getBigint());
    }

    @Test
    public void convert_tz() {
        ConstantOperator olddt = ConstantOperator.createDatetime(LocalDateTime.of(2019, 8, 1, 13, 21, 3));
        assertEquals("2019-07-31T22:21:03",
                ScalarOperatorFunctions.convert_tz(olddt,
                        ConstantOperator.createVarchar("Asia/Shanghai"),
                        ConstantOperator.createVarchar("America/Los_Angeles")).getDatetime().toString());

        ConstantOperator oldd = ConstantOperator.createDate(LocalDateTime.of(2019, 8, 1, 0, 0, 0));
        assertEquals("2019-07-31T09:00",
                ScalarOperatorFunctions.convert_tz(oldd,
                        ConstantOperator.createVarchar("Asia/Shanghai"),
                        ConstantOperator.createVarchar("America/Los_Angeles")).getDatetime().toString());
    }

    @Test
    public void fromUnixTime() throws AnalysisException {
        assertEquals("1970-01-01 08:00:10",
                ScalarOperatorFunctions.fromUnixTime(O_BI_10).getVarchar());
    }

    @Test
    public void curDate() {
        ConnectContext ctx = new ConnectContext(null);
        ctx.setThreadLocalInfo();
        ctx.setStartTime();
        LocalDateTime now = LocalDateTime.of(LocalDate.now(), LocalTime.of(0, 0, 0));
        assertEquals(now, ScalarOperatorFunctions.curDate().getDate());
    }

    @Test
    public void nextDay() {
        assertEquals("2015-03-29T09:23:55", ScalarOperatorFunctions.nextDay(O_DT_20150323_092355,
                ConstantOperator.createVarchar("Sunday")).getDate().toString());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScalarOperatorFunctions.nextDay(O_DT_20150323_092355, ConstantOperator.createVarchar("undefine_dow"))
                        .getVarchar(),
                "undefine_dow not supported in next_day dow_string");
    }

    @Test
    public void previousDay() {
        assertEquals("2015-03-22T09:23:55", ScalarOperatorFunctions.previousDay(O_DT_20150323_092355,
                ConstantOperator.createVarchar("Sunday")).getDate().toString());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScalarOperatorFunctions.previousDay(O_DT_20150323_092355, ConstantOperator.createVarchar("undefine_dow"))
                        .getVarchar(),
                "undefine_dow not supported in previous_day dow_string");
    }

    @Test
    public void makeDate() {
        ConnectContext ctx = new ConnectContext(null);
        ctx.setThreadLocalInfo();
        ctx.setStartTime();

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createNull(Type.INT),
                        ConstantOperator.createNull(Type.INT)));

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createNull(Type.INT),
                        ConstantOperator.createInt(1)));

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(1),
                        ConstantOperator.createNull(Type.INT)));

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(2000), ConstantOperator.createInt(0)));

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(2000), ConstantOperator.createInt(367)));

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(-1), ConstantOperator.createInt(1)));

        assertEquals(ConstantOperator.createNull(Type.DATE),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(10000), ConstantOperator.createInt(1)));

        assertEquals(ConstantOperator.createDate(LocalDateTime.of(2000, 1, 1, 0, 0, 0)),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(2000), ConstantOperator.createInt(1)));

        assertEquals(ConstantOperator.createDate(LocalDateTime.of(2000, 12, 31, 0, 0, 0)),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(2000), ConstantOperator.createInt(366)));

        assertEquals(ConstantOperator.createDate(LocalDateTime.of(0, 1, 1, 0, 0, 0)),
                ScalarOperatorFunctions.makeDate(ConstantOperator.createInt(0), ConstantOperator.createInt(1)));
    }

    @Test
    public void timeSlice() throws AnalysisException {
        class Param {
            final LocalDateTime dateTime;
            final int interval;
            final String unit;
            final String boundary;

            LocalDateTime expect;
            String e;

            public Param(LocalDateTime dateTime, int interval, String unit, LocalDateTime expect) {
                this(dateTime, interval, unit, "floor", expect);
            }

            private Param(LocalDateTime dateTime, int interval, String unit, String boundary, LocalDateTime expect) {
                this.dateTime = dateTime;
                this.interval = interval;
                this.unit = unit;
                this.boundary = boundary;
                this.expect = expect;
            }

            private Param(LocalDateTime dateTime, int interval, String unit, String boundary, String e) {
                this.dateTime = dateTime;
                this.interval = interval;
                this.unit = unit;
                this.boundary = boundary;
                this.e = e;
            }
        }

        // test case from be TimeFunctionsTest
        List<Param> cases = Arrays.asList(
                // second
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "second", LocalDateTime.of(0001, 1, 1, 21, 22, 50)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "second", LocalDateTime.of(0001, 3, 2, 14, 17, 25)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "second", LocalDateTime.of(0001, 5, 6, 11, 54, 20)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "second", LocalDateTime.of(2022, 7, 8, 9, 13, 15)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "second", LocalDateTime.of(2022, 9, 9, 8, 8, 15)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "second", LocalDateTime.of(2022, 11, 3, 23, 41, 35)),

                // minute
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "minute", LocalDateTime.of(0001, 1, 1, 21, 20, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "minute", LocalDateTime.of(0001, 3, 2, 14, 15, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "minute", LocalDateTime.of(0001, 5, 6, 11, 50, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "minute", LocalDateTime.of(2022, 7, 8, 9, 10, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "minute", LocalDateTime.of(2022, 9, 9, 8, 5, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "minute", LocalDateTime.of(2022, 11, 3, 23, 40, 0)),

                // hour
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "hour", LocalDateTime.of(0001, 1, 1, 20, 0, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "hour", LocalDateTime.of(0001, 3, 2, 10, 0, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "hour", LocalDateTime.of(0001, 5, 6, 10, 0, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "hour", LocalDateTime.of(2022, 7, 8, 8, 0, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "hour", LocalDateTime.of(2022, 9, 9, 6, 0, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "hour", LocalDateTime.of(2022, 11, 3, 21, 0, 0)),

                // day
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "day", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "day", LocalDateTime.of(0001, 3, 2, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "day", LocalDateTime.of(0001, 5, 6, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "day", LocalDateTime.of(2022, 7, 5, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "day", LocalDateTime.of(2022, 9, 8, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "day", LocalDateTime.of(2022, 11, 2, 0, 0, 0)),

                // month
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "month", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "month", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "month", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "month", LocalDateTime.of(2022, 4, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "month", LocalDateTime.of(2022, 9, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "month", LocalDateTime.of(2022, 9, 1, 0, 0, 0)),

                // year
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "year", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "year", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "year", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "year", LocalDateTime.of(2021, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "year", LocalDateTime.of(2021, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "year", LocalDateTime.of(2021, 1, 1, 0, 0, 0)),

                // week
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "week", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "week", LocalDateTime.of(0001, 2, 5, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "week", LocalDateTime.of(0001, 4, 16, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "week", LocalDateTime.of(2022, 6, 20, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "week", LocalDateTime.of(2022, 8, 29, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "week", LocalDateTime.of(2022, 10, 3, 0, 0, 0)),

                // quarter
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "quarter", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "quarter", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "quarter", LocalDateTime.of(0001, 1, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "quarter", LocalDateTime.of(2022, 4, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "quarter", LocalDateTime.of(2022, 4, 1, 0, 0, 0)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "quarter", LocalDateTime.of(2022, 4, 1, 0, 0, 0)),

                // second ceil
                new Param(LocalDateTime.of(0001, 1, 1, 21, 22, 51), 5, "second", "ceil", LocalDateTime.of(0001, 1, 1, 21, 22,
                        55)),
                new Param(LocalDateTime.of(0001, 3, 2, 14, 17, 28), 5, "second", "ceil", LocalDateTime.of(0001, 3, 2, 14, 17,
                        30)),
                new Param(LocalDateTime.of(0001, 5, 6, 11, 54, 23), 5, "second", "ceil", LocalDateTime.of(0001, 5, 6, 11, 54,
                        25)),
                new Param(LocalDateTime.of(2022, 7, 8, 9, 13, 19), 5, "second", "ceil", LocalDateTime.of(2022, 7, 8, 9, 13,
                        20)),
                new Param(LocalDateTime.of(2022, 9, 9, 8, 8, 16), 5, "second", "ceil", LocalDateTime.of(2022, 9, 9, 8, 8,
                        20)),
                new Param(LocalDateTime.of(2022, 11, 3, 23, 41, 37), 5, "second", "ceil", LocalDateTime.of(2022, 11, 3, 23, 41,
                        40)),
                new Param(LocalDateTime.of(0000, 01, 01, 00, 00, 00), 5, "hour", "floor",
                        "time used with time_slice can't before 0001-01-01 00:00:00"),
                new Param(LocalDateTime.of(2023, 12, 31, 03, 12, 00), 2147483647, "minute", "floor",
                        LocalDateTime.of(0001, 01, 01, 00, 00, 00))
        );

        for (Param testCase : cases) {
            try {
                ConstantOperator result = ScalarOperatorFunctions.timeSlice(
                        ConstantOperator.createDatetime(testCase.dateTime),
                        ConstantOperator.createInt(testCase.interval),
                        ConstantOperator.createVarchar(testCase.unit),
                        ConstantOperator.createVarchar(testCase.boundary)
                );
                if (testCase.expect != null) {
                    assertEquals(testCase.expect, result.getDatetime());
                } else {
                    Assertions.fail();
                }
            } catch (AnalysisException e) {
                assertTrue(e.getMessage().contains(testCase.e));
            }
        }
    }

    @Test
    public void floor() {
        assertEquals(100, ScalarOperatorFunctions.floor(O_FLOAT_100).getBigint());
    }

    @Test
    public void addSmallInt() {
        assertEquals(20,
                ScalarOperatorFunctions.addSmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void addInt() {
        assertEquals(20,
                ScalarOperatorFunctions.addInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void addBigInt() {
        assertEquals(200, ScalarOperatorFunctions.addBigInt(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void addLargeInt() {
        assertEquals("200",
                ScalarOperatorFunctions.addLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void addDouble() {
        assertEquals(200.0,
                ScalarOperatorFunctions.addDouble(O_DOUBLE_100, O_DOUBLE_100).getDouble(), 1);
    }

    @Test
    public void addDecimal() {
        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL_100, O_DECIMAL_100).getDecimal().toPlainString());
        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL32P7S2_100, O_DECIMAL32P7S2_100).getDecimal()
                        .toPlainString());
        assertTrue(
                ScalarOperatorFunctions.addDecimal(O_DECIMAL32P7S2_100, O_DECIMAL32P7S2_100).getType().isDecimalV3());

        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL32P9S0_100, O_DECIMAL32P9S0_100).getDecimal()
                        .toPlainString());
        assertTrue(
                ScalarOperatorFunctions.addDecimal(O_DECIMAL32P9S0_100, O_DECIMAL32P9S0_100).getType().isDecimalV3());

        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL64P15S10_100, O_DECIMAL64P15S10_100).getDecimal()
                        .toPlainString());
        assertTrue(ScalarOperatorFunctions.addDecimal(O_DECIMAL64P15S10_100, O_DECIMAL64P15S10_100).getType()
                .isDecimalV3());

        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL64P18S15_100, O_DECIMAL64P18S15_100).getDecimal()
                        .toPlainString());
        assertTrue(ScalarOperatorFunctions.addDecimal(O_DECIMAL64P18S15_100, O_DECIMAL64P18S15_100).getType()
                .isDecimalV3());

        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL128P30S2_100, O_DECIMAL128P30S2_100).getDecimal()
                        .toPlainString());
        assertTrue(ScalarOperatorFunctions.addDecimal(O_DECIMAL128P30S2_100, O_DECIMAL128P30S2_100).getType()
                .isDecimalV3());

        assertEquals("200",
                ScalarOperatorFunctions.addDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getDecimal()
                        .toPlainString());
        assertTrue(ScalarOperatorFunctions.addDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getType()
                .isDecimalV3());
    }

    @Test
    public void subtractSmallInt() {
        assertEquals(0,
                ScalarOperatorFunctions.subtractSmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void subtractInt() {
        assertEquals(0,
                ScalarOperatorFunctions.subtractInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void subtractBigInt() {
        assertEquals(0, ScalarOperatorFunctions.subtractBigInt(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void subtractDouble() {
        assertEquals(0.0,
                ScalarOperatorFunctions.subtractDouble(O_DOUBLE_100, O_DOUBLE_100).getDouble(), 1);
    }

    @Test
    public void subtractDecimal() {
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL_100, O_DECIMAL_100).getDecimal().toString());
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL32P7S2_100, O_DECIMAL32P7S2_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL32P9S0_100, O_DECIMAL32P9S0_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL64P15S10_100, O_DECIMAL64P15S10_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL64P18S15_100, O_DECIMAL64P18S15_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL128P30S2_100, O_DECIMAL128P30S2_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.subtractDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getDecimal()
                        .toString());

        assertTrue(ScalarOperatorFunctions.subtractDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getType()
                .isDecimalV3());
    }

    @Test
    public void subtractLargeInt() {
        assertEquals("0",
                ScalarOperatorFunctions.subtractLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void multiplySmallInt() {
        assertEquals(100,
                ScalarOperatorFunctions.multiplySmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void multiplyInt() {
        assertEquals(100,
                ScalarOperatorFunctions.multiplyInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void multiplyBigInt() {
        assertEquals(10000,
                ScalarOperatorFunctions.multiplyBigInt(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void multiplyDouble() {
        assertEquals(10000.0,
                ScalarOperatorFunctions.multiplyDouble(O_DOUBLE_100, O_DOUBLE_100).getDouble(), 1);
    }

    @Test
    public void multiplyDecimal() {
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL_100, O_DECIMAL_100).getDecimal().toPlainString());
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL32P7S2_100, O_DECIMAL32P7S2_100).getDecimal()
                        .toPlainString());
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL32P9S0_100, O_DECIMAL32P9S0_100).getDecimal()
                        .toPlainString());
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL64P15S10_100, O_DECIMAL64P15S10_100).getDecimal()
                        .toPlainString());
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL64P18S15_100, O_DECIMAL64P18S15_100).getDecimal()
                        .toPlainString());
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL128P30S2_100, O_DECIMAL128P30S2_100).getDecimal()
                        .toPlainString());
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getDecimal()
                        .toPlainString());

        assertTrue(ScalarOperatorFunctions.multiplyDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getType()
                .isDecimalV3());
    }

    @Test
    public void multiplyLargeInt() {
        assertEquals("10000",
                ScalarOperatorFunctions.multiplyLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void divideDouble() {
        assertEquals(1.0,
                ScalarOperatorFunctions.divideDouble(O_DOUBLE_100, O_DOUBLE_100).getDouble(), 1);
    }

    @Test
    public void divideDecimal() {
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL_100, O_DECIMAL_100).getDecimal().toString());
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL32P7S2_100, O_DECIMAL32P7S2_100).getDecimal()
                        .toString());
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL32P9S0_100, O_DECIMAL32P9S0_100).getDecimal()
                        .toString());
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL64P15S10_100, O_DECIMAL64P15S10_100).getDecimal()
                        .toString());
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL64P18S15_100, O_DECIMAL64P18S15_100).getDecimal()
                        .toString());
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL128P30S2_100, O_DECIMAL128P30S2_100).getDecimal()
                        .toString());
        assertEquals("1",
                ScalarOperatorFunctions.divideDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getDecimal()
                        .toString());

        assertTrue(ScalarOperatorFunctions.divideDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getType()
                .isDecimalV3());

    }

    @Test
    public void intDivideTinyInt() {
        assertEquals(1, ScalarOperatorFunctions.intDivideTinyInt(O_TI_10, O_TI_10).getTinyInt());
    }

    @Test
    public void intDivideSmallInt() {
        assertEquals(1, ScalarOperatorFunctions.intDivideSmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void intDivideInt() {
        assertEquals(1, ScalarOperatorFunctions.intDivideInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void intDivide() {
        assertEquals(33, ScalarOperatorFunctions.intDivideBigint(O_BI_100, O_BI_3).getBigint());
    }

    @Test
    public void intDivideLargeInt() {
        assertEquals("1", ScalarOperatorFunctions.intDivideLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void modTinyInt() {
        assertEquals(0, ScalarOperatorFunctions.modTinyInt(O_TI_10, O_TI_10).getTinyInt());
    }

    @Test
    public void modSMALLINT() {
        assertEquals(0, ScalarOperatorFunctions.modSMALLINT(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void modInt() {
        assertEquals(0, ScalarOperatorFunctions.modInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void modBigInt() {
        assertEquals(0, ScalarOperatorFunctions.modBigInt(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void modLargeInt() {
        assertEquals("0", ScalarOperatorFunctions.modLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void modDecimal() {
        assertEquals("0", ScalarOperatorFunctions.modDecimal(O_DECIMAL_100, O_DECIMAL_100).getDecimal().toString());
        assertEquals("0",
                ScalarOperatorFunctions.modDecimal(O_DECIMAL32P7S2_100, O_DECIMAL32P7S2_100).getDecimal().toString());
        assertEquals("0",
                ScalarOperatorFunctions.modDecimal(O_DECIMAL32P9S0_100, O_DECIMAL32P9S0_100).getDecimal().toString());
        assertEquals("0",
                ScalarOperatorFunctions.modDecimal(O_DECIMAL64P15S10_100, O_DECIMAL64P15S10_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.modDecimal(O_DECIMAL64P18S15_100, O_DECIMAL64P18S15_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.modDecimal(O_DECIMAL128P30S2_100, O_DECIMAL128P30S2_100).getDecimal()
                        .toString());
        assertEquals("0",
                ScalarOperatorFunctions.modDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getDecimal()
                        .toString());
        assertTrue(ScalarOperatorFunctions.modDecimal(O_DECIMAL128P38S20_100, O_DECIMAL128P38S20_100).getType()
                .isDecimalV3());
    }

    @Test
    public void bitandTinyInt() {
        assertEquals(10, ScalarOperatorFunctions.bitandTinyInt(O_TI_10, O_TI_10).getTinyInt());
    }

    @Test
    public void bitandSmallInt() {
        assertEquals(10, ScalarOperatorFunctions.bitandSmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void bitandInt() {
        assertEquals(10, ScalarOperatorFunctions.bitandInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void bitandBigint() {
        assertEquals(100, ScalarOperatorFunctions.bitandBigint(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void bitandLargeInt() {
        assertEquals("100", ScalarOperatorFunctions.bitandLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void bitorTinyInt() {
        assertEquals(10, ScalarOperatorFunctions.bitorTinyInt(O_TI_10, O_TI_10).getTinyInt());
    }

    @Test
    public void bitorSmallInt() {
        assertEquals(10, ScalarOperatorFunctions.bitorSmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void bitorInt() {
        assertEquals(10, ScalarOperatorFunctions.bitorInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void bitorBigint() {
        assertEquals(100, ScalarOperatorFunctions.bitorBigint(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void bitorLargeInt() {
        assertEquals("100", ScalarOperatorFunctions.bitorLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void bitxorTinyInt() {
        assertEquals(0, ScalarOperatorFunctions.bitxorTinyInt(O_TI_10, O_TI_10).getTinyInt());
    }

    @Test
    public void bitxorSmallInt() {
        assertEquals(0, ScalarOperatorFunctions.bitxorSmallInt(O_SI_10, O_SI_10).getSmallint());
    }

    @Test
    public void bitxorInt() {
        assertEquals(0, ScalarOperatorFunctions.bitxorInt(O_INT_10, O_INT_10).getInt());
    }

    @Test
    public void bitxorBigint() {
        assertEquals(0, ScalarOperatorFunctions.bitxorBigint(O_BI_100, O_BI_100).getBigint());
    }

    @Test
    public void bitxorLargeInt() {
        assertEquals("0", ScalarOperatorFunctions.bitxorLargeInt(O_LI_100, O_LI_100).getLargeInt().toString());
    }

    @Test
    public void bitShiftLeftTinyInt() {
        assertEquals(80, ScalarOperatorFunctions.bitShiftLeftTinyInt(O_TI_10, O_BI_3).getTinyInt());
    }

    @Test
    public void bitShiftLeftSmallInt() {
        assertEquals(80, ScalarOperatorFunctions.bitShiftLeftSmallInt(O_SI_10, O_BI_3).getSmallint());
    }

    @Test
    public void bitShiftLeftInt() {
        assertEquals(80, ScalarOperatorFunctions.bitShiftLeftInt(O_INT_10, O_BI_3).getInt());
    }

    @Test
    public void bitShiftLeftBigint() {
        assertEquals(800, ScalarOperatorFunctions.bitShiftLeftBigint(O_BI_100, O_BI_3).getBigint());
    }

    @Test
    public void bitShiftLeftLargeInt() {
        assertEquals("800", ScalarOperatorFunctions.bitShiftLeftLargeInt(O_LI_100, O_BI_3).getLargeInt().toString());
    }

    @Test
    public void bitShiftRightTinyInt() {
        assertEquals(1, ScalarOperatorFunctions.bitShiftRightTinyInt(O_TI_10, O_BI_3).getTinyInt());
    }

    @Test
    public void bitShiftRightSmallInt() {
        assertEquals(1, ScalarOperatorFunctions.bitShiftRightSmallInt(O_SI_10, O_BI_3).getSmallint());
    }

    @Test
    public void bitShiftRightInt() {
        assertEquals(1, ScalarOperatorFunctions.bitShiftRightInt(O_INT_10, O_BI_3).getInt());
    }

    @Test
    public void bitShiftRightBigint() {
        assertEquals(12, ScalarOperatorFunctions.bitShiftRightBigint(O_BI_100, O_BI_3).getBigint());
    }

    @Test
    public void bitShiftRightLargeInt() {
        assertEquals("12", ScalarOperatorFunctions.bitShiftRightLargeInt(O_LI_100, O_BI_3).getLargeInt().toString());
    }

    @Test
    public void bitShiftRightLogicalTinyInt() {
        assertEquals(1, ScalarOperatorFunctions.bitShiftRightLogicalTinyInt(O_TI_10, O_BI_3).getTinyInt());
    }

    @Test
    public void bitShiftRightLogicalSmallInt() {
        assertEquals(1, ScalarOperatorFunctions.bitShiftRightLogicalSmallInt(O_SI_10, O_BI_3).getSmallint());
    }

    @Test
    public void bitShiftRightLogicalInt() {
        assertEquals(1, ScalarOperatorFunctions.bitShiftRightLogicalInt(O_INT_10, O_BI_3).getInt());
    }

    @Test
    public void bitShiftRightLogicalBigint() {
        assertEquals(12, ScalarOperatorFunctions.bitShiftRightLogicalBigint(O_BI_100, O_BI_3).getBigint());
    }

    @Test
    public void bitShiftRightLogicalLargeInt() {
        assertEquals("12",
                ScalarOperatorFunctions.bitShiftRightLogicalLargeInt(O_LI_100, O_BI_3).getLargeInt().toString());
        assertEquals("800",
                ScalarOperatorFunctions.bitShiftRightLogicalLargeInt(O_LI_100, O_BI_NEG_3).getLargeInt().toString());
        assertEquals("12",
                ScalarOperatorFunctions.bitShiftRightLogicalLargeInt(O_LI_100, O_BI_131).getLargeInt().toString());
        assertEquals("42535295865117307932921825928971026419",
                ScalarOperatorFunctions.bitShiftRightLogicalLargeInt(O_LI_NEG_100, O_BI_3).getLargeInt().toString());
    }

    @Test
    public void concat() {
        ConstantOperator[] arg = {ConstantOperator.createVarchar("1"),
                ConstantOperator.createVarchar("2"),
                ConstantOperator.createVarchar("3")};
        ConstantOperator result = ScalarOperatorFunctions.concat(arg);

        assertEquals(Type.VARCHAR, result.getType());
        assertEquals("123", result.getVarchar());
    }

    @Test
    public void concat_ws() {
        ConstantOperator[] arg = {ConstantOperator.createVarchar("1"),
                ConstantOperator.createVarchar("2"),
                ConstantOperator.createVarchar("3")};
        ConstantOperator result = ScalarOperatorFunctions.concat_ws(ConstantOperator.createVarchar(","), arg);

        assertEquals(Type.VARCHAR, result.getType());
        assertEquals("1,2,3", result.getVarchar());
    }

    @Test
    public void concat_ws_with_null() {
        {
            ConstantOperator[] argWithNull = {ConstantOperator.createVarchar("star"),
                    ConstantOperator.createNull(Type.VARCHAR),
                    ConstantOperator.createVarchar("cks")};
            ConstantOperator result =
                    ScalarOperatorFunctions.concat_ws(ConstantOperator.createVarchar("ro"), argWithNull);
            assertEquals(Type.VARCHAR, result.getType());
            assertEquals("starrocks", result.getVarchar());
        }
        {
            ConstantOperator[] argWithNull = {ConstantOperator.createVarchar("1"),
                    ConstantOperator.createNull(Type.VARCHAR)};
            ConstantOperator result =
                    ScalarOperatorFunctions.concat_ws(ConstantOperator.createVarchar(","), argWithNull);
            assertEquals(Type.VARCHAR, result.getType());
            assertEquals("1", result.getVarchar());
        }
        {
            ConstantOperator[] argWithNull = {ConstantOperator.createVarchar("1"),
                    ConstantOperator.createNull(Type.VARCHAR),
                    ConstantOperator.createNull(Type.VARCHAR)};
            ConstantOperator result =
                    ScalarOperatorFunctions.concat_ws(ConstantOperator.createVarchar(","), argWithNull);
            assertEquals(Type.VARCHAR, result.getType());
            assertEquals("1", result.getVarchar());
        }
        {
            ConstantOperator result = ScalarOperatorFunctions.concat_ws(ConstantOperator.createVarchar(","),
                    ConstantOperator.createNull(Type.VARCHAR));
            assertEquals("", result.getVarchar());

            ConstantOperator[] argWithoutNull = {ConstantOperator.createVarchar("star"),
                    ConstantOperator.createVarchar("cks")};
            result = ScalarOperatorFunctions.concat_ws(ConstantOperator.createNull(Type.VARCHAR), argWithoutNull);
            assertTrue(result.isNull());
        }
    }

    @Test
    public void fromUnixTime2() throws AnalysisException {
        ConstantOperator date =
                ScalarOperatorFunctions.fromUnixTime(O_BI_10, ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s"));
        assertTrue(date.toString().matches("1970-01-01 0.*:00:10"));
    }

    @Test
    public void fromUnixTime3() throws AnalysisException {
        ConstantOperator date =
                ScalarOperatorFunctions.fromUnixTime(O_BI_10, ConstantOperator.createVarchar("%Y-%m-%d %H:%i:%s"),
                        ConstantOperator.createVarchar("UTC"));
        assertTrue(date.toString().matches("1970-01-01 0.*:00:10"));
    }

    @Test
    public void testNonDeterministicFuncComp() {
        // In logical phash, the new operator cloned from the original one should equal with the original one.
        CallOperator random = new CallOperator(FunctionSet.RANDOM, Type.DOUBLE, Lists.newArrayList());
        CallOperator randomCopy = (CallOperator) random.clone();
        assertEquals(random, randomCopy);
    }

    @Test
    public void testUTCTimestamp() {
        ConnectContext ctx = new ConnectContext(null);
        ctx.setThreadLocalInfo();
        ctx.setStartTime();
        LocalDateTime expected = Instant.ofEpochMilli(ctx.getStartTime() / 1000 * 1000)
                .atZone(ZoneOffset.UTC).toLocalDateTime();
        assertEquals(expected, ScalarOperatorFunctions.utcTimestamp().getDatetime());
    }

    @Test
    public void testNow() {
        ConnectContext ctx = new ConnectContext(null);
        ctx.setThreadLocalInfo();
        ctx.setStartTime();
        LocalDateTime expected = Instant.ofEpochMilli(ctx.getStartTime() / 1000 * 1000)
                .atZone(TimeUtils.getTimeZone().toZoneId()).toLocalDateTime();
        assertEquals(expected, ScalarOperatorFunctions.now().getDatetime());
        double expectedTime = expected.getHour() * 3600D + expected.getMinute() * 60D + expected.getSecond();
        assertEquals(expectedTime, ScalarOperatorFunctions.curTime().getTime(), 0.1);
    }

    @Test
    public void testNowWithParameter() throws AnalysisException {
        ConnectContext ctx = new ConnectContext(null);
        ctx.setThreadLocalInfo();
        ctx.setStartTime();
        Instant instant = ctx.getStartTimeInstant();
        LocalDateTime expected = Instant.ofEpochSecond(instant.getEpochSecond(), instant.getNano() / 1000 * 1000)
                .atZone(TimeUtils.getTimeZone().toZoneId()).toLocalDateTime();
        assertEquals(expected, ScalarOperatorFunctions.now(new ConstantOperator(6, Type.INT)).getDatetime());
    }

    @Test
    public void testSubString() {
        assertEquals("ab", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(1), ConstantOperator.createInt(2)).getVarchar());
        assertEquals("abcd", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(1)).getVarchar());
        assertEquals("cd", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(-2)).getVarchar());
        assertEquals("c", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(-2), ConstantOperator.createInt(1)).getVarchar());
        assertEquals("abcd", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(1), ConstantOperator.createInt(4)).getVarchar());
        assertEquals("abcd", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(1), ConstantOperator.createInt(10)).getVarchar());
        assertEquals("cd", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(3), ConstantOperator.createInt(4)).getVarchar());
        assertEquals("", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(0), ConstantOperator.createInt(2)).getVarchar());
        assertEquals("", ScalarOperatorFunctions.substring(ConstantOperator.createVarchar("abcd"),
                ConstantOperator.createInt(5), ConstantOperator.createInt(2)).getVarchar());

        assertEquals("starrocks", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrockscluster", Type.VARCHAR),
                new ConstantOperator(1, Type.INT),
                new ConstantOperator(9, Type.INT)).getVarchar());

        assertEquals("rocks", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(-5, Type.INT),
                new ConstantOperator(5, Type.INT)).getVarchar());

        assertEquals("s", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(-1, Type.INT),
                new ConstantOperator(8, Type.INT)).getVarchar());

        assertEquals("", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(-100, Type.INT),
                new ConstantOperator(5, Type.INT)).getVarchar());

        assertEquals("", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(0, Type.INT),
                new ConstantOperator(5, Type.INT)).getVarchar());

        assertEquals("", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(-1, Type.INT),
                new ConstantOperator(0, Type.INT)).getVarchar());

        assertEquals("apple", ScalarOperatorFunctions.substring(
                new ConstantOperator("apple", Type.VARCHAR),
                new ConstantOperator(-5, Type.INT),
                new ConstantOperator(5, Type.INT)).getVarchar());

        assertEquals("", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(0, Type.INT)).getVarchar());

        assertEquals("starrocks", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(1, Type.INT)).getVarchar());

        assertEquals("s", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(9, Type.INT)).getVarchar());

        assertEquals("", ScalarOperatorFunctions.substring(
                new ConstantOperator("starrocks", Type.VARCHAR),
                new ConstantOperator(10, Type.INT)).getVarchar());
    }

    @Test
    public void testUrlExtractParameter() {
        assertEquals("100", ScalarOperatorFunctions.urlExtractParameter(
                new ConstantOperator("https://starrocks.com/doc?k1=100&k2=3", Type.VARCHAR),
                new ConstantOperator("k1", Type.VARCHAR)
        ).getVarchar());
        assertEquals(ScalarOperatorFunctions.urlExtractParameter(
                        new ConstantOperator("1234i5", Type.VARCHAR),
                        new ConstantOperator("k1", Type.VARCHAR)),
                ConstantOperator.createNull(Type.VARCHAR));
        assertEquals(ScalarOperatorFunctions.urlExtractParameter(
                        new ConstantOperator("https://starrocks.com/doc?k1=100&k2=3", Type.VARCHAR),
                        new ConstantOperator("k3", Type.VARCHAR)),
                ConstantOperator.createNull(Type.VARCHAR));
    }

    @Test
    public void testReplace() {
        // arg0, arg1, arg2, expected_result
        String[][] testCases = {
                {"2024-08-06", "-", "", "20240806"},
                {"abc def ghi", "", "1234", "abc def ghi"},
                {"abc def ghi abc", "abc", "1234", "1234 def ghi 1234"},
                {"", "abc", "1234", ""}
        };

        for (String[] tc : testCases) {
            assertEquals(tc[3], ScalarOperatorFunctions.replace(
                    new ConstantOperator(tc[0], Type.VARCHAR),
                    new ConstantOperator(tc[1], Type.VARCHAR),
                    new ConstantOperator(tc[2], Type.VARCHAR)
            ).getVarchar(), "Test case: " + Arrays.toString(tc));
        }
    }

    @Test
    public void testLowerUpper() {
        assertEquals("aaa", ScalarOperatorFunctions.lower(
                new ConstantOperator("AAA", Type.VARCHAR)
        ).getVarchar());
        assertEquals("AAA", ScalarOperatorFunctions.upper(
                new ConstantOperator("aaa", Type.VARCHAR)
        ).getVarchar());
    }

    @Test
    public void testJodatimeFormat() {
        assertEquals("", ScalarOperatorFunctions.jodatimeFormat(
                new ConstantOperator("2024-08-06", Type.DATE),
                new ConstantOperator("", Type.VARCHAR)).getVarchar());

        assertEquals("20241109", ScalarOperatorFunctions.jodatimeFormat(
                new ConstantOperator(LocalDateTime.of(2024, 11, 9, 15, 30, 45),
                        Type.DATE),
                new ConstantOperator("yyyyMMdd", Type.VARCHAR)).getVarchar());
    }

    /*
    test cases are generated by the following SQL by capturing:
    1. leap year
    2. begin days and end days of a year.
    3. different modes.

    with T as (
  select
   days_add(years_add('1900-01-01', Y.delta), D.day) as start_day,
   days_sub(years_add('1900-12-31', Y.delta), D.day) as end_day,
   days_add(years_add('2000-01-01', Y.delta), D.day) as start_day2,
   days_sub(years_add('2000-12-31', Y.delta), D.day) as end_day2,
   M.mode
  from
  table(generate_series(0, 14, 1)) as D(day) join
  table(generate_series(0, 40, 1)) as Y(delta) join
  table(generate_series(0, 7, 1)) AS M(mode)
 )
 select mode, start_day, week(start_day, mode), end_day, week(end_day, mode) ,
   start_day2, week(start_day2, mode), end_day2, week(end_day2, mode) from T;
     */

    static class WeekFunctionTestCase {
        int mode;
        LocalDateTime dt;
        int value;

        @Override
        public String toString() {
            return String.format("mode = %d, input = %s, value = %d", mode, dt, value);
        }

        public static List<WeekFunctionTestCase> readTestCases(String filePath) {
            List<WeekFunctionTestCase> testCaseList = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] columns = line.split("\t");
                    int mode = Integer.parseInt(columns[0]);
                    for (int i = 1; i < columns.length; i += 2) {
                        WeekFunctionTestCase tc = new WeekFunctionTestCase();
                        tc.mode = mode;
                        tc.dt = LocalDateTime.parse(columns[i], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        tc.value = Integer.parseInt(columns[i + 1]);
                        testCaseList.add(tc);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return testCaseList;
        }
    }

    @Test
    public void testWeekFunction() {
        String testPath = Objects.requireNonNull(
                ClassLoader.getSystemClassLoader().getResource("sql/optimizer/rewrite/week-function-test.dat")).getPath();
        List<WeekFunctionTestCase> testCaseList = WeekFunctionTestCase.readTestCases(testPath);
        for (WeekFunctionTestCase tc : testCaseList) {
            LocalDateTime dt = tc.dt;
            long result = ScalarOperatorFunctions.TimeFunctions.computeWeek(dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                    tc.mode);
            assertEquals(tc.value, result, String.format("test case failed: %s, result = %d", tc, result));
        }
    }

    @Test
    public void testYearWeekFunction() {
        String testPath = Objects.requireNonNull(
                ClassLoader.getSystemClassLoader().getResource("sql/optimizer/rewrite/year-week-function-test.dat")).getPath();
        List<WeekFunctionTestCase> testCaseList = WeekFunctionTestCase.readTestCases(testPath);
        for (WeekFunctionTestCase tc : testCaseList) {
            LocalDateTime dt = tc.dt;
            long result =
                    ScalarOperatorFunctions.TimeFunctions.computeYearWeek(dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                            tc.mode);
            assertEquals(tc.value, result, String.format("test case failed: %s, result = %d", tc, result));
        }
    }

    @Test
    public void testLastDayDefaultMonth() {
        Object[][] testCases = {
                // date, expected last day of month
                {"2023-05-10T10:00:00", "2023-05-31"},
                {"2024-02-01T00:00:00", "2024-02-29"}, // Leap year
                {"2021-02-01T00:00:00", "2021-02-28"},
        };

        for (Object[] tc : testCases) {
            ConstantOperator input = ConstantOperator.createDatetime(LocalDateTime.parse((String) tc[0]));
            ConstantOperator result = ScalarOperatorFunctions.lastDay(input);
            assertEquals(tc[1], result.getDate().toLocalDate().toString(), "Failed case: " + Arrays.toString(tc));
        }
    }

    @Test
    public void testLastDayWithUnit() {
        Object[][] testCases = {
                {"2023-03-15T00:00:00", "month", "2023-03-31"},
                {"2023-03-15T00:00:00", "quarter", "2023-03-31"},
                {"2023-05-01T00:00:00", "quarter", "2023-06-30"},
                {"2023-05-01T00:00:00", "year", "2023-12-31"},
        };

        for (Object[] tc : testCases) {
            ConstantOperator input = ConstantOperator.createDatetime(LocalDateTime.parse((String) tc[0]));
            ConstantOperator unit = ConstantOperator.createVarchar((String) tc[1]);
            ConstantOperator result = ScalarOperatorFunctions.lastDay(input, unit);
            assertEquals(tc[2], result.getDate().toLocalDate().toString(), "Failed case: " + Arrays.toString(tc));
        }
    }

    @Test
    public void testLastDayWithInvalidUnit() {
        assertThrows(IllegalArgumentException.class, () -> {
            ConstantOperator input = ConstantOperator.createDatetime(LocalDateTime.parse("2023-05-10T00:00:00"));
            ConstantOperator unit = ConstantOperator.createVarchar("invalid");
            ScalarOperatorFunctions.lastDay(input, unit);
        });
    }

    @Test
    public void testLastDayWithNull() {
        ConstantOperator input = ConstantOperator.createNull(Type.DATETIME);
        ConstantOperator unit = ConstantOperator.createVarchar("month");
        ConstantOperator result = ScalarOperatorFunctions.lastDay(input, unit);
        assertEquals(true, result.isNull());
    }
}
