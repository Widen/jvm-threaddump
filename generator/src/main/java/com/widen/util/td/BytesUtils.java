/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.widen.util.td;

import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.Locale;

class BytesUtils
{

    private static final NumberFormat format = NumberFormat.getNumberInstance(Locale.US);

    /**
     * The number of bytes in a kilobyte.
     */
    private static final long ONE_KB = 1024;

    /**
     * The number of bytes in a kilobyte.
     *
     * @since 2.4
     */
    private static final BigInteger ONE_KB_BI = BigInteger.valueOf(ONE_KB);

    /**
     * The number of bytes in a megabyte.
     *
     * @since 2.4
     */
    private static final BigInteger ONE_MB_BI = ONE_KB_BI.multiply(ONE_KB_BI);

    static String byteCountToDisplaySize(long input) {
        BigInteger size = BigInteger.valueOf(input);
        String displaySize;

        if (size.divide(ONE_MB_BI).compareTo(BigInteger.ZERO) > 0) {
            BigInteger result = size.divide(ONE_MB_BI);
            displaySize = format.format(result) + " MB";
        }
        else if (size.divide(ONE_KB_BI).compareTo(BigInteger.ZERO) > 0) {
            BigInteger result = size.divide(ONE_KB_BI);
            displaySize = format.format(result) + " KB";
        }
        else {
            displaySize = format.format(size) + " bytes";
        }
        return displaySize;
    }
}
