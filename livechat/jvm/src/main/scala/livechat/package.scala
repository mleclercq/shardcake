import com.devsisters.shardcake.StorageRedis.Redis
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import zio.interop.catz._
import zio._
import zio.logging._
import java.time.format.DateTimeFormatter

package object livechat {
  val redis: ZLayer[Any, Throwable, Redis] =
    ZLayer.scopedEnvironment {
      implicit val runtime: zio.Runtime[Any] = zio.Runtime.default
      implicit val logger: Log[Task]         = new Log[Task] {
        override def debug(msg: => String): Task[Unit] = ZIO.logDebug(msg)
        override def error(msg: => String): Task[Unit] = ZIO.logError(msg)
        override def info(msg: => String): Task[Unit]  = ZIO.logInfo(msg)
      }

      (for {
        // Start redis docker container with
        // $ docker run --name redis --rm -p 6379:6379 redis
        client   <- RedisClient[Task].from("redis://localhost:6379")
        commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
        pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
      } yield ZEnvironment(commands, pubSub)).toScopedZIO
    }

  val logger = Runtime.removeDefaultLoggers >>> consoleLogger(
    ConsoleLoggerConfig(
      format = {
        import LogFormat._
        timestamp(DateTimeFormatter.ISO_LOCAL_TIME).fixed(12).color(LogColor.BLUE) |-|
          level.fixed(5).highlight |-|
          fiberId.fixed(13).color(LogColor.WHITE) |-|
          line.highlight +
          (space + label("cause", cause).highlight).filter(LogFilter.causeNonEmpty)

      },
      filter = LogFilter.logLevel(LogLevel.Debug)
    )
  )
}
