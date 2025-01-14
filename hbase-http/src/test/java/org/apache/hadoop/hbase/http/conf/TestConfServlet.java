/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.http.conf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.apache.hbase.thirdparty.org.eclipse.jetty.util.ajax.JSON;

/**
 * Basic test case that the ConfServlet can write configuration
 * to its output in XML and JSON format.
 */
@Category({ MiscTests.class, SmallTests.class })
public class TestConfServlet {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
      HBaseClassTestRule.forClass(TestConfServlet.class);

  private static final String TEST_KEY = "testconfservlet.key";
  private static final String TEST_VAL = "testval";

  private Configuration getTestConf() {
    Configuration testConf = new Configuration();
    testConf.set(TEST_KEY, TEST_VAL);
    return testConf;
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWriteJson() throws Exception {
    StringWriter sw = new StringWriter();
    ConfServlet.writeResponse(getTestConf(), sw, "json");
    String json = sw.toString();
    boolean foundSetting = false;
    Set<String> programSet = new HashSet<>();
    programSet.add("programatically");
    programSet.add("programmatically");
    Object parsed = JSON.parse(json);
    Object[] properties = ((Map<String, Object[]>)parsed).get("properties");
    for (Object o : properties) {
      Map<String, Object> propertyInfo = (Map<String, Object>)o;
      String key = (String)propertyInfo.get("key");
      String val = (String)propertyInfo.get("value");
      String resource = (String)propertyInfo.get("resource");
      System.err.println("k: " + key + " v: " + val + " r: " + resource);
      if (TEST_KEY.equals(key) && TEST_VAL.equals(val)
          && programSet.contains(resource)) {
        foundSetting = true;
      }
    }
    assertTrue(foundSetting);
  }

  @Test
  public void testWriteXml() throws Exception {
    StringWriter sw = new StringWriter();
    ConfServlet.writeResponse(getTestConf(), sw, "xml");
    String xml = sw.toString();

    DocumentBuilderFactory docBuilderFactory
      = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = docBuilderFactory.newDocumentBuilder();
    Document doc = builder.parse(new InputSource(new StringReader(xml)));
    NodeList nameNodes = doc.getElementsByTagName("name");
    boolean foundSetting = false;
    for (int i = 0; i < nameNodes.getLength(); i++) {
      Node nameNode = nameNodes.item(i);
      String key = nameNode.getTextContent();
      System.err.println("xml key: " + key);
      if (TEST_KEY.equals(key)) {
        foundSetting = true;
        Element propertyElem = (Element)nameNode.getParentNode();
        String val = propertyElem.getElementsByTagName("value").item(0).getTextContent();
        assertEquals(TEST_VAL, val);
      }
    }
    assertTrue(foundSetting);
  }

  @Test
  public void testBadFormat() throws Exception {
    StringWriter sw = new StringWriter();
    try {
      ConfServlet.writeResponse(getTestConf(), sw, "not a format");
      fail("writeResponse with bad format didn't throw!");
    } catch (ConfServlet.BadFormatException bfe) {
      // expected
    }
    assertEquals("", sw.toString());
  }
}
