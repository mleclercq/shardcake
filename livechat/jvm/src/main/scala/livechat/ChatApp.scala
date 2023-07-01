package livechat

import com.devsisters.shardcake.{ Config, Server => _, _ }
import com.devsisters.shardcake.interfaces.Serialization
import com.devsisters.shardcake.interfaces.Storage
import zio._
import zio.http._

object ChatApp extends ZIOAppDefault {

  val program: ZIO[Sharding with Scope with Serialization with Server with StorageRedis.Redis, Throwable, Unit] =
    for {
      _   <-
        Sharding.registerEntity(
          ChatRoomBehavior.ChatRoom,
          ChatRoomBehavior.behavior,
          entityMaxIdleTime = Some(1.hour)
        )
      _   <- Sharding.registerScoped
      app <- ChatRoomApi.make
      _   <- Server.serve(app)
      _   <- ZIO.never
    } yield ()

  val portOffsetArg = getArgs.map(_.headOption.flatMap(_.toIntOption).getOrElse(0))

  val config = ZLayer {
    portOffsetArg.flatMap { portOffset =>
      val shardingPort = Config.default.shardingPort + portOffset
      ZIO
        .log(s"using sharding port $shardingPort")
        .as(Config.default.copy(shardingPort = shardingPort))
    }
  }

  val serverConfig = ZLayer {
    portOffsetArg.flatMap { portOffset =>
      val httpPort = 8081 + portOffset
      ZIO
        .log(s"using http port $httpPort")
        .as(Server.Config.default.port(httpPort))
    }
  }

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = logger

  def run =
    ZIO
      .scoped(program)
      .provideSome[ZIOAppArgs](
        config,
        ZLayer.succeed(GrpcConfig.default),
        ZLayer.succeed(RedisConfig.default),
        redis,
        StorageRedis.live,
        Serialization.javaSerialization,
        ShardManagerClient.liveWithSttp,
        GrpcPods.live,
        Sharding.live,
        GrpcShardingService.live,
        serverConfig,
        Server.live
      )
}
