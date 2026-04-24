# Sample gRPC Client

This is a reference gRPC client demonstrating the **standard integration pattern** that any microservice should follow to consume live data from the Stream Router. 

Although this example is written in Node.js/JavaScript, the process is **language-agnostic** and works exactly the same in Python, Go, Java, Rust, C++, etc.

## The 4-Step Integration Pattern

No matter what language you are using, consuming the stream requires these four steps:

1. **Get the Protos**: Copy the router contract (`stream_router.proto`) and your domain specific payload schema (e.g., `Odometry.proto`) into your project.
2. **Connect**: Connect to the Stream Router's gRPC endpoint (default: `localhost:8080`, no TLS).
3. **Subscribe**: Call `SubscribeToTopic` with the target topic name (e.g., `amr.001.odom_with_amcl`). The router will stream `RawDataChunk` messages back to you.
4. **Deserialize**: The `RawDataChunk` contains an opaque `payload` byte array. Use your domain-specific `.proto` (like `Odometry.proto`) to decode these bytes into a usable object.

---

## Running This Sample

### Setup
1. Ensure Node.js 20+ is installed.
2. Install dependencies:
   ```bash
   npm install
   ```

### 1. List Available Topics
To see what topics the router is currently handling:
```bash
npm run list-topics
```
*(Runs `src/list-topics.mjs`)*

### 2. Full Workflow (Subscribe & Deserialize)
To connect, subscribe to `amr.001.odom_with_amcl`, and decode the Odometry payload:
```bash
npm run client
```
*(Runs `src/client.mjs`)*

### 3. Raw Subscription (No Deserialization)
To subscribe to *any* topic and view the raw hex payload (useful for debugging):
```bash
npm run subscribe <topic_name>
# Example: npm run subscribe amr.001.odom_with_amcl
```
*(Runs `src/subscribe-topic.mjs`)*

---

## Adding New Domain Protos

If you want to consume a topic that uses a different protobuf schema (e.g., `LidarScan` instead of `Odometry`), follow these steps:

1. Drop the new `.proto` file into the `proto/` directory.
2. In your code, load the new `.proto` file.
3. Look up the specific message type (e.g., `sensor_msgs.LaserScan`).
4. When you receive a `RawDataChunk` on that topic, use the new message type to decode `chunk.payload`.

The Stream Router itself **never** needs to be updated when you add new data types. It only routes raw bytes.

---

## Porting to Other Languages

This exact same pattern applies to other languages:

*   **Python**: Use `grpcio` and `grpcio-tools` to generate Python classes from the `.proto` files. Call `stub.SubscribeToTopic(TopicStreamRequest(topic="amr..."))`. For deserialization, use `Odometry.FromString(chunk.payload)`.
*   **Go**: Use `protoc` with the `go` and `go-grpc` plugins. Connect using `grpc.Dial()`. Consume the stream in a `for` loop receiving `RawDataChunk`s. Use `proto.Unmarshal(chunk.Payload, &odometry)` to decode.
*   **Rust**: Use `tonic` and `prost`. Connect and subscribe, receiving a `Stream` of `RawDataChunk`. Decode the bytes using `Odometry::decode(chunk.payload)`.
