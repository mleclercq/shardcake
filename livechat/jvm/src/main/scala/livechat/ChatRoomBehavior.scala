package livechat

import com.devsisters.shardcake._
import dev.profunktor.redis4cats.RedisCommands
import io.circe.Codec
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.semiauto._
import zio._
import java.time.Instant

object ChatRoomBehavior {

  sealed trait Message

  object Message {
    final case class Join(userName: String, replier: Replier[Set[String]]) extends Message
    final case class Leave(userName: String)                               extends Message
    final case class Write(userName: String, message: String)              extends Message

    final case class GetHistory(from: Int, to: Int, replier: Replier[Chunk[ChatEvent.UserWrote]]) extends Message
    final case class Subscribe(receiver: Receiver[ChatEvent])                                     extends Message
    final case class Unsubscribe(receiver: Receiver[ChatEvent])                                   extends Message
  }

  object ChatRoom extends EntityType[Message]("chatRoom")

  private final case class State(
    users: Set[String],
    subscribers: Set[Receiver[ChatEvent]],
    nextOffset: Int
  )

  def behavior(
    entityId: String,
    messages: Dequeue[Message]
  ): RIO[Sharding with RedisCommands[Task, String, String], Nothing] =
    (
      for {
        _              <- ZIO.logInfo(s"Started chat room $entityId")
        redis          <- ZIO.service[RedisCommands[Task, String, String]]
        recoveredState <- recoverState(entityId, redis)
        state          <- Ref.make(recoveredState)
        r              <- messages.take.flatMap(handleMessage(entityId, state, redis, _)).forever
      } yield r
    ).ensuring(ZIO.logInfo(s"Chat room $entityId ended"))

  def usersKey(entityId: String)       = s"chatRoom/${entityId}/users"
  def subscribersKey(entityId: String) = s"chatRoom/${entityId}/subscribers"
  def messagesKey(entityId: String)    = s"chatRoom/${entityId}/messages"

  private def getHistory(entityId: String, redis: RedisCommands[Task, String, String], from: Int, to: Int) =
    for {
      persistedMessages <- redis.lRange(messagesKey(entityId), from, to)
      pastMessages      <-
        ZIO
          .foreach(persistedMessages.to(Chunk))(
            parse(_).flatMap(_.as[PersistedMessage]) match {
              case Right(PersistedMessage(time, nextOffset, userName, message)) =>
                ZIO.some(ChatEvent.UserWrote(time.toEpochMilli(), nextOffset, userName, message))
              case Left(err)                                                    =>
                ZIO.logWarning(s"Failed to decode persisted message: $err").as(None)
            }
          )
          .map(_.flatten)
    } yield pastMessages

  private def recoverState(entityId: String, redis: RedisCommands[Task, String, String]): Task[State] =
    for {
      users       <- redis.lRange(usersKey(entityId), 0, -1)
      subscribers <- redis
                       .lRange(subscribersKey(entityId), 0, -1)
                       .map(subscribers =>
                         for {
                           subscriberStr <- subscribers
                           subscriber    <- Receiver[ChatEvent](subscriberStr)
                         } yield subscriber
                       )
      lastMessage <- getHistory(entityId, redis, -1, -1)
    } yield State(users.toSet, subscribers.toSet, lastMessage.headOption.map(_.offset + 1).getOrElse(0))

  def handleMessage(
    entityId: String,
    state: Ref[State],
    redis: RedisCommands[Task, String, String],
    message: Message
  ): RIO[Sharding, Unit] = {

    def unsubscribe(subscriber: Receiver[ChatEvent]) =
      state.update(s => s.copy(subscribers = s.subscribers - subscriber)) *>
        redis.lRem(subscribersKey(entityId), 1, subscriber.toString)

    def publish(subscribers: Set[Receiver[ChatEvent]], update: ChatEvent) =
      ZIO.foreachDiscard(subscribers) { subscriber =>
        ZIO.unlessZIO(subscriber.send(update))(
          ZIO.log(s"Remove unreachable subscriber $subscriber") *> unsubscribe(subscriber)
        )
      }

    message match {
      case Message.Join(userName, replier)       =>
        for {
          s <- state.updateAndGet(s => s.copy(users = s.users + userName))
          _ <- redis.lPush(usersKey(entityId), userName)
          _ <- replier.reply(s.users)
          _ <- publish(s.subscribers, ChatEvent.UserJoined(userName))
        } yield ()
      case Message.Leave(userName)               =>
        for {
          s <- state.updateAndGet(s => s.copy(users = s.users - userName))
          _ <- redis.lRem(usersKey(entityId), 1, userName)
          _ <- publish(s.subscribers, ChatEvent.UserLeft(userName))
        } yield ()
      case Message.Write(userName, message)      =>
        for {
          s   <- state.getAndUpdate(s => s.copy(nextOffset = s.nextOffset + 1))
          now <- Clock.instant
          _   <-
            redis.rPush(messagesKey(entityId), PersistedMessage(now, s.nextOffset, userName, message).asJson.noSpaces)
          _   <- publish(s.subscribers, ChatEvent.UserWrote(now.toEpochMilli(), s.nextOffset, userName, message))
        } yield ()
      case Message.GetHistory(from, to, replier) =>
        for {
          pastMessages <- getHistory(entityId, redis, from, to)
          _            <- replier.reply(pastMessages)
        } yield ()
      case Message.Subscribe(receiver)           =>
        for {
          _ <- ZIO.log(s"Add subscriber $receiver")
          _ <- state.update(s => s.copy(subscribers = s.subscribers + receiver))
          _ <- redis.lPush(subscribersKey(entityId), receiver.toString)
        } yield ()
      case Message.Unsubscribe(receiver)         =>
        for {
          _ <- ZIO.log(s"Remove subscriber $receiver")
          _ <- unsubscribe(receiver)
        } yield ()
    }
  }

  private case class PersistedMessage(time: Instant, offset: Int, userName: String, message: String)
  private object PersistedMessage {
    implicit val codec: Codec[PersistedMessage] = deriveCodec
  }
}
