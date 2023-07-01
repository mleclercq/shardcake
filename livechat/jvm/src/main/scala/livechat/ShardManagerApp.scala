package livechat

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import zio._

object ShardManagerApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = logger

  val managerConfig = ZLayer.succeed(ManagerConfig.default.copy(rebalanceInterval = 10.seconds))

  def run: Task[Nothing] =
    Server.run.provide(
      managerConfig,
      ZLayer.succeed(GrpcConfig.default),
      ZLayer.succeed(RedisConfig.default),
      redis,
      StorageRedis.live,
      PodsHealth.local,
      GrpcPods.live,
      ShardManager.live
    )
}
