package com.devsisters.shardcake

import com.devsisters.shardcake.CounterActor.CounterMessage._
import com.devsisters.shardcake.CounterActor._
import com.devsisters.shardcake.interfaces.{ Pods, Serialization, Storage }
import zio.{ Config => _, _ }
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._

object ShardingSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ShardingSpec")(
      test("Send message to entities") {
        ZIO.scoped {
          for {
            _       <- Sharding.registerEntity(Counter, behavior)
            _       <- Sharding.registerScoped
            counter <- Sharding.messenger(Counter)
            _       <- counter.sendDiscard("c1")(IncrementCounter)
            _       <- counter.sendDiscard("c1")(DecrementCounter)
            _       <- counter.sendDiscard("c1")(IncrementCounter)
            _       <- counter.sendDiscard("c1")(IncrementCounter)
            _       <- counter.sendDiscard("c2")(IncrementCounter)
            _       <- Clock.sleep(1 second)
            c1      <- counter.send("c1")(GetCounter.apply)
            c2      <- counter.send("c2")(GetCounter.apply)
          } yield assertTrue(c1 == 2) && assertTrue(c2 == 1)
        }
      },
      test("Receive updates from entity") {
        ZIO.scoped {
          for {
            _        <- Sharding.registerEntity(Counter, behavior)
            _        <- Sharding.registerScoped
            counter  <- Sharding.messenger(Counter)
            updates  <- Ref.make(Chunk.empty[Update])
            receiver <-
              Sharding.receiver[Any, Update](queue => queue.take.flatMap(update => updates.update(_ :+ update)).forever)
            _        <- counter.sendDiscard("c1")(Subscribe(receiver))
            _        <- counter.sendDiscard("c1")(IncrementCounter)
            _        <- counter.sendDiscard("c1")(DecrementCounter)
            _        <- counter.sendDiscard("c1")(IncrementCounter)
            _        <- counter.sendDiscard("c1")(IncrementCounter)
            _        <- counter.sendDiscard("c1")(Unsubscribe(receiver))
            _        <- counter.sendDiscard("c1")(IncrementCounter)
            _        <- Clock.sleep(1 second)
            items    <- updates.get
          } yield assertTrue(
            items == Chunk(
              Update.Incremented(1),
              Update.Decremented(0),
              Update.Incremented(1),
              Update.Incremented(2)
            )
          )
        }
      },
      test("Entity termination") {
        ZIO.scoped {
          for {
            _       <- Sharding.registerEntity(Counter, behavior, entityMaxIdleTime = Some(1.seconds))
            _       <- Sharding.registerScoped
            counter <- Sharding.messenger(Counter)
            _       <- counter.sendDiscard("c3")(IncrementCounter)
            c0      <- counter.send("c3")(GetCounter.apply)
            _       <- Clock.sleep(3 seconds)
            c1 <- counter.send("c3")(GetCounter.apply) // counter should be restarted
          } yield assertTrue(c0 == 1, c1 == 0)
        }
      },
      test("Cluster singleton") {
        ZIO.scoped {
          for {
            p   <- Promise.make[Nothing, Unit]
            _   <- Sharding.registerSingleton("singleton", p.succeed(()) *> ZIO.never)
            _   <- Sharding.registerScoped
            res <- p.await
          } yield assertTrue(res == ())
        }
      }
    ).provideShared(
      Sharding.live,
      Serialization.javaSerialization,
      Pods.noop,
      ShardManagerClient.local,
      Storage.memory,
      ZLayer.succeed(Config.default)
    ) @@ sequential @@ withLiveClock
}

object CounterActor {
  sealed trait CounterMessage

  object CounterMessage {
    case class GetCounter(replier: Replier[Int])       extends CounterMessage
    case class Subscribe(receiver: Receiver[Update])   extends CounterMessage
    case class Unsubscribe(receiver: Receiver[Update]) extends CounterMessage
    case object IncrementCounter                       extends CounterMessage
    case object DecrementCounter                       extends CounterMessage
  }

  sealed trait Update
  object Update {
    case class Incremented(value: Int) extends Update
    case class Decremented(value: Int) extends Update
  }

  object Counter extends EntityType[CounterMessage]("counter")

  def behavior(entityId: String, messages: Dequeue[CounterMessage]): RIO[Sharding, Nothing] =
    ZIO.logInfo(s"Started entity $entityId") *>
      Ref
        .make(0 -> Set.empty[Receiver[Update]])
        .flatMap { state =>
          def unsubscribe(subscriber: Receiver[Update]) =
            state.update { case (count, subscribers) => (count, subscribers - subscriber) }

          def publish(update: Update, subscribers: Set[Receiver[Update]]) =
            ZIO.foreachDiscard(subscribers) { subscriber =>
              ZIO.unlessZIO(subscriber.send(update))(unsubscribe(subscriber))
            }

          messages.take.flatMap {
            case CounterMessage.GetCounter(replier)   => state.get.flatMap { case (count, _) => replier.reply(count) }
            case CounterMessage.IncrementCounter      =>
              state.updateAndGet { case (count, subscribers) => (count + 1, subscribers) }.flatMap {
                case (count, subscribers) => publish(Update.Incremented(count), subscribers)
              }
            case CounterMessage.DecrementCounter      =>
              state.updateAndGet { case (count, subscribers) => (count - 1, subscribers) }.flatMap {
                case (count, subscribers) => publish(Update.Decremented(count), subscribers)
              }
            case CounterMessage.Subscribe(receiver)   =>
              state.update { case (count, subscribers) => (count, subscribers + receiver) }
            case CounterMessage.Unsubscribe(receiver) => unsubscribe(receiver)
          }.forever
        }
}
