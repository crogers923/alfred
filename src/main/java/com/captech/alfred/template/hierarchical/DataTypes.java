/*
 * Copyright 2018 CapTech Ventures, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.captech.alfred.template.hierarchical;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;

public class DataTypes {

    /**
     * if the current guess is to the right of a previous guess in this list, the current
     * guess will override the previous one.
     */
    private static final String[] rankOrder = {"anySimpleType", "date", "time", "dateTime",
            "integer", "decimal", "float", "string"};

    /**
     * Make the constructor private so no one can instantiate this
     */
    private DataTypes() {
    }

    /**
     * For now, we will try to distinguish values among decimal, integer and date/time
     *
     * @param value
     * @return
     */
    public static String guessDataType(String value) {
        if (value == null || value.isEmpty()) {
            return rankOrder[0];
        }
        if (isISO8601Time(value)) {
            return "timestamp";
        } else if (isISO8601Date(value)) {
            return "date";
        } else if (isISO8601DateTime(value)) {
            return "timestamp";
        } else if (isInteger(value)) {
            return "int";
        } else if (isDecimal(value)) {
            return "decimal";
        } else if (isFloat(value)) {
            return "float";
        } else if (isString(value)) {
            return "string";
        } else {
            return "anySimpleType";
        }
    }

    private static boolean isISO8601Time(String value) {
        try {
            if (value.split("-").length > 2 || value.contains("T") || value.split(":").length < 3) {
                return false;
            }
            java.time.OffsetTime.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isISO8601Date(String value) {
        try {
            if (value.split("-").length < 3 || value.contains("T")) {
                return false;
            }
            if (value.matches("\\d{4}-\\d{2}-\\d{2}([+-]\\d{2}:\\d{2}|Z)?")) {
                return true;
            }
            java.time.LocalDate.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isISO8601DateTime(String value) {
        try {
            if (!value.contains("T") || value.split(":").length < 3 || value.split("-").length < 3) {
                return false;
            }
            java.time.OffsetDateTime.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isInteger(String value) {
        try {
            new BigInteger(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDecimal(String value) {
        try {
            if (value.contains("e") || value.contains("E")) {
                return false;
            }
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isFloat(String value) {
        try {
            Float.parseFloat(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isString(String value) {
        return value != null;
    }

    public static int getRankOrder(String dataType) {
        for (int order = 0; order < rankOrder.length; order++) {
            if (StringUtils.equals(dataType, rankOrder[order])) {
                return order;
            }
        }
        return -1;
    }

}
