import protobuf from 'protobufjs';
import fs from 'fs';

async function main() {
  const root = await protobuf.load('./proto/Odometry.proto');
  const OdometryMessage = root.lookupType("combined_odom.Odometry");

  // Create a dummy message
  const payload = {
    Header: { Seq: 1 },
    ChildFrameId: "base_link",
    Pose: {
      Pose: {
        Position: { X: 1.0, Y: 2.0, Z: 3.0 },
        Orientation: { W: 1.0 }
      }
    }
  };

  const verifyErr = OdometryMessage.verify(payload);
  if (verifyErr) throw Error(verifyErr);

  const message = OdometryMessage.create(payload);
  const buffer = OdometryMessage.encode(message).finish();

  const decoded = OdometryMessage.decode(buffer);
  const obj = OdometryMessage.toObject(decoded, {
    longs: String,
    enums: String,
    bytes: String,
  });

  console.log(JSON.stringify(obj, null, 2));
}

main();
