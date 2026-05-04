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
// 4. Subscribe and Deserialize function
// -----------------------------------------------------------------------------
function subscribeToTopic(client, topicName, OdometryMessage) {
  console.log(`\n[Client] Subscribing to stream: ${topicName}`);
  const stream = client.SubscribeToTopic({ topic: topicName });
  
  let messageCount = 0;

  stream.on('data', (chunk) => {
    messageCount++;
    try {
      // chunk.payload is a Buffer containing the serialized Odometry message
      const decoded = OdometryMessage.decode(chunk.payload);
      const obj = OdometryMessage.toObject(decoded, {
        longs: String,
        enums: String,
        bytes: String,
        defaults: true, // Forces protobufjs to include fields that are 0
      });
      
      console.log(`\n--- Received Message #${messageCount} on ${chunk.topic} ---`);
      
      // The properties match the .proto exactly (PascalCase). 
      // If a value is 0, protobuf3 omits it, but `defaults: true` fixes that.
      const stamp = obj.Header?.Stamp?.seconds || 'N/A';
      const x = obj.Pose?.Pose?.Position?.X ?? 0;
      const y = obj.Pose?.Pose?.Position?.Y ?? 0;
      const w = obj.Pose?.Pose?.Orientation?.W ?? 0;

      console.log(`Timestamp: ${stamp}`);
      console.log(`Position:  X=${x.toFixed(3)} Y=${y.toFixed(3)}`);
      console.log(`Orientation: W=${w.toFixed(3)}`);
      
    } catch (err) {
       console.error(`[Error on ${topicName}] Failed to deserialize payload:`, err);
    }
  });

  stream.on('error', (err) => {
    console.error(`\n[Error on ${topicName}] Stream error:`, err.message);
  });

  stream.on('end', () => {
    console.log(`\n[Client] Stream ended by server for topic: ${topicName}`);
  });
}

// -----------------------------------------------------------------------------
// 5. Main Execution
// -----------------------------------------------------------------------------
import protobuf from 'protobufjs';

async function main() {
  try {
    // 1. List topics available on the server
    const availableTopics = await listTopics();
    
    // 2. Load domain proto with protobufjs for decoding payloads
    const root = await protobuf.load(ODOMETRY_PROTO_PATH);
    const OdometryMessage = root.lookupType("combined_odom.Odometry");

    // 3. User-defined list of topics they WANT to subscribe to
    const requestedTopics = [
      'amr.001.odom_with_amcl',
      'amr.002.odom_with_amcl',
      'amr.003.odom_with_amcl'
    ];

    console.log(`\n[Client] User requested topics:`);
    requestedTopics.forEach(t => console.log(`  - ${t}`));

    // 4. Filter requested topics against available active topics
    const topicsToSubscribe = requestedTopics.filter(topic => availableTopics.includes(topic));

    if (topicsToSubscribe.length === 0) {
      console.warn(`\n[Warning] None of the requested topics are currently active on the server.`);
      console.log(`Client shutting down...`);
      return;
    }

    console.log(`\n[Client] Matched ${topicsToSubscribe.length} active topics. Initiating subscriptions...`);

    // 5. Subscribe to each matched topic
    for (const topic of topicsToSubscribe) {
      subscribeToTopic(client, topic, OdometryMessage);
    }

  } catch (err) {
    console.error("Fatal error:", err);
  }
}

main();
