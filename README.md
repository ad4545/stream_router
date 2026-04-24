# Stream Router — Technical & Architectural Documentation

## Overview
The **Stream Router** is a high-performance, schema-blind gRPC streaming gateway — the **"Tap"** in the distributed continuous streaming architecture. If the `Stream Handler` is the pump pulling data from the well (Kafka), the Stream Router is the **central water tower**: it receives the continuous flow from the handler over the Pekko Cluster and exposes it to downstream microservices as an instantly connectable, zero-overhead HTTP/2 stream.

It provides a complexity-free gRPC endpoint for microservices to attach to live, low-latency data streams without knowing anything about internal data topologies, Kafka offsets, or consumer groups.

## Architectural Philosophy: The "Tap" Analogy
1. **Continuous Internal Flow**: Inside the router, data from the `Stream Handler` flows continuously, 24/7. When there are zero gRPC clients connected, the data flows through the internal hub and dissipates. This ensures upstream Kafka brokers are drained efficiently at a steady pace without backlog, protecting the overarching infrastructure.
2. **The "Tap" for Microservices**: Downstream microservices do not care about historical data, offsets, or consumer groups. They want instantaneous latency. By connecting via gRPC, they "open the tap" and immediately receive a continuous stream of the absolute latest data.
3. **No-Overhead Closing**: When a microservice scales down or drops the connection, it "closes the tap". Since the interior stream keeps flowing, there is zero impact on the rest of the cluster — no partition rebalancing, no coordination required.
4. **Lowest Latency Design**: By dropping historic data on connection and pushing current events directly into an HTTP/2 stream, latency is kept to sub-millisecond ranges once a payload hits the router.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          STREAM ROUTER NODE                                     │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │  RouterActor                                                              │  │
│  │                                                                           │  │
│  │  Registered under RouterKey in Receptionist                               │  │
│  │  Single ingestion gateway for all Stream Handler producers                │  │
│  │                                                                           │  │
│  │   StreamElementIn(RawMessage)                                             │  │
│  │          │                                                                │  │
│  │          │ inspects topic name                                            │  │
│  │          │ spawns TopicActor on first sight                               │  │
│  │          │ replies StreamAck (backpressure)                               │  │
│  │          ▼                                                                │  │
│  │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐               │  │
│  │   │  TopicActor  │    │  TopicActor  │    │  TopicActor  │               │  │
│  │   │  amr.001     │    │  amr.002     │    │  amr.003     │               │  │
│  │   │              │    │              │    │              │               │  │
│  │   │  MergeHub    │    │  MergeHub    │    │  MergeHub    │               │  │
│  │   │     ▼        │    │     ▼        │    │     ▼        │               │  │
│  │   │  BroadcastHub│    │  BroadcastHub│    │  BroadcastHub│               │  │
│  │   └──────┬───────┘    └──────┬───────┘    └──────┬───────┘               │  │
│  └──────────┼───────────────────┼───────────────────┼───────────────────────┘  │
│             │                   │                   │                           │
└─────────────┼───────────────────┼───────────────────┼───────────────────────────┘
              │                   │                   │
    ┌─────────▼──────┐   ┌────────▼───────┐   ┌──────▼──────────┐
    │  GrpcStream    │   │  GrpcStream    │   │  GrpcStream     │
    │  Service       │   │  Service       │   │  Service        │
    │  (subscriber)  │   │  (subscriber)  │   │  (subscriber)   │
    └────────────────┘   └────────────────┘   └─────────────────┘

  ┌────────────────────┐        ┌──────────────────────────────┐
  │  Stream Handler    │        │  Pekko Receptionist          │
  │  (Remote Node)     │        │                              │
  │                    │        │  RouterKey  ──▶ RouterActor  │
  │  Pushes            │        │  TopicHub-amr.001 ──▶ ...    │
  │  StreamElementIn   │──────▶ │  TopicHub-amr.002 ──▶ ...    │
  │  over TCP (CBOR)   │        │                              │
  └────────────────────┘        └──────────────────────────────┘
```

---

## Component Architecture & Low-Level Mechanics

### 1. Startup & Registration (`RouterNode.scala` / `NodeA.scala`)

`RouterNode` is the entry point for the JVM process. On startup it:
1. Overrides `pekko.remote.artery.canonical.port` at runtime so a single JAR can run on any node without changing `application.conf`.
2. Creates a `ClusterSystem` under the name `ClusterSystem`, which joins the seed nodes listed in config.
3. Spawns a single `RouterActor` and immediately registers it with the Pekko `Receptionist` under the shared `RouterKey`. This broadcast makes the router discoverable to every producer in the cluster regardless of its IP address.
4. Constructs a `GrpcStreamService` and binds it on `0.0.0.0:8080` via HTTP/2.

```
  RouterNode.main()
       │
       ├─▶ ActorSystem("ClusterSystem") joins cluster
       │
       ├─▶ ctx.spawn(RouterActor(), "routerActor")
       │       └─▶ Receptionist.Register(RouterKey, routerActor)
       │              └─▶ Stream Handler finds this key and starts pushing
       │
       └─▶ Http().newServerAt("0.0.0.0", 8080).bind(grpcHandler)
               └─▶ GrpcStreamService serves SubscribeToTopic / ListTopics
```

**Cluster configuration** (`application.conf`):

| Key | Value | Purpose |
|---|---|---|
| `pekko.remote.artery.canonical.hostname` | `10.0.0.10` | Intra-cluster identity |
| `pekko.remote.artery.canonical.port` | `25520` | Overridden per-node at startup |
| `pekko.actor.provider` | `cluster` | Enables cluster formation |
| `pekko.actor.serialization-bindings` | `CborSerializable → jackson-cbor` | CBOR-only, Java serialization disabled |
| `pekko.remote.artery.advanced.maximum-frame-size` | `256000b` | ~250 KB max inter-node message |
| `pekko.cluster.downing-provider-class` | `SplitBrainResolverProvider` | `keep-majority` strategy, stable after 20s |
| `grpc.port` | `8080` | gRPC server listen port |
| `stream-router.topic-actor-buffer-size` | `1024` | `BroadcastHub` ring-buffer depth per topic |
| `stream-router.grpc-topic-lookup-timeout` | `5s` | Receptionist ask timeout |
| `stream-router.grpc-topic-retry-backoff-max` | `4` | Max exponential backoff retries |

---

### 2. The Ingestion Gateway (`RouterActor.scala`)

`RouterActor` is the **single point of entry** into the Router node from the cluster.

- **Receptionist Discovery**: Registers itself under `RouterKey`. Upstream `Stream Handler` nodes resolve this key dynamically and never need to know the router's IP.
- **Dynamic Laziness**: The router does not need to know topics in advance. When it receives a `StreamElementIn[RawMessage]`, it checks its internal mutable `Map[String, ActorRef[TopicHubCommand]]`. If no actor exists for that topic, it lazily spawns one.
- **Lifecycle Watching**: After spawning a `TopicActor`, it calls `ctx.watch(child)`. If the child terminates (crash or explicit stop), the `Terminated` signal removes the entry from the registry, so the next incoming message transparently re-creates the actor.
- **Global Knowledge**: Each newly discovered topic name is added to a thread-safe `ConcurrentHashMap.KeySet` (`RouterActor.knownTopics`). The gRPC layer reads this set lock-free when serving `ListTopics` requests.
- **Backpressure Protocol**: For every `StreamElementIn`, the router replies with `StreamAck` after forwarding. This is the demand signal that prevents the network from being flooded faster than the actor can process.
- **Diagnostic Logging**: Every 500 forwarded messages, the router logs a throughput summary (`[RouterActor] Forwarded N messages across M topic(s).`).

#### Message Protocol (`StreamToActorMessaging.scala`)

All cluster-crossing messages implement `CborSerializable` and are serialized with Jackson CBOR, keeping the wire format compact and cross-language compatible.

| Message | Direction | Purpose |
|---|---|---|
| `StreamInit(replyTo)` | Handler → Router | Stream handshake — Router replies `StreamAck` |
| `StreamElementIn(element, replyTo)` | Handler → Router | Carries one `RawMessage` payload |
| `StreamAck` | Router → Handler | Demand signal — Handler sends next element |
| `StreamFailed(cause)` | Router → Handler | Signals a processing error |
| `StreamCompleted` | Handler → Router | Clean upstream termination |

```
  Stream Handler                         RouterActor
       │                                      │
       │──── StreamInit(replyTo) ────────────▶│
       │◀─── StreamAck ───────────────────────│
       │                                      │
       │──── StreamElementIn(raw, replyTo) ──▶│  ─▶  TopicActor.Publish(raw)
       │◀─── StreamAck ───────────────────────│
       │                                      │
       │──── StreamElementIn(raw, replyTo) ──▶│  ─▶  TopicActor.Publish(raw)
       │◀─── StreamAck ───────────────────────│
       │  ...continuous for lifetime of node  │
```

---

### 3. The Fan-Out Engine (`TopicActor.scala`)

Each Kafka topic first seen by the `RouterActor` gets its own dedicated `TopicActor`. Isolation means a misbehaving subscriber on `amr.001` cannot starve consumers of `amr.002`.

#### The Hub Pipeline

On actor initialization, `TopicActor` wires and materializes a permanent internal stream:

```
  producers ──▶ MergeHub ──▶ BroadcastHub ──▶ N gRPC subscribers
```

Crucially, this pipeline is materialized immediately inside `Behaviors.setup`. The "pipes" are open and pulling even before any gRPC client has subscribed. This preserves the healthy steady-state of the upstream Kafka consumers — the flow never backs up.

```scala
val (mergeSink, hubSource) =
  MergeHub.source[RawMessage](perProducerBufferSize = 16)
    .toMat(BroadcastHub.sink[RawMessage](bufferSize = bufferSize))(Keep.both)
    .run()
```

#### MergeHub (Fan-In)

Provides a dynamic "funnel" that accepts concurrent `Source.single(msg).runWith(mergeSink)` calls from the `RouterActor`. Each `Publish` command creates an independent stream materialization — thread-safe by design, no locking required.

#### BroadcastHub (Fan-Out)

Acts as a dynamic "splitter". It takes the unified stream and provides a reusable `hubSource` that is shared among all gRPC subscribers.

- Every time a new gRPC client subscribes, they materialize this `Source`, receiving their own **independent cursor** into the rolling ring-buffer.
- **Isolation**: A slow network client that cannot consume fast enough has its buffer dropped locally (ring-buffer overflow). The global ingestion flow is never blocked.
- **Buffer depth** is controlled by `stream-router.topic-actor-buffer-size` (default: `1024` elements).

#### TopicActor Commands

| Command | Sender | Action |
|---|---|---|
| `Publish(msg: RawMessage)` | `RouterActor` | Pushes one message into the `MergeHub` |
| `Subscribe(replyTo)` | `GrpcStreamService` | Returns the shared `hubSource` reference |

---

### 4. gRPC Edge Interface (`GrpcStreamService.scala`)

The entry point for edge microservices, implementing the `StreamRouter` gRPC service definition.

#### `SubscribeToTopic` (server-streaming RPC)

```
  Microservice                GrpcStreamService             Receptionist / TopicActor
       │                             │                               │
       │── SubscribeToTopic("amr.001")▶│                              │
       │                             │── Find(topicHubKey("amr.001"))─▶│
       │                             │◀─ Listing([ref])  ─────────────│
       │                             │── Subscribe(replyTo) ──────────▶│ TopicActor
       │                             │◀─ hubSource ───────────────────│
       │◀── RawDataChunk (stream) ───│   map RawMessage → RawDataChunk│
       │◀── RawDataChunk (stream) ───│                               │
       │  ...continuous until close  │                               │
```

**Resilient Lookups**: If the `TopicActor` has not yet been spawned (the producer hasn't sent a message for that topic yet), the service retries with **exponential backoff** before failing:

| Attempt | Delay |
|---|---|
| 0 | immediate |
| 1 | 250 ms |
| 2 | 500 ms |
| 3 | 1 000 ms |
| 4 | 2 000 ms |
| > 4 | `NOT_FOUND` gRPC error |

The maximum retries and base timeout are configurable via `stream-router.grpc-topic-retry-backoff-max` and `stream-router.grpc-topic-lookup-timeout`.

**Data Mapping**: Upon successful attachment to the `BroadcastHub`, the service maps the reactive `RawMessage` stream into `RawDataChunk` Protobuf objects on the fly over HTTP/2:

```scala
hubSource.map { raw =>
  RawDataChunk(
    topic   = raw.topic,
    key     = raw.key,
    payload = ByteString.copyFrom(raw.value)
  )
}
```

#### `ListTopics` (unary RPC)

Reads `RouterActor.knownTopics` — a `ConcurrentHashMap.KeySet` — directly from any thread. No actor messaging is required because the set is written only by `RouterActor` (a single writer) and is safe for concurrent readers. Returns a sorted `TopicList`.

---

### 5. Schema-Blind Routing (`FlowMessage.scala`, `CborSerializable.scala`)

The router does not deserialize application-level data (e.g., Protobuf domains like `Scan`, `Telemetry`).

- **Opaque Protocol**: The internal `RawMessage` carries an unparsed `Array[Byte]` as the `value` field.
- **CBOR Envelope**: Only the *cluster messaging envelope* (`StreamElementIn`, `StreamAck`, etc.) is serialized with Jackson CBOR. The payload bytes are opaque throughout.
- **Zero Coupling**: Microservices receive these raw bytes and apply their own `.proto` definitions. Adding a new domain type requires zero changes to the router.

```
  Array[Byte] (Kafka payload)
       │
       │  Stream Handler:  wraps in RawMessage, CBOR-serializes envelope
       ▼
  RawMessage { topic, key, value: Array[Byte] }
       │
       │  RouterActor:  forwards opaque RawMessage → TopicActor
       ▼
  TopicActor:  pushes into MergeHub as-is
       │
       │  GrpcStreamService:  copies bytes into RawDataChunk.payload
       ▼
  RawDataChunk { topic, key, payload: ByteString }
       │
       │  Microservice:  deserializes payload with its own .proto schema
       ▼
  Domain-specific type (Scan, Telemetry, …)
```

---

## Data Pipeline Flow

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                                                                                │
│  [1] Stream Handler (Remote Node)                                              │
│      Pushes StreamElementIn(RawMessage) over Pekko Cluster TCP (CBOR)         │
│          │                                                                     │
│          │  CBOR-deserialized, cluster message dispatch                        │
│          ▼                                                                     │
│  [2] RouterActor                                                               │
│      Receives StreamElementIn. Inspects topic field.                           │
│      On first sight: spawns TopicActor, registers topicHubKey in Receptionist. │
│      Forwards Publish(RawMessage) to TopicActor.                               │
│      Replies StreamAck to handler (backpressure demand signal).                │
│          │                                                                     │
│          │  actor message (in-process)                                         │
│          ▼                                                                     │
│  [3] TopicActor                                                                │
│      Receives Publish(raw). Runs Source.single(raw).runWith(mergeSink).        │
│      Message enters the MergeHub and is pulled forward into the BroadcastHub. │
│          │                                                                     │
│          │  reactive stream (in-process)                                       │
│          ▼                                                                     │
│  [4] BroadcastHub                                                              │
│      Fans out RawMessage to all currently subscribed hubSource materializations│
│      (one per active gRPC subscriber). Slow clients drop from their own buffer.│
│          │                                                                     │
│          │  hubSource (per-subscriber reactive stream)                         │
│          ▼                                                                     │
│  [5] GrpcStreamService                                                         │
│      Maps RawMessage → RawDataChunk (copies opaque bytes into ByteString).     │
│      Emits continuously over HTTP/2 server-streaming response.                 │
│          │                                                                     │
│          │  HTTP/2 frame (gRPC streaming)                                      │
│          ▼                                                                     │
│  [6] Microservice                                                              │
│      Receives RawDataChunk. Deserializes payload with its own .proto schema.   │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## gRPC Contract (`stream_router.proto`)

```protobuf
syntax = "proto3";
package com.example.grpc;

// Client requests a stream filtered by topic name
message TopicStreamRequest {
  string topic = 1;   // e.g. "amr.001", "sensor-scan"
}

// Each chunk is a raw Kafka record — client deserializes with their own .proto
message RawDataChunk {
  string topic   = 1;
  string key     = 2;
  bytes  payload = 3;   // Opaque serialized bytes
}

message ListTopicsRequest {}

message TopicList {
  repeated string topics = 1;
}

service StreamRouter {
  // Server-streaming: subscribe to a specific topic's live data
  rpc SubscribeToTopic (TopicStreamRequest) returns (stream RawDataChunk);

  // Unary: list currently active topics (topics for which data has been received)
  rpc ListTopics (ListTopicsRequest) returns (TopicList);
}
```

---

## Source File Map

| File | Role |
|---|---|
| `NodeA.scala` (`RouterNode`) | JVM entry point — wires cluster, `RouterActor`, and gRPC server |
| `RouterActor.scala` | Single cluster ingestion gateway; lazy `TopicActor` spawner |
| `TopicActor.scala` | Per-topic `MergeHub → BroadcastHub` fan-out engine |
| `GrpcStreamService.scala` | gRPC service impl — resilient topic lookup, stream mapping |
| `FlowMessage.scala` | Domain model: `RawMessage`, `RouterKey`, `TopicHubCommand`, `topicHubKey` |
| `StreamToActor.scala` | Cluster messaging protocol: `StreamInit`, `StreamAck`, `StreamElementIn`, … |
| `CborSerializable.scala` | Marker trait triggering Jackson CBOR serialization |
| `stream_router.proto` | Public gRPC service contract |
| `application.conf` | Cluster, serialization, gRPC, and router tuning configuration |

---

## Operations & Extensibility

### Starting the Node
```bash
sbt clean run
# Select com.example.RouterNode
```

### Connecting a Microservice (gRPC)
```bash
# List active topics
grpcurl -plaintext localhost:8080 com.example.grpc.StreamRouter/ListTopics

# Subscribe to a live topic stream
grpcurl -plaintext -d '{"topic": "amr.001"}' \
  localhost:8080 com.example.grpc.StreamRouter/SubscribeToTopic
```

### Extending for New Protocols
The router is source-agnostic. Any future producer (MQTT bridge, WebSocket relay, etc.) need only:
1. Connect to the same `ClusterSystem` seed nodes.
2. Look up `RouterKey` from the `Receptionist`.
3. Send `StreamElementIn(RawMessage(...))` with a valid `replyTo` reference.

The gRPC edge interface, `TopicActor` hubs, and all downstream microservices remain entirely unchanged.

### Tuning the BroadcastHub Buffer
The `topic-actor-buffer-size` (default: `1024`) controls how many elements each `BroadcastHub` retains for slow subscribers. Increase this on high-throughput topics where subscriber latency variance is expected. Each element is an opaque `Array[Byte]` reference, so memory overhead is proportional to the number of live topics × buffer depth.

### Logging & Diagnostics

| Log line | Meaning |
|---|---|
| `[RouterNode] RouterActor spawned and registered under RouterKey` | Cluster is up, producers can now connect |
| `[RouterNode] gRPC Server bound to 0.0.0.0/0.0.0.0:8080` | Edge interface is accepting client connections |
| `[RouterActor] First message for topic 'X' — spawning TopicActor` | New data topic discovered; fan-out hub materialized |
| `[RouterActor] Forwarded N messages across M topic(s)` | Throughput heartbeat every 500 messages |
| `[TopicActor-X] New subscriber registered` | A gRPC client opened the tap on topic X |
| `[RouterActor] TopicActor for 'X' terminated — removing from registry` | TopicActor crash; will be re-created on next inbound message |

> **Integration Detail**: Any microservice implementation connects directly to `localhost:8080` (or the cluster-facing IP). See the `stream_router.proto` contract above for the public API, and the **Stream Handler** documentation to understand how data arrives at this node.
