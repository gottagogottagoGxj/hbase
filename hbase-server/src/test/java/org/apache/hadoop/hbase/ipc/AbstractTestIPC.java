/*
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
package org.apache.hadoop.hbase.ipc;

import static org.apache.hadoop.hbase.ipc.TestProtobufRpcServiceImpl.SERVICE;
import static org.apache.hadoop.hbase.ipc.TestProtobufRpcServiceImpl.newBlockingStub;
import static org.apache.hadoop.hbase.ipc.TestProtobufRpcServiceImpl.newStub;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.ipc.RpcServer.BlockingServiceAndInterface;
import org.apache.hadoop.hbase.trace.HBaseSemanticAttributes;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.util.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.collect.ImmutableList;
import org.apache.hbase.thirdparty.com.google.common.collect.Lists;
import org.apache.hbase.thirdparty.com.google.protobuf.ServiceException;

import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestProtos.EchoRequestProto;
import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestProtos.EchoResponseProto;
import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestProtos.EmptyRequestProto;
import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestProtos.EmptyResponseProto;
import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestProtos.PauseRequestProto;
import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestRpcServiceProtos.TestProtobufRpcProto.BlockingInterface;
import org.apache.hadoop.hbase.shaded.ipc.protobuf.generated.TestRpcServiceProtos.TestProtobufRpcProto.Interface;
import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;

/**
 * Some basic ipc tests.
 */
public abstract class AbstractTestIPC {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractTestIPC.class);

  private static final byte[] CELL_BYTES = Bytes.toBytes("xyz");
  private static final KeyValue CELL = new KeyValue(CELL_BYTES, CELL_BYTES, CELL_BYTES, CELL_BYTES);

  protected static final Configuration CONF = HBaseConfiguration.create();
  static {
    // Set the default to be the old SimpleRpcServer. Subclasses test it and netty.
    CONF.set(RpcServerFactory.CUSTOM_RPC_SERVER_IMPL_CONF_KEY, SimpleRpcServer.class.getName());
  }

  protected abstract RpcServer createRpcServer(final Server server, final String name,
      final List<BlockingServiceAndInterface> services,
      final InetSocketAddress bindAddress, Configuration conf,
      RpcScheduler scheduler) throws IOException;

  protected abstract AbstractRpcClient<?> createRpcClientNoCodec(Configuration conf);


  @Rule
  public OpenTelemetryRule traceRule = OpenTelemetryRule.create();

  /**
   * Ensure we do not HAVE TO HAVE a codec.
   */
  @Test
  public void testNoCodec() throws IOException, ServiceException {
    Configuration conf = HBaseConfiguration.create();
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClientNoCodec(conf)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      HBaseRpcController pcrc = new HBaseRpcControllerImpl();
      String message = "hello";
      assertEquals(message,
        stub.echo(pcrc, EchoRequestProto.newBuilder().setMessage(message).build()).getMessage());
      assertNull(pcrc.cellScanner());
    } finally {
      rpcServer.stop();
    }
  }

  protected abstract AbstractRpcClient<?> createRpcClient(Configuration conf);

  /**
   * It is hard to verify the compression is actually happening under the wraps. Hope that if
   * unsupported, we'll get an exception out of some time (meantime, have to trace it manually to
   * confirm that compression is happening down in the client and server).
   */
  @Test
  public void testCompressCellBlock() throws IOException, ServiceException {
    Configuration conf = new Configuration(HBaseConfiguration.create());
    conf.set("hbase.client.rpc.compressor", GzipCodec.class.getCanonicalName());
    List<Cell> cells = new ArrayList<>();
    int count = 3;
    for (int i = 0; i < count; i++) {
      cells.add(CELL);
    }
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));

    try (AbstractRpcClient<?> client = createRpcClient(conf)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      HBaseRpcController pcrc = new HBaseRpcControllerImpl(CellUtil.createCellScanner(cells));
      String message = "hello";
      assertEquals(message,
        stub.echo(pcrc, EchoRequestProto.newBuilder().setMessage(message).build()).getMessage());
      int index = 0;
      CellScanner cellScanner = pcrc.cellScanner();
      assertNotNull(cellScanner);
      while (cellScanner.advance()) {
        assertEquals(CELL, cellScanner.current());
        index++;
      }
      assertEquals(count, index);
    } finally {
      rpcServer.stop();
    }
  }

  protected abstract AbstractRpcClient<?> createRpcClientRTEDuringConnectionSetup(
      Configuration conf) throws IOException;

  @Test
  public void testRTEDuringConnectionSetup() throws Exception {
    Configuration conf = HBaseConfiguration.create();
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClientRTEDuringConnectionSetup(conf)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      stub.ping(null, EmptyRequestProto.getDefaultInstance());
      fail("Expected an exception to have been thrown!");
    } catch (Exception e) {
      LOG.info("Caught expected exception: " + e.toString());
      assertTrue(e.toString(), StringUtils.stringifyException(e).contains("Injected fault"));
    } finally {
      rpcServer.stop();
    }
  }

  /**
   * Tests that the rpc scheduler is called when requests arrive.
   */
  @Test
  public void testRpcScheduler() throws IOException, ServiceException, InterruptedException {
    RpcScheduler scheduler = spy(new FifoRpcScheduler(CONF, 1));
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF, scheduler);
    verify(scheduler).init(any(RpcScheduler.Context.class));
    try (AbstractRpcClient<?> client = createRpcClient(CONF)) {
      rpcServer.start();
      verify(scheduler).start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      EchoRequestProto param = EchoRequestProto.newBuilder().setMessage("hello").build();
      for (int i = 0; i < 10; i++) {
        stub.echo(null, param);
      }
      verify(scheduler, times(10)).dispatch(any(CallRunner.class));
    } finally {
      rpcServer.stop();
      verify(scheduler).stop();
    }
  }

  /** Tests that the rpc scheduler is called when requests arrive. */
  @Test
  public void testRpcMaxRequestSize() throws IOException, ServiceException {
    Configuration conf = new Configuration(CONF);
    conf.setInt(RpcServer.MAX_REQUEST_SIZE, 1000);
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), conf,
        new FifoRpcScheduler(conf, 1));
    try (AbstractRpcClient<?> client = createRpcClient(conf)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      StringBuilder message = new StringBuilder(1200);
      for (int i = 0; i < 200; i++) {
        message.append("hello.");
      }
      // set total RPC size bigger than 100 bytes
      EchoRequestProto param = EchoRequestProto.newBuilder().setMessage(message.toString()).build();
      stub.echo(
        new HBaseRpcControllerImpl(CellUtil.createCellScanner(ImmutableList.<Cell> of(CELL))),
        param);
      fail("RPC should have failed because it exceeds max request size");
    } catch (ServiceException e) {
      LOG.info("Caught expected exception: " + e);
      assertTrue(e.toString(),
          StringUtils.stringifyException(e).contains("RequestTooBigException"));
    } finally {
      rpcServer.stop();
    }
  }

  /**
   * Tests that the RpcServer creates & dispatches CallRunner object to scheduler with non-null
   * remoteAddress set to its Call Object
   */
  @Test
  public void testRpcServerForNotNullRemoteAddressInCallObject()
      throws IOException, ServiceException {
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    InetSocketAddress localAddr = new InetSocketAddress("localhost", 0);
    try (AbstractRpcClient<?> client = createRpcClient(CONF)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      assertEquals(localAddr.getAddress().getHostAddress(),
        stub.addr(null, EmptyRequestProto.getDefaultInstance()).getAddr());
    } finally {
      rpcServer.stop();
    }
  }

  @Test
  public void testRemoteError() throws IOException, ServiceException {
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClient(CONF)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      stub.error(null, EmptyRequestProto.getDefaultInstance());
    } catch (ServiceException e) {
      LOG.info("Caught expected exception: " + e);
      IOException ioe = ProtobufUtil.handleRemoteException(e);
      assertTrue(ioe instanceof DoNotRetryIOException);
      assertTrue(ioe.getMessage().contains("server error!"));
    } finally {
      rpcServer.stop();
    }
  }

  @Test
  public void testTimeout() throws IOException {
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClient(CONF)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      HBaseRpcController pcrc = new HBaseRpcControllerImpl();
      int ms = 1000;
      int timeout = 100;
      for (int i = 0; i < 10; i++) {
        pcrc.reset();
        pcrc.setCallTimeout(timeout);
        long startTime = System.nanoTime();
        try {
          stub.pause(pcrc, PauseRequestProto.newBuilder().setMs(ms).build());
        } catch (ServiceException e) {
          long waitTime = (System.nanoTime() - startTime) / 1000000;
          // expected
          LOG.info("Caught expected exception: " + e);
          IOException ioe = ProtobufUtil.handleRemoteException(e);
          assertTrue(ioe.getCause() instanceof CallTimeoutException);
          // confirm that we got exception before the actual pause.
          assertTrue(waitTime < ms);
        }
      }
    } finally {
      rpcServer.stop();
    }
  }

  protected abstract RpcServer createTestFailingRpcServer(final Server server, final String name,
      final List<BlockingServiceAndInterface> services,
      final InetSocketAddress bindAddress, Configuration conf,
      RpcScheduler scheduler) throws IOException;

  /** Tests that the connection closing is handled by the client with outstanding RPC calls */
  @Test
  public void testConnectionCloseWithOutstandingRPCs() throws InterruptedException, IOException {
    Configuration conf = new Configuration(CONF);
    RpcServer rpcServer = createTestFailingRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));

    try (AbstractRpcClient<?> client = createRpcClient(conf)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      EchoRequestProto param = EchoRequestProto.newBuilder().setMessage("hello").build();
      stub.echo(null, param);
      fail("RPC should have failed because connection closed");
    } catch (ServiceException e) {
      LOG.info("Caught expected exception: " + e.toString());
    } finally {
      rpcServer.stop();
    }
  }

  @Test
  public void testAsyncEcho() throws IOException {
    Configuration conf = HBaseConfiguration.create();
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClient(conf)) {
      rpcServer.start();
      Interface stub = newStub(client, rpcServer.getListenerAddress());
      int num = 10;
      List<HBaseRpcController> pcrcList = new ArrayList<>();
      List<BlockingRpcCallback<EchoResponseProto>> callbackList = new ArrayList<>();
      for (int i = 0; i < num; i++) {
        HBaseRpcController pcrc = new HBaseRpcControllerImpl();
        BlockingRpcCallback<EchoResponseProto> done = new BlockingRpcCallback<>();
        stub.echo(pcrc, EchoRequestProto.newBuilder().setMessage("hello-" + i).build(), done);
        pcrcList.add(pcrc);
        callbackList.add(done);
      }
      for (int i = 0; i < num; i++) {
        HBaseRpcController pcrc = pcrcList.get(i);
        assertFalse(pcrc.failed());
        assertNull(pcrc.cellScanner());
        assertEquals("hello-" + i, callbackList.get(i).get().getMessage());
      }
    } finally {
      rpcServer.stop();
    }
  }

  @Test
  public void testAsyncRemoteError() throws IOException {
    AbstractRpcClient<?> client = createRpcClient(CONF);
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try {
      rpcServer.start();
      Interface stub = newStub(client, rpcServer.getListenerAddress());
      BlockingRpcCallback<EmptyResponseProto> callback = new BlockingRpcCallback<>();
      HBaseRpcController pcrc = new HBaseRpcControllerImpl();
      stub.error(pcrc, EmptyRequestProto.getDefaultInstance(), callback);
      assertNull(callback.get());
      assertTrue(pcrc.failed());
      LOG.info("Caught expected exception: " + pcrc.getFailed());
      IOException ioe = ProtobufUtil.handleRemoteException(pcrc.getFailed());
      assertTrue(ioe instanceof DoNotRetryIOException);
      assertTrue(ioe.getMessage().contains("server error!"));
    } finally {
      client.close();
      rpcServer.stop();
    }
  }

  @Test
  public void testAsyncTimeout() throws IOException {
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
        Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(
            SERVICE, null)), new InetSocketAddress("localhost", 0), CONF,
        new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClient(CONF)) {
      rpcServer.start();
      Interface stub = newStub(client, rpcServer.getListenerAddress());
      List<HBaseRpcController> pcrcList = new ArrayList<>();
      List<BlockingRpcCallback<EmptyResponseProto>> callbackList = new ArrayList<>();
      int ms = 1000;
      int timeout = 100;
      long startTime = System.nanoTime();
      for (int i = 0; i < 10; i++) {
        HBaseRpcController pcrc = new HBaseRpcControllerImpl();
        pcrc.setCallTimeout(timeout);
        BlockingRpcCallback<EmptyResponseProto> callback = new BlockingRpcCallback<>();
        stub.pause(pcrc, PauseRequestProto.newBuilder().setMs(ms).build(), callback);
        pcrcList.add(pcrc);
        callbackList.add(callback);
      }
      for (BlockingRpcCallback<?> callback : callbackList) {
        assertNull(callback.get());
      }
      long waitTime = (System.nanoTime() - startTime) / 1000000;
      for (HBaseRpcController pcrc : pcrcList) {
        assertTrue(pcrc.failed());
        LOG.info("Caught expected exception: " + pcrc.getFailed());
        IOException ioe = ProtobufUtil.handleRemoteException(pcrc.getFailed());
        assertTrue(ioe.getCause() instanceof CallTimeoutException);
      }
      // confirm that we got exception before the actual pause.
      assertTrue(waitTime < ms);
    } finally {
      rpcServer.stop();
    }
  }

  private void assertSameTraceId() {
    String traceId = traceRule.getSpans().get(0).getTraceId();
    for (SpanData data : traceRule.getSpans()) {
      // assert we are the same trace
      assertEquals(traceId, data.getTraceId());
    }
  }

  private SpanData waitSpan(String name) {
    Waiter.waitFor(CONF, 1000,
      () -> traceRule.getSpans().stream().map(SpanData::getName).anyMatch(s -> s.equals(name)));
    return traceRule.getSpans().stream().filter(s -> s.getName().equals(name)).findFirst().get();
  }

  private void assertRpcAttribute(SpanData data, String methodName, InetSocketAddress addr,
    SpanKind kind) {
    assertEquals(SERVICE.getDescriptorForType().getName(),
      data.getAttributes().get(HBaseSemanticAttributes.RPC_SERVICE_KEY));
    assertEquals(methodName, data.getAttributes().get(HBaseSemanticAttributes.RPC_METHOD_KEY));
    if (addr != null) {
      assertEquals(
        addr.getHostName(),
        data.getAttributes().get(HBaseSemanticAttributes.REMOTE_HOST_KEY));
      assertEquals(
        addr.getPort(),
        data.getAttributes().get(HBaseSemanticAttributes.REMOTE_PORT_KEY).intValue());
    }
    assertEquals(kind, data.getKind());
  }

  private void assertRemoteSpan() {
    SpanData data = waitSpan("RpcServer.process");
    assertTrue(data.getParentSpanContext().isRemote());
    assertEquals(SpanKind.SERVER, data.getKind());
  }

  @Test
  public void testTracing() throws IOException, ServiceException {
    RpcServer rpcServer = createRpcServer(null, "testRpcServer",
      Lists.newArrayList(new RpcServer.BlockingServiceAndInterface(SERVICE, null)),
      new InetSocketAddress("localhost", 0), CONF, new FifoRpcScheduler(CONF, 1));
    try (AbstractRpcClient<?> client = createRpcClient(CONF)) {
      rpcServer.start();
      BlockingInterface stub = newBlockingStub(client, rpcServer.getListenerAddress());
      stub.pause(null, PauseRequestProto.newBuilder().setMs(100).build());
      assertRpcAttribute(waitSpan("RpcClient.callMethod"), "pause", rpcServer.getListenerAddress(),
        SpanKind.CLIENT);
      assertRpcAttribute(waitSpan("RpcServer.callMethod"), "pause", null, SpanKind.INTERNAL);
      assertRemoteSpan();
      assertSameTraceId();
      for (SpanData data : traceRule.getSpans()) {
        assertThat(
          TimeUnit.NANOSECONDS.toMillis(data.getEndEpochNanos() - data.getStartEpochNanos()),
          greaterThanOrEqualTo(100L));
        assertEquals(StatusCode.OK, data.getStatus().getStatusCode());
      }

      traceRule.clearSpans();
      assertThrows(ServiceException.class,
        () -> stub.error(null, EmptyRequestProto.getDefaultInstance()));
      assertRpcAttribute(waitSpan("RpcClient.callMethod"), "error", rpcServer.getListenerAddress(),
        SpanKind.CLIENT);
      assertRpcAttribute(waitSpan("RpcServer.callMethod"), "error", null, SpanKind.INTERNAL);
      assertRemoteSpan();
      assertSameTraceId();
      for (SpanData data : traceRule.getSpans()) {
        assertEquals(StatusCode.ERROR, data.getStatus().getStatusCode());
      }
    }
  }
}
