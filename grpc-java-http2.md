# How grpc-java turns calls into HTTP/2 frames

What grpc-java does between `stub.call(request)` and the bytes on the TCP connection:
- how the HEADERS and DATA frames of a call are built;
- when they are written;
- how frames from several calls share one connection.

[grpc-in-kubernetes.md](grpc-in-kubernetes.md) §1 describes the protocol itself (frames, the 5-byte
message prefix, trailers, HPACK) from Go and Python captures. This file is the Java side, and the
code that produces it.

Labels: **(code)** read in grpc-java v1.86.0 (`fe53026`, 2026-11-24) or in Netty 4.2.17.Final,
which v1.86.0 uses; **(lab)** seen in the runs below.

## 0. Short answers

- **Every call is one HTTP/2 stream.** The client gives it the next odd stream id: 3, 5, 7 and so
  on. Stream 1 is skipped because Netty reserves it for an HTTP/1.1 upgrade.
- **The request is a HEADERS frame, then DATA frames.**
  - The HEADERS frame carries `:method POST`, `:path /<service>/<method>`, `content-type:
    application/grpc`, `te: trailers` and your metadata.
  - Each message is a 5-byte prefix (a compressed flag and a 4-byte length) followed by the
    marshalled bytes.
  - The client ends its side with END_STREAM: on the DATA frame of the last message for unary
    calls, or on an empty DATA frame for streaming ones.
- **The response is HEADERS (`:status 200`), DATA, then trailers.** The trailers are a second
  HEADERS frame with `grpc-status` and END_STREAM. A call that fails before sending anything gets a
  single HEADERS frame holding both ("trailers-only").
- **Your thread doesn't write to the socket.** It queues commands (create a stream, send a frame)
  in a `WriteQueue`. The connection's Netty event loop thread runs them and flushes. For unary
  calls the stream creation isn't flushed on its own, so HEADERS and the message leave in one
  write.
- **Three kinds of thread take part in a call** (§3.0). Your thread builds and queues the request.
  The connection's event loop writes and reads frames. Your service method runs on the server's
  executor (`grpc-default-executor`), never on the event loop. A blocking stub's response
  callbacks run on your own waiting thread.
- **Interleaving happens in Netty's flow controller.** DATA from all streams waits there. Netty's
  `UniformStreamByteDistributor` gives each stream a turn of at least 16 KiB, round-robin, and the
  frame writer cuts each turn into frames of at most 16384 bytes. With two large downloads in the
  lab, the server alternated between the streams about every 32 KiB.
- **Headers shrink after the first call.** HPACK's dynamic table on the connection remembers them:
  the second call's request headers were 8 bytes, against 118 for the first. grpc-java never uses
  Huffman coding, so the first ones are plain ASCII on the wire.

## 1. The lab

- **One Java program, `H2Lab`.**
  - It starts a grpc-java server on port 50551 and a client channel to port 50552.
  - Both use the Netty transport (`grpc-netty` 1.86.0, not the shaded jar), in plaintext, so
    HTTP/2 runs over TCP without TLS ("h2c").
  - The methods use an identity marshaller (`byte[]` in, `byte[]` out) instead of protobuf, so the
    bytes in each message are exactly what the program wrote.

  | Method | Type | What it does |
  |---|---|---|
  | `lab.Echo/Upper` | unary | returns the request in upper case |
  | `lab.Echo/Download` | server streaming | request `"<count> <size> <letter>"`: sends `<count>` messages of `<size>` bytes |
  | `lab.Echo/Upload` | client streaming | answers `got <total bytes>` |
  | `lab.Echo/Fail` | unary | fails with `NOT_FOUND`, an extra trailer `x-reason` |

- **A TCP relay between them.**
  - A Python relay listens on 50552 and forwards to 50551.
  - It logs each chunk it reads from either side, and the HTTP/2 frames in it: type, flags, stream,
    length, and the raw HPACK bytes.
  - A chunk is one `recv()`, so it shows which frames arrived together. It doesn't show exactly how
    the sender split its writes.
- **Netty's frame logger** on both ends. `NettyClientHandler` and `NettyServerHandler` already wrap
  their frame reader and writer in Netty's `Http2FrameLogger` at DEBUG (code). Setting those two
  java.util.logging loggers to `FINE` prints every frame, with decoded headers. One difference from
  the relay: the logger records each `writeData` call. A 40 005-byte DATA write is logged once,
  but goes on the wire as three frames (§5).

The `:authority` in the logs is `localhost:50552`, the relay's port, because that's what the client
dialled.

### 1.1 How the lab creates the server and the client (code)

The server, from `H2Lab.java`. A method is a `MethodDescriptor` (name, type, two marshallers)
plus a handler. Generated protobuf stubs build the same two things for you.

```java
static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<>() {
    public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
    public byte[] parse(InputStream in) { return in.readAllBytes(); }  // (try/catch left out)
};

static final MethodDescriptor<byte[], byte[]> UNARY = MethodDescriptor.<byte[], byte[]>newBuilder()
    .setType(MethodDescriptor.MethodType.UNARY)
    .setFullMethodName("lab.Echo/Upper")              // what :path carries, after the "/"
    .setRequestMarshaller(BYTES).setResponseMarshaller(BYTES)
    .build();

ServerServiceDefinition svc = ServerServiceDefinition.builder("lab.Echo")
    .addMethod(UNARY, ServerCalls.asyncUnaryCall((byte[] req, StreamObserver<byte[]> out) -> {
        out.onNext(new String(req).toUpperCase().getBytes());
        out.onCompleted();
    }))
    // Download, Upload and Fail are added the same way
    .build();
Server server = NettyServerBuilder.forPort(50551).addService(svc).build().start();
```

What `build().start()` sets up (code):
- **A method registry.** `addService` files each method under its full name, `lab.Echo/Upper`.
  A request's `:path` without the leading `/` is looked up there.
- **A listening socket.** `NettyServer.start` binds a Netty `ServerBootstrap`. For each accepted
  connection, a `ChannelInitializer` creates a `NettyServerTransport`. That puts
  `NettyServerHandler` (Netty's HTTP/2 codec plus grpc-java's handler) in the connection's
  pipeline. The connection is tied to one event loop thread of the worker group for its whole life.
- **An executor for your code.** Service methods run on the builder's `executor(...)`. By default
  that's the shared, unbounded cached pool `grpc-default-executor`
  (`GrpcUtil.SHARED_CHANNEL_EXECUTOR`).

The client:

```java
ManagedChannel ch = NettyChannelBuilder.forAddress("localhost", 50552).usePlaintext().build();
byte[] reply = ClientCalls.blockingUnaryCall(ch, UNARY, CallOptions.DEFAULT, "hello".getBytes());
```

`ClientCalls.blockingUnaryCall` is what a generated blocking stub's `upper(request)` calls. What the
channel does (code):
- **`build()` doesn't connect.** It returns a `ManagedChannelImpl` in idle mode.
- **The first call, or `getState(true)`, leaves idle mode.**
  - The name resolver resolves `localhost`.
  - The default load balancer, `pick_first` (`GrpcUtil.DEFAULT_LB_POLICY`), creates a subchannel.
  - `NettyClientTransport.start` connects a Netty `Bootstrap`, with `NettyClientHandler` in the
    pipeline.
- **Calls made before the connection is ready wait** in `DelayedClientTransport` (§6 shows what
  that does to the frames).
- **Both builders use the same event loop group by default**,
  `Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP` (NIO on macOS, epoll on Linux when it's available). In
  the lab, with client and server in one JVM, the client's connection ran on
  `grpc-nio-worker-ELG-3-2` and the server's end on `-3-3`.

## 2. The connection: preface, SETTINGS, window (lab + code)

Before any call:

```text
relay (ms since start)
 1596.4 server→client   27 B  SETTINGS
 1598.3 client→server   57 B  PREFACE (24 B), SETTINGS
 1600.4 client→server   13 B  WINDOW_UPDATE (stream 0)
 1600.6 server→client   13 B  WINDOW_UPDATE (stream 0)
 1608.8 server→client    9 B  SETTINGS ack
 1623.2 client→server    9 B  SETTINGS ack
```

```text
frame logger
Client OUTBOUND SETTINGS: settings={ENABLE_PUSH=0, MAX_CONCURRENT_STREAMS=0, INITIAL_WINDOW_SIZE=1048576, MAX_HEADER_LIST_SIZE=8192}
Server OUTBOUND SETTINGS: settings={MAX_CONCURRENT_STREAMS=2147483647, INITIAL_WINDOW_SIZE=1048576, MAX_HEADER_LIST_SIZE=8192}
Client OUTBOUND WINDOW_UPDATE: streamId=0 windowSizeIncrement=983041
Server OUTBOUND WINDOW_UPDATE: streamId=0 windowSizeIncrement=983041
```

- **The preface.** The client starts with the fixed 24 bytes `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`,
  then its SETTINGS. In h2c with prior knowledge there's no HTTP/1.1 upgrade first.
- **The client's SETTINGS** come from `NettyClientHandler.newHandler` (code):
  - `ENABLE_PUSH=0`: no server push;
  - `MAX_CONCURRENT_STREAMS=0`: the server may not open streams towards the client;
  - `INITIAL_WINDOW_SIZE=1048576`: each stream may receive 1 MiB before a WINDOW_UPDATE. That's
    `DEFAULT_FLOW_CONTROL_WINDOW` in `NettyChannelBuilder` and `NettyServerBuilder`;
  - `MAX_HEADER_LIST_SIZE=8192`: `GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE`.
- **The server allows unlimited concurrent streams** (`2147483647`) unless
  `maxConcurrentCallsPerConnection` is set.
- **The WINDOW_UPDATE on stream 0.** `INITIAL_WINDOW_SIZE` only applies to streams. The connection
  window always starts at 65 535, so each side raises its own with one WINDOW_UPDATE of
  1 048 576 − 65 535 = 983 041.

## 3. A unary call, from the stub to the bytes

### 3.0 One call end to end, and which thread runs each step (code + lab)

**On the client, your thread** (`ClientCalls`, `ClientCallImpl`):

1. `blockingUnaryCall` creates a `ThreadlessExecutor` and calls
   `channel.newCall(method, callOptions.withExecutor(executor))`. Each interceptor wraps the
   channel. The innermost is `ManagedChannelImpl`'s `RealChannel`, which returns a `ClientCallImpl`.
2. `asyncUnaryRequestCall` then makes four calls on it, in this order:
   `start(listener, new Metadata())`, `request(2)` (two, so that a server sending a second
   response is caught), `sendMessage(req)`, `halfClose()`.
3. `ClientCallImpl.start`:
   - `prepareHeaders` adds `grpc-accept-encoding` and, if set, `grpc-encoding`;
   - it works out the deadline;
   - `clientStreamProvider.newStream(...)` lets the load balancer pick a transport, and
     `NettyClientTransport.newStream` returns a `NettyClientStream`;
   - `stream.start()` calls `Sink.writeHeaders`, which enqueues a `CreateStreamCommand` (§3.1).
4. `sendMessage` calls `stream.writeMessage(method.streamRequest(req))`. The request marshaller
   turns the message into an `InputStream`, and the `MessageFramer` adds the 5-byte prefix.
   `halfClose` closes the framer, which enqueues `SendGrpcFrameCommand(endOfStream=true)` with a
   flush.
5. Your thread then parks in `executor.waitAndDrain()` until the response future is done.

**On the client's event loop:**
- `NettyClientHandler.onHeadersRead` and `onDataRead` pass the frames to the stream's
  `TransportState`. Its `MessageDeframer` takes the 5-byte prefix off each message.
- `ClientStreamListenerImpl.headersRead`, `messagesAvailable` and `closed` don't call your listener.
  Each wraps the work in a `Runnable` and hands it to the call's executor.
- For a blocking call, that executor is the `ThreadlessExecutor`. The `Runnable`s are queued, and
  your parked thread wakes up and runs them:
  - `onMessage` stores the response, after the response marshaller parses it;
  - `onClose(OK)` completes the future;
  - `blockingUnaryCall` returns the value. A non-OK status is thrown as `StatusRuntimeException`.
- With an async or future stub the same `Runnable`s run on the channel's executor instead,
  `grpc-default-executor` by default.

**On the server's event loop** (`NettyServerHandler`):
1. `onHeadersRead` validates the request (§3.5) and takes the method name from `:path`.
2. It creates a `NettyServerStream` and calls `transportListener.streamCreated(stream, method,
   metadata)`.

**On the server, `ServerImpl` and the executor:**
1. **`streamCreated` doesn't run your code on the event loop.**
   - It wraps the executor in a `SerializingExecutor`, so one call's callbacks run in order, one at
     a time.
   - It queues two tasks: `MethodLookup`, then `HandleServerCall`.
2. **Until those have run, the stream's listener is a
   `JumpToApplicationThreadServerStreamListener`.** Every event the event loop reports (messages
   available, half-close, closed) becomes a task on that same executor.
3. **`MethodLookup` finds the method:** `registry.lookupMethod("lab.Echo/Upper")`, then the
   fallback registry. If neither has it, the call fails with `UNIMPLEMENTED`.
4. **`HandleServerCall` starts the call.** It creates the `ServerCallImpl` and runs the
   interceptors, then the handler's `startCall`.
5. **For `ServerCalls.asyncUnaryCall`, the handler is `UnaryServerCallHandler`.**
   - Its `startCall` calls `call.request(2)`, so a client that sends two requests is caught.
   - It returns a `UnaryServerCallListener`. Its `onMessage` only stores the request.
   - `onHalfClose` calls your method. A unary method never runs before the client's END_STREAM.
6. **Your `out.onNext(r)` sends the headers, then the message.** `ServerCallStreamObserverImpl`
   calls `call.sendHeaders(new Metadata())` on the first response only, then `call.sendMessage(r)`.
7. **`out.onCompleted()` sends the trailers:** `call.close(Status.OK, new Metadata())`. `onError(t)`
   instead calls `call.close(Status.fromThrowable(t), trailers)` (§8).
8. **Writes go to the event loop.** Each of these goes through `NettyServerStream.Sink` to the
   connection's `WriteQueue`, and the event loop writes the frames.

**The lab** (`Flow.java`, the same unary method).
- A client and a server interceptor log every `ClientCall` and `ServerCall` method, and each
  listener callback, with the thread that ran it.
- A `java.util.logging` handler on Netty's frame logger adds the frames.
- The channel was connected first, so the trace holds only the call. Pings are left out. The times
  include first-call class loading.

```text
    ms  thread                 side   step
   0.0  main                   client --- blockingUnaryCall("hello") ---
   2.4  main                   client channel.newCall(lab.Echo/Upper)
  33.6  main                   client call.start()
  61.2  main                   client call.request(2)
  63.2  main                   client call.sendMessage()
  71.5  main                   client call.halfClose()
  72.8  ELG-3-2                client OUTBOUND HEADERS stream 3 [:authority …] endStream=false
  92.1  ELG-3-2                client OUTBOUND DATA stream 3 endStream=true length=10
  99.8  ELG-3-3                server INBOUND HEADERS stream 3 [:path: /lab.Ech…] endStream=false
 107.0  ELG-3-3                server INBOUND DATA stream 3 endStream=true length=10
 114.0  grpc-default-executor  server interceptCall(lab.Echo/Upper)
 114.7  grpc-default-executor  server call.request(2)
 117.4  grpc-default-executor  server listener.onMessage()
 117.5  grpc-default-executor  server listener.onHalfClose()
 117.8  grpc-default-executor  server Upper handler runs, request "hello"
 117.9  grpc-default-executor  server call.sendHeaders()
 118.7  grpc-default-executor  server call.sendMessage()
 119.5  grpc-default-executor  server call.close(OK)
 120.5  ELG-3-3                server OUTBOUND HEADERS stream 3 [:status: 200 …] endStream=false
 121.6  ELG-3-3                server OUTBOUND DATA stream 3 endStream=false length=10
 121.9  ELG-3-3                server OUTBOUND HEADERS stream 3 [grpc-status: 0 …] endStream=true
 123.0  ELG-3-2                client INBOUND HEADERS stream 3 [:status: 200 …] endStream=false
 123.2  grpc-default-executor  server listener.onComplete()
 123.7  main                   client listener.onHeaders()
 123.8  ELG-3-2                client INBOUND DATA stream 3 endStream=false length=10
 124.5  main                   client listener.onMessage()
 124.6  ELG-3-2                client INBOUND HEADERS stream 3 [grpc-status: 0 …] endStream=true
 125.7  main                   client listener.onClose(OK)
 126.1  main                   client blockingUnaryCall returned "HELLO"
```

`ELG-3-2` and `ELG-3-3` are the event loop threads `grpc-nio-worker-ELG-3-2` and `-3-3`.

What the trace shows (lab):
- **`main` never touched the socket.** It made the four `ClientCall` calls. The frames were written
  afterwards by the client's event loop.
- **The server read both frames before any service code ran.** HEADERS and DATA arrived at
  99.8 and 107.0 ms. The method lookup and `interceptCall` ran at 114.0 on the executor, and the
  message was held in the stream until the listener was set. Your handler ran only after
  `onHalfClose`.
- **The response was written after `close()` returned.** The handler's three calls only queued
  commands. The event loop wrote HEADERS, DATA and trailers afterwards, at 120.5–121.9.
- **The blocking client's callbacks ran on `main`.** Each one ran just after the event loop read the
  frame it reports, because `main` was parked in the `ThreadlessExecutor`.

### 3.1 The path (code)

```text
┌─ application thread (your code, the stub) ───────────────────────────────────────┐
│ ClientCall.start()      → Sink.writeHeaders: Metadata → Http2Headers             │
│                           enqueue CreateStreamCommand (no flush if unary)        │
│ ClientCall.sendMessage() → MessageFramer: 5-byte prefix + marshalled bytes       │
│ ClientCall.halfClose()  → enqueue SendGrpcFrameCommand(endOfStream=true)         │
└──────────────────────────────────────────────────────────────────────────────────┘
  │  WriteQueue: a ConcurrentLinkedQueue; a flush schedules one drain
  ▼
┌─ Netty event loop thread (each connection is tied to one) ───────────────────────┐
│ WriteQueue.flush(): run every queued command, then channel.flush()               │
│   createStream  → next stream id (3, 5, 7 …) → encoder.writeHeaders              │
│   sendGrpcFrame → encoder.writeData → queued in the remote flow controller       │
│ channel.flush() → UniformStreamByteDistributor: round-robin turns                │
│                 → DefaultHttp2FrameWriter: frames of ≤ MAX_FRAME_SIZE            │
└──────────────────────────────────────────────────────────────────────────────────┘
  │  the flushed frames go to the socket together
  ▼
┌─ TCP connection ─────────────────────────────────────────────────────────────────┐
│ HEADERS(3) DATA(3, END_STREAM) …  frames of all streams, interleaved             │
└──────────────────────────────────────────────────────────────────────────────────┘
```

- **`WriteQueue`** (`netty/WriteQueue.java`) is the only path from other threads to the
  connection. `enqueue(command, flush)` adds the command to a lock-free queue. Only if `flush` is
  true does it schedule a drain on the event loop. The drain runs the commands in order, calls
  `channel.flush()` once (or every 128 commands, `DEQUE_CHUNK_SIZE`), and reschedules itself if
  more arrived in the meantime. Commands from many calls on the same connection therefore end up
  in the same flush.
- **When `NettyClientStream` asks for a flush.** `CreateStreamCommand` is enqueued with
  `flush = !clientSendsOneMessage()`, which is false for unary and server-streaming methods.
  `ClientCallImpl` also skips the per-message flush for those two types (`if (!unaryRequest)
  stream.flush()`). Their only flush is the half-close. So the HEADERS frame and the message's DATA
  frame are written in the same drain.

### 3.2 The request HEADERS (code + lab)

`Utils.convertClientHeaders` removes any `content-type`, `te` or `user-agent` the application put
in the `Metadata`. `GrpcHttp2OutboundHeaders.clientRequestHeaders` then puts the reserved headers
first, in this order, followed by the metadata:

```text
Client OUTBOUND HEADERS: streamId=3 headers=GrpcHttp2OutboundHeaders[:authority: localhost:50552,
  :path: /lab.Echo/Upper, :method: POST, :scheme: http, content-type: application/grpc,
  te: trailers, user-agent: grpc-java-netty/1.86.0, x-request-id: r-1, grpc-accept-encoding: gzip]
  padding=0 endStream=false
```

- **`:path`** is `/` + the method's full name. `:method` is `POST`. `NettyClientStream` has code
  for a `GET` with the request in the query string, but `NettyChannelBuilder` hard-codes
  `useGetForSafeMethods = false`, so the Netty transport always sends `POST`.
- **`te: trailers`** tells proxies that the client accepts trailers. The server only logs a
  warning if it's missing (`NettyServerHandler.onHeadersRead`).
- **`x-request-id`** is the application's metadata, here added with `MetadataUtils`.
- **`grpc-accept-encoding: gzip`** lists the compressors the client could decompress. It was added
  by the call, not the application.

The HPACK bytes of the same headers, from another run without `x-request-id` (118 bytes, decoded
from the relay's capture):

```text
410f 6c6f63616c686f73743a3530353532       literal, add to table: :authority = localhost:50552
440f 2f6c61622e4563686f2f5570706572       literal, add to table: :path = /lab.Echo/Upper
83                                        static index 3: :method POST
86                                        static index 6: :scheme http
5f10 6170706c69636174696f6e2f67727063     literal, add to table: content-type = application/grpc
4002 7465 08 747261696c657273             literal, add to table: te = trailers
7a16 677270632d6a6176612d6e657474792f…    literal, add to table: user-agent = grpc-java-netty/1.86.0
4014 677270632d6163636570742d656e636f…    literal, add to table: grpc-accept-encoding = gzip
```

The values are plain ASCII (`6c6f63616c686f7374` is `localhost`). grpc-java builds its HPACK
encoder as `new DefaultHttp2HeadersEncoder(sensitivityDetector, false, 64, Integer.MAX_VALUE)`
(code). The last argument is Netty's Huffman threshold, and Netty only Huffman-codes strings at
least that long, so grpc-java never does. Every value is added to the connection's dynamic table,
which pays off on the next call (§4).

### 3.3 The message (code + lab)

`MessageFramer` writes each message as a 5-byte prefix plus the bytes the marshaller produced:

```text
Client OUTBOUND DATA: streamId=3 padding=0 endStream=true length=10 bytes=000000000568656c6c6f
                                                                          └┘└──────┘└────────┘
                                                                           │   │        │
                                                                           │   │        └ "hello"
                                                                           │   └ length 5
                                                                           └ 0: not compressed
```

- **Byte 0** is the compressed flag (`UNCOMPRESSED = 0`, `COMPRESSED = 1`). Bytes 1–4 are the
  length, big-endian: `00000005`.
- **The message isn't split by the framer.** It's copied into a buffer sized for prefix plus
  message, of at most 1 MiB (`NettyWritableBufferAllocator.MAX_BUFFER`), and handed to the
  transport as one `writeData`. Cutting it into HTTP/2 frames happens later, in Netty (§5).
- **`endStream=true`** on the same DATA frame: for a unary call, the half-close is folded into the
  message's frame. There's no separate empty frame.
- **Size limits.** `maxOutboundMessageSize` refuses a message that's too big before it's written
  (`RESOURCE_EXHAUSTED`). On the receiving side, `MessageDeframer` reads the prefix and refuses a
  length above `maxInboundMessageSize`, 4 MiB by default (`GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE`).

### 3.4 What went over the socket (lab)

```text
relay (ms since start)
 1658.5 client→server  164 B  HEADERS(s3, END_HEADERS, 136 B), DATA(s3, END_STREAM, 10 B)
 1672.0 server→client   17 B  PING
 1673.0 client→server   17 B  PING ack
 1693.4 server→client  122 B  HEADERS(s3, END_HEADERS, 70 B), DATA(s3, 10 B), HEADERS(s3, END_STREAM, 15 B)
```

- **The client's HEADERS and DATA arrived in one read** (164 bytes): the single flush of §3.1.
- **The server's whole response, headers, message and trailers, also came in one read.**
  - `ServerCallImpl` writes the headers with `flush = !serverSendsOneMessage()`, so for a unary
    method without a flush.
  - The message is flushed, and the trailers are enqueued with `flush = true`
    (`NettyServerStream.Sink.writeTrailers`).
  - All three were in the queue by the time the event loop drained it.
- **The PING carries 1234.** That's `BDP_MEASUREMENT_PING` (`AbstractNettyHandler`). When a side
  receives DATA, it sends this ping and times the round trip, to measure the bandwidth-delay
  product. It raises its receive window (a new `INITIAL_WINDOW_SIZE` in SETTINGS, at most 8 MiB)
  if the measurement says the current one is too small. It's on by default
  (`NettyChannelBuilder.flowControlWindow` turns it off), and it's why every exchange here has a
  PING pair.

### 3.5 The server side (code + lab)

`NettyServerHandler.onHeadersRead` checks the request before any service code runs. Each failure
becomes a plain HTTP error response:

| Check | Failure |
|---|---|
| `:path` present and starts with `/` | 404, `UNIMPLEMENTED` |
| `content-type` present and `application/grpc…` | 415, `INTERNAL` |
| `:method` is `POST` | 405, `INTERNAL` |
| header list size | 431, `RESOURCE_EXHAUSTED` |
| `te: trailers` | only a warning, once |

It then creates a `NettyServerStream` and hands it to the server with the method name (`:path`
without the `/`) and the metadata.

The response is built the same way as the request:
- `GrpcHttp2OutboundHeaders.serverResponseHeaders` puts `:status 200` and `content-type` first;
- `serverResponseTrailers` adds no reserved headers, only `grpc-status`, optional `grpc-message`
  and the trailing metadata.

```text
Server OUTBOUND HEADERS: streamId=3 headers=[:status: 200, content-type: application/grpc,
  grpc-encoding: identity, grpc-accept-encoding: gzip] endStream=false
Server OUTBOUND DATA: streamId=3 endStream=false length=10 bytes=000000000548454c4c4f   ← "HELLO"
Server OUTBOUND HEADERS: streamId=3 headers=[grpc-status: 0] endStream=true
```

## 4. The second call on the connection: HPACK's dynamic table (lab)

Two unary calls, one after the other, on the same channel:

```text
relay
 1885.5 client→server  147 B  HEADERS(s3, 118 B), DATA(s3, END_STREAM, 11 B)
 1949.7 server→client  123 B  HEADERS(s3, 70 B), DATA(s3, 11 B), HEADERS(s3, END_STREAM, 15 B)
 1973.0 client→server   37 B  HEADERS(s5, 8 B), DATA(s5, END_STREAM, 11 B)
 1976.7 server→client   43 B  HEADERS(s5, 4 B), DATA(s5, 11 B), HEADERS(s5, END_STREAM, 1 B)
```

The second call is stream 5, and its header blocks are tiny:

```text
client→server, stream 5 (8 bytes):   c3 c2 83 86 c1 c0 bf be
server→client, stream 5 (4 bytes):   88 c1 c0 bf
trailers, stream 5 (1 byte):         be
```

- **Every byte is a reference** (top bit set) to one table entry.
  - Indexes 1–61 are HPACK's static table: `83` is `:method POST`, `86` is `:scheme http`, `88`
    is `:status 200`.
  - 62 and up are the dynamic table, newest entry first. The first call's literals put
    `:authority`, `:path`, `content-type`, `te`, `user-agent` and `grpc-accept-encoding` there.
- **The table lasts for the connection, one per direction.** A new connection starts empty again.
  It's also why a capture that misses the start of a connection can't decode later headers
  ([grpc-in-kubernetes.md](grpc-in-kubernetes.md) §1.6).
- **`:path` was reused here because the method was the same.** A call to another method sends a
  new literal `:path`. The rest stays indexed.

## 5. A large message: one write, three frames (lab + code)

A 40 000-byte request to `Upper`, so 40 005 bytes with the prefix:

```text
frame logger
Client OUTBOUND DATA: streamId=3 endStream=true length=40005 bytes=0000009c40626262…
                                                                   └────────┘
                                                                        └ flag 0, length 40000
relay
 1374.5 client→server  16332 B  HEADERS(s3, 118 B)  + 16 205 B of an unfinished frame
 1374.7 client→server  23827 B  DATA(s3, 16384 B), DATA(s3, 16384 B), DATA(s3, END_STREAM, 7237 B)
```

- **The logger saw one `writeData` of 40 005 bytes.** `DefaultHttp2FrameWriter.writeData` cut it
  into frames of at most `maxFrameSize`, the peer's `SETTINGS_MAX_FRAME_SIZE`. Neither side sent
  one, so it's HTTP/2's default of 16 384: 16 384 + 16 384 + 7 237 = 40 005. Only the last frame
  has END_STREAM.
- **The 5-byte prefix is only at the start of the first frame.** The receiver's `MessageDeframer`
  reads the prefix, then collects 40 000 bytes from however many DATA frames they arrive in.
  Message boundaries and frame boundaries are unrelated.
- **A read doesn't end at a frame boundary.** The first read ended 16 205 bytes into the first
  DATA frame. TCP delivers a byte stream, and the receiver's frame reader reassembles frames from
  it.

## 6. Client streaming: a frame per message, then an empty END_STREAM (lab)

Three messages (`one`, `two`, `three`) 50 ms apart, then `onCompleted()`, on a channel that was
already connected:

```text
frame logger
Client OUTBOUND DATA: streamId=3 endStream=false length=8  bytes=00000000036f6e65            "one"
Client OUTBOUND DATA: streamId=3 endStream=false length=8  bytes=000000000374776f            "two"
Client OUTBOUND DATA: streamId=3 endStream=false length=10 bytes=00000000057468726565        "three"
Client OUTBOUND DATA: streamId=3 endStream=true  length=0  bytes=                            half-close
relay
 1898.1 client→server  145 B  HEADERS(s3, 119 B), DATA(s3, 8 B)
 1938.0 client→server   17 B  DATA(s3, 8 B)
 1988.0 client→server   19 B  DATA(s3, 10 B)
 2042.0 client→server    9 B  DATA(s3, END_STREAM, 0 B)
```

- **For streaming requests every message is flushed** (`ClientCallImpl`: `if (!unaryRequest)
  stream.flush()`), so each `onNext` is its own frame and its own write. The half-close comes later
  and has no message left to ride on, so it's an empty DATA frame with END_STREAM.
- **The first message shared a read with the HEADERS.** The stream creation was enqueued with a
  flush, but most likely the first message was also queued before that drain ran on the event
  loop (an inference from the timing, not traced).

**The same call on a channel that wasn't connected yet looked different:**

```text
 1737.0 client→server  163 B  HEADERS(s3, 119 B), DATA(s3, END_STREAM, 26 B)
Client OUTBOUND DATA: streamId=3 endStream=true length=26
  bytes=00000000036f6e65 000000000374776f 00000000057468726565
```

All three messages and the half-close went out as one DATA frame, after the 150 ms of sleeps.
While the connection was being set up, the call waited in grpc-java's delayed transport, which
records the stream's operations and replays them once the transport is ready. By then the framer
held all three messages, so one write sent them together. That's three gRPC messages in one HTTP/2
frame: the prefixes keep them apart.

## 7. Two calls at once: how frames interleave (lab + code)

Two `Download` calls started together, each asking for 2 messages of 40 000 bytes (stream 3 all
`x`, stream 5 all `y`): 80 010 bytes per stream including the prefixes.

```text
relay
 1372.2 client→server    193 B  HEADERS(s3, 121 B), HEADERS(s5, 8 B), DATA(s3, END_STREAM, 14 B), DATA(s5, END_STREAM, 14 B)
 1417.3 server→client  32664 B  HEADERS(s3, 70 B), HEADERS(s5, 4 B), DATA(s5, 16384), DATA(s5, 16146)  + 24 B
 1417.4 server→client  32534 B  DATA(s3, 16384), DATA(s3, 16146), DATA(s5, 1)
 1419.2 server→client  49024 B  DATA(s3, 16384), DATA(s3, 8110), DATA(s5, 16384), DATA(s5, 8110)
 1422.1 server→client  40686 B  DATA(s3, 16384), DATA(s3, 3936), DATA(s5, 16384), DATA(s5, 3936), DATA(s3, 1)
 1428.0 server→client   5382 B  DATA(s5, 2665), HEADERS(s5, END_STREAM, 15 B), DATA(s3, 2665), HEADERS(s3, END_STREAM, 1 B)
```

The server's frame logger shows the `writeData` calls behind those frames, one per turn:

```text
stream 5: 32530   stream 3: 32530   stream 5: 1
stream 3: 24494   stream 5: 24494
stream 3: 20320   stream 5: 20320   stream 3: 1
stream 5:  2665   stream 3:  2665
```

- **Both requests went out in one write:** two HEADERS frames, then the two DATA frames. That's
  the queued-commands batching of §3.1, across two calls. The second HEADERS block is 8 bytes,
  thanks to the dynamic table (§4).
- **The server's DATA doesn't go straight to the socket.** `sendGrpcFrame` calls
  `encoder.writeData`, which queues the bytes per stream in Netty's
  `DefaultHttp2RemoteFlowController` (code). On flush the controller works out how many bytes may
  leave now: the connection's flow-control window, capped by `channel.bytesBeforeUnwritable()`,
  the free space in the socket's outbound buffer. grpc-java doesn't change Netty's write-buffer
  water marks, so that free space starts at 64 KiB.
- **That allowance is split by `UniformStreamByteDistributor`,** which grpc-java installs with
  `minAllocationChunk(16 * 1024)` (`MIN_ALLOCATED_CHUNK`). On each round it takes the streams with
  queued data in turn:
  - each gets `max(16 KiB, allowance / number of streams)`, or less if that's all the allowance
    has left;
  - a stream with data left goes back to the end of the queue.

  With two streams and about 64 KiB free, each turn was about 32 KiB (32 530 bytes). Later rounds
  were smaller (24 494, 20 320) because the buffer hadn't fully drained.
- **Turns are cut into frames,** of 16 384 bytes plus a remainder (§5). That's why each stream's
  frames come in pairs.
- **The one-byte frames.** When the allowance runs out partway through a round, the last stream's
  turn is whatever is left. Twice that was 1 byte, and Netty sent a 1-byte DATA frame rather than
  waiting.
- **Netty's distributor ignores priorities.** Its `updateDependencyTree` does nothing, so every
  stream gets the same share. A single stream can still use the whole connection when it's the
  only one with data.
- **Trailers wait for the data in front of them.** HEADERS frames aren't flow-controlled, but
  Netty's encoder queues a HEADERS frame that ends the stream behind that stream's pending DATA
  ("Trailing headers must go through flow control if there are other frames queued", code).
  Each stream's trailers went out right after its last DATA frame.

On the receiving side the client's `NettyClientHandler` routes each frame by stream id to that
call's `MessageDeframer`. The program printed the four messages in the order they completed:
`y, x, y, x`.

## 8. A failed call: trailers-only (lab + code)

`Fail` calls `onError(Status.NOT_FOUND…)` before sending any message or headers:

```text
Server OUTBOUND HEADERS: streamId=3 headers=[:status: 200, content-type: application/grpc,
  x-reason: no-such-order, grpc-status: 5, grpc-message: order 42 not found] endStream=true
relay
 1386.1 server→client  100 B  HEADERS(s3, END_HEADERS|END_STREAM, 91 B)
```

- **One HEADERS frame ends the stream.** `Utils.convertTrailers(trailers, headersSent)`: when no
  headers were sent yet, the trailers are built as response headers. So `:status 200` and
  `content-type` come first, then the trailing metadata, `grpc-status` and `grpc-message`.
- **An error is still `:status 200`.** The gRPC status lives in `grpc-status` (5 = `NOT_FOUND`).
  HTTP status codes other than 200 only appear for transport-level problems like the ones in the
  §3.5 table.
- **The client sees the trailing metadata,** including `x-reason`:
  `Metadata(content-type=application/grpc,x-reason=no-such-order)`.

## 9. The defaults involved (code)

| Setting | Default | Where |
|---|---|---|
| Initial flow-control window, per stream and connection | 1 MiB | `NettyChannelBuilder` / `NettyServerBuilder.DEFAULT_FLOW_CONTROL_WINDOW` |
| Automatic window tuning (BDP pings) | on, window up to 8 MiB | `AbstractNettyHandler.FlowControlPinger` |
| Max header list size | 8192 | `GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE` |
| Max inbound message | 4 MiB | `GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE` |
| Max frame size | 16 384 (HTTP/2 default, not changed) | Netty `Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE` |
| Interleaving turn | at least 16 KiB per stream | `AbstractNettyHandler.MIN_ALLOCATED_CHUNK` |
| Commands per flush, at most | 128 | `WriteQueue.DEQUE_CHUNK_SIZE` |
| Receive-window refill | when half is used | Netty `DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO` (0.5) |
| HPACK Huffman coding | never | `Integer.MAX_VALUE` threshold in `NettyClientHandler` |
| First client stream id | 3 | Netty `DefaultHttp2Connection` (1 is reserved for upgrade) |

## Related notes

- [grpc-in-kubernetes.md](grpc-in-kubernetes.md): the gRPC protocol on the wire, and where
  Kubernetes uses it (etcd, CRI, CSI).
- [http.md](http.md): HTTP/1.1 vs HTTP/2 multiplexing.
- [kubernetes-services.md](kubernetes-services.md): why one long-lived gRPC connection defeats
  connection-level load balancing.
