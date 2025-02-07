/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jdvn.setl.geos.processors.gss;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Before;
import org.junit.Test;


public class FetchGSSTest {

    private TestRunner testRunner;

    @Before
    public void init() {
        testRunner = TestRunners.newTestRunner(FetchGSS.class);
    }

    @Test
    public void testProcessor() {

    	String dateStr = "2022-10-25 16.15.56.963";
    	DateFormat readFormat = new SimpleDateFormat( "yyyy-MM-dd HH.mm.ss.S");
    	DateFormat writeFormat = new SimpleDateFormat( "yyyy-MM-dd HH.mm.ss.S");
    	Date date = null;
    	try {
    	    date = readFormat.parse(dateStr);
    	} catch (ParseException e) {
    	    e.printStackTrace();
    	}

    	if (date != null) {
    	    String formattedDate = writeFormat.format(date);
    	    System.out.println(formattedDate);
    	}
    }

}
