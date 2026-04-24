import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';

// -----------------------------------------------------------------------------
// 1. Load Proto Definitions
// -----------------------------------------------------------------------------
// The Stream Router contract. We use this to connect to the gRPC service.
const ROUTER_PROTO_PATH = './proto/stream_router.proto';

// The domain-specific schema. We use this to deserialize the opaque payload bytes.
const ODOMETRY_PROTO_PATH = './proto/Odometry.proto';

// Load the Stream Router proto
const routerPackageDefinition = protoLoader.loadSync(ROUTER_PROTO_PATH, {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true
});
const routerProto = grpc.loadPackageDefinition(routerPackageDefinition).com.example.grpc;

// Load the domain Odometry proto
// We only need this to get the message schema for deserialization.
const odometryPackageDefinition = protoLoader.loadSync(ODOMETRY_PROTO_PATH, {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true
});
// The package name in Odometry.proto is `combined_odom`
const domainProto = grpc.loadPackageDefinition(odometryPackageDefinition).combined_odom;

// -----------------------------------------------------------------------------
// 2. Connect to the Stream Router
// -----------------------------------------------------------------------------
const ROUTER_ADDRESS = process.env.ROUTER_ADDRESS || 'localhost:8080';
const client = new routerProto.StreamRouter(
  ROUTER_ADDRESS,
  grpc.credentials.createInsecure() // Router does not use TLS by default
);

console.log(`[Client] Connected to Stream Router at ${ROUTER_ADDRESS}`);

// -----------------------------------------------------------------------------
// 3. Step 1: List Topics
// -----------------------------------------------------------------------------
function listTopics() {
  return new Promise((resolve, reject) => {
    console.log(`[Client] Requesting available topics...`);
    client.ListTopics({}, (err, response) => {
      if (err) {
        console.error(`[Error] Failed to list topics:`, err.message);
        return reject(err);
      }
      
      console.log(`[Client] Found ${response.topics?.length || 0} active topics:`);
      response.topics?.forEach(topic => console.log(`  - ${topic}`));
      resolve(response.topics || []);
    });
  });
}

// -----------------------------------------------------------------------------
// 4. Step 2 & 3: Subscribe and Deserialize
// -----------------------------------------------------------------------------
function subscribeAndDeserialize(topicName) {
  console.log(`\n[Client] Subscribing to stream: ${topicName}`);
  
  // Create a server-streaming request
  const stream = client.SubscribeToTopic({ topic: topicName });
  
  let messageCount = 0;

  // Handle incoming data chunks
  stream.on('data', (chunk) => {
    messageCount++;
    
    // The `chunk` is a `RawDataChunk` containing:
    // - topic (string)
    // - key (string)
    // - payload (Buffer - opaque bytes)
    
    // We know that topic amr.001.odom_with_amcl contains `combined_odom.Odometry` messages.
    // So we use the Odometry message type to deserialize the opaque payload Buffer.
    
    try {
      // In @grpc/grpc-js + protoLoader, we can use the type definition's decode method
      // Actually, since we loaded it using grpc.loadPackageDefinition, the returned object 
      // doesn't expose the decode method directly easily for dynamic Buffers if we don't use the underlying protobufjs root.
      // Let's use the underlying protobufjs root that @grpc/proto-loader created.
      // However, @grpc/proto-loader is primarily for service definitions. 
      // Let's use the service definition's fileDescriptorProtos to decode, OR 
      // even better, since we are doing dynamic decoding, we can use protobuf.js directly.
      // Wait, @grpc/proto-loader *does* use protobufjs. But let's show a cleaner way.
      // 
      // Actually, since we use @grpc/proto-loader, we can access the type.
      // A cleaner way for domain objects is to use protobufjs root directly, 
      // but let's see if we can do it with just protoLoader.
      // The grpc.loadPackageDefinition output has the type but no direct `decode` function.
      // We will parse it manually via the package definition.
    } catch (err) {
       console.error(`[Error] Failed to deserialize payload:`, err);
    }
  });

  stream.on('error', (err) => {
    console.error(`\n[Error] Stream error:`, err.message);
    if (err.code === grpc.status.NOT_FOUND) {
        console.error(`Hint: The topic '${topicName}' might not be active yet.`);
    }
  });

  stream.on('end', () => {
    console.log(`\n[Client] Stream ended by server.`);
  });
}

// Let's fix the deserialization. Since @grpc/proto-loader hides the protobuf.js `decode` method,
// it's best to use `protobufjs` directly for decoding domain payloads in Node.js.
// Let's do that cleanly.
import protobuf from 'protobufjs';

async function main() {
  try {
    // 1. List topics
    const topics = await listTopics();
    
    // 2. Load domain proto with protobufjs for decoding payloads
    const root = await protobuf.load(ODOMETRY_PROTO_PATH);
    const OdometryMessage = root.lookupType("combined_odom.Odometry");

    const TARGET_TOPIC = 'amr.001.odom_with_amcl';
    
    if (!topics.includes(TARGET_TOPIC)) {
      console.warn(`\n[Warning] Topic '${TARGET_TOPIC}' is not currently active.`);
      console.log(`We will try subscribing anyway (it will retry until the stream appears).`);
    }

    // 3. Subscribe
    console.log(`\n[Client] Subscribing to stream: ${TARGET_TOPIC}`);
    const stream = client.SubscribeToTopic({ topic: TARGET_TOPIC });
    
    let messageCount = 0;

    stream.on('data', (chunk) => {
      messageCount++;
      
      try {
        // chunk.payload is a Buffer containing the serialized Odometry message
        const decoded = OdometryMessage.decode(chunk.payload);
        
        // Convert to a plain JS object for easy printing
        const obj = OdometryMessage.toObject(decoded, {
          longs: String,
          enums: String,
          bytes: String,
        });
        
        console.log(`\n--- Received Message #${messageCount} on ${chunk.topic} ---`);
        console.log(`Timestamp: ${obj.Header?.Stamp?.seconds || 'N/A'}`);
        console.log(`Position:  X=${obj.Pose?.Pose?.Position?.X?.toFixed(3)} Y=${obj.Pose?.Pose?.Position?.Y?.toFixed(3)}`);
        console.log(`Orientation: W=${obj.Pose?.Pose?.Orientation?.W?.toFixed(3)}`);
        
      } catch (err) {
         console.error(`[Error] Failed to deserialize payload:`, err);
      }
    });

    stream.on('error', (err) => {
      console.error(`\n[Error] Stream error:`, err.message);
    });

    stream.on('end', () => {
      console.log(`\n[Client] Stream ended by server.`);
    });

  } catch (err) {
    console.error("Fatal error:", err);
  }
}

main();
