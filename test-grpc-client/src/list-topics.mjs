import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';

const ROUTER_PROTO_PATH = './proto/stream_router.proto';
const ROUTER_ADDRESS = process.env.ROUTER_ADDRESS || 'localhost:8080';

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

client.ListTopics({}, (err, response) => {
  if (err) {
    console.error(`Error:`, err.message);
    process.exit(1);
  }
  
  const topics = response.topics || [];
  console.log(`Found ${topics.length} active topics:`);
  topics.forEach(t => console.log(`  - ${t}`));
});
