import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';

const ROUTER_PROTO_PATH = './proto/stream_router.proto';
const ROUTER_ADDRESS = process.env.ROUTER_ADDRESS || 'localhost:8080';

const topicName = process.argv[2];

if (!topicName) {
  console.error("Usage: node src/subscribe-topic.mjs <topic_name>");
  process.exit(1);
}

const packageDefinition = protoLoader.loadSync(ROUTER_PROTO_PATH, {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true
});
const routerProto = grpc.loadPackageDefinition(packageDefinition).com.example.grpc;

const client = new routerProto.StreamRouter(
  ROUTER_ADDRESS,
  grpc.credentials.createInsecure()
);

console.log(`Connecting to Router at ${ROUTER_ADDRESS}...`);
console.log(`Subscribing to topic: ${topicName}\n`);

const stream = client.SubscribeToTopic({ topic: topicName });

stream.on('data', (chunk) => {
  // chunk is RawDataChunk
  console.log(`Received raw chunk on ${chunk.topic}`);
  console.log(`  Key: ${chunk.key}`);
  console.log(`  Payload (hex): ${chunk.payload.toString('hex').substring(0, 50)}... (${chunk.payload.length} bytes)`);
});

stream.on('error', (err) => {
  console.error(`Stream error:`, err.message);
});

stream.on('end', () => {
  console.log(`Stream ended.`);
});
