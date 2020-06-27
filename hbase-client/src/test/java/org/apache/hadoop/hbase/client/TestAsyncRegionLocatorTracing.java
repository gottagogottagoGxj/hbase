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
package org.apache.hadoop.hbase.client;

import static org.junit.Assert.assertEquals;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.ipc.RpcClient;
import org.apache.hadoop.hbase.ipc.RpcClientFactory;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.UserProvider;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.trace.TraceUtil;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.hbase.thirdparty.com.google.common.io.Closeables;
import org.apache.hbase.thirdparty.com.google.protobuf.BlockingRpcChannel;
import org.apache.hbase.thirdparty.com.google.protobuf.Descriptors.MethodDescriptor;
import org.apache.hbase.thirdparty.com.google.protobuf.Message;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcCallback;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcChannel;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcController;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProtos.LocateMetaRegionResponse;

@Category({ ClientTests.class, MediumTests.class })
public class TestAsyncRegionLocatorTracing {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestAsyncRegionLocatorTracing.class);

  private static Configuration CONF = HBaseConfiguration.create();

  private AsyncConnectionImpl conn;

  private static RegionLocations locs;

  @Rule
  public OpenTelemetryRule traceRule = OpenTelemetryRule.create();

  public static final class RpcClientForTest implements RpcClient {

    public RpcClientForTest(Configuration configuration, String clusterId,
      SocketAddress localAddress, MetricsConnection metrics) {
    }

    @Override
    public BlockingRpcChannel createBlockingRpcChannel(ServerName sn, User user, int rpcTimeout) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public RpcChannel createRpcChannel(ServerName sn, User user, int rpcTimeout) {
      return new RpcChannel() {

        @Override
        public void callMethod(MethodDescriptor method, RpcController controller, Message request,
          Message responsePrototype, RpcCallback<Message> done) {
          LocateMetaRegionResponse.Builder builder = LocateMetaRegionResponse.newBuilder();
          for (HRegionLocation loc : locs) {
            if (loc != null) {
              builder.addMetaLocations(ProtobufUtil.toRegionLocation(loc));
            }
          }
          done.run(builder.build());
        }
      };
    }

    @Override
    public void cancelConnections(ServerName sn) {
    }

    @Override
    public void close() {
    }

    @Override
    public boolean hasCellBlockSupport() {
      return false;
    }
  }

  @BeforeClass
  public static void setUpBeforeClass() {
    CONF.setClass(RpcClientFactory.CUSTOM_RPC_CLIENT_IMPL_CONF_KEY, RpcClientForTest.class,
      RpcClient.class);
  }

  @Before
  public void setUp() throws IOException {
    RegionInfo metaRegionInfo = RegionInfoBuilder.FIRST_META_REGIONINFO;
    locs = new RegionLocations(
      new HRegionLocation(metaRegionInfo,
        ServerName.valueOf("127.0.0.1", 12345, EnvironmentEdgeManager.currentTime())),
      new HRegionLocation(RegionReplicaUtil.getRegionInfoForReplica(metaRegionInfo, 1),
        ServerName.valueOf("127.0.0.2", 12345, EnvironmentEdgeManager.currentTime())),
      new HRegionLocation(RegionReplicaUtil.getRegionInfoForReplica(metaRegionInfo, 2),
        ServerName.valueOf("127.0.0.3", 12345, EnvironmentEdgeManager.currentTime())));
    conn = new AsyncConnectionImpl(CONF, new DoNothingConnectionRegistry(CONF) {

      @Override
      public CompletableFuture<ServerName> getActiveMaster() {
        return CompletableFuture.completedFuture(
          ServerName.valueOf("127.0.0.1", 12345, EnvironmentEdgeManager.currentTime()));
      }
    }, "test", null, UserProvider.instantiate(CONF).getCurrent());
  }

  @After
  public void tearDown() throws IOException {
    Closeables.close(conn, true);
  }

  private SpanData waitSpan(String name) {
    Waiter.waitFor(CONF, 1000,
      () -> traceRule.getSpans().stream().map(SpanData::getName).anyMatch(s -> s.equals(name)));
    return traceRule.getSpans().stream().filter(s -> s.getName().equals(name)).findFirst().get();
  }

  @Test
  public void testClearCache() {
    conn.getLocator().clearCache();
    SpanData span = waitSpan("AsyncRegionLocator.clearCache");
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
  }

  @Test
  public void testClearCacheServerName() {
    ServerName sn = ServerName.valueOf("127.0.0.1", 12345, EnvironmentEdgeManager.currentTime());
    conn.getLocator().clearCache(sn);
    SpanData span = waitSpan("AsyncRegionLocator.clearCache");
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(sn.toString(), span.getAttributes().get(TraceUtil.SERVER_NAME_KEY));
  }

  @Test
  public void testClearCacheTableName() {
    conn.getLocator().clearCache(TableName.META_TABLE_NAME);
    SpanData span = waitSpan("AsyncRegionLocator.clearCache");
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(TableName.META_TABLE_NAME.getNamespaceAsString(),
      span.getAttributes().get(TraceUtil.NAMESPACE_KEY));
    assertEquals(TableName.META_TABLE_NAME.getNameAsString(),
      span.getAttributes().get(TraceUtil.TABLE_KEY));
  }

  @Test
  public void testGetRegionLocation() {
    conn.getLocator().getRegionLocation(TableName.META_TABLE_NAME, HConstants.EMPTY_START_ROW,
      RegionLocateType.CURRENT, TimeUnit.SECONDS.toNanos(1)).join();
    SpanData span = waitSpan("AsyncRegionLocator.getRegionLocation");
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(TableName.META_TABLE_NAME.getNamespaceAsString(),
      span.getAttributes().get(TraceUtil.NAMESPACE_KEY));
    assertEquals(TableName.META_TABLE_NAME.getNameAsString(),
      span.getAttributes().get(TraceUtil.TABLE_KEY));
    List<String> regionNames = span.getAttributes().get(TraceUtil.REGION_NAMES_KEY);
    assertEquals(1, regionNames.size());
    assertEquals(locs.getDefaultRegionLocation().getRegion().getRegionNameAsString(),
      regionNames.get(0));
  }

  @Test
  public void testGetRegionLocations() {
    conn.getLocator().getRegionLocations(TableName.META_TABLE_NAME, HConstants.EMPTY_START_ROW,
      RegionLocateType.CURRENT, false, TimeUnit.SECONDS.toNanos(1)).join();
    SpanData span = waitSpan("AsyncRegionLocator.getRegionLocations");
    assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
    assertEquals(TableName.META_TABLE_NAME.getNamespaceAsString(),
      span.getAttributes().get(TraceUtil.NAMESPACE_KEY));
    assertEquals(TableName.META_TABLE_NAME.getNameAsString(),
      span.getAttributes().get(TraceUtil.TABLE_KEY));
    List<String> regionNames = span.getAttributes().get(TraceUtil.REGION_NAMES_KEY);
    assertEquals(3, regionNames.size());
    for (int i = 0; i < 3; i++) {
      assertEquals(locs.getRegionLocation(i).getRegion().getRegionNameAsString(),
        regionNames.get(i));
    }
  }
}