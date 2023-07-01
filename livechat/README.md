# Livechat full stack demo

## How to run

Build the backend

```
sbt livechatJVM/stage
```

Start a redis container

```bash
docker run --name redis --rm -p 6379:6379 redis
```

Start the shard manager

```bash
./livechat/jvm/target/universal/stage/bin/shard-manager-app
```

Start a pod

```bash
./livechat/jvm/target/universal/stage/bin/chat-app
```

Start the frontend server

```bash
cd livechat
npm install
npm run dev
```

Optional: start a second pod

```bash
./livechat/jvm/target/universal/stage/bin/chat-app 1
```

You can start more by passing increasing numbers. These numbers ensures that each pod uses different ports.

Note that the frontend app is hardcoded to talk to the first pod on port 8081 but running other pods can be interresting to see how shards are reassigned when a pod joins or leaves.
