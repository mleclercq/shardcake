package livechat

import livechat.ChatRoomBehavior._
import io.circe.parser._
import io.circe.syntax._

import zio._
import zio.http._
import zio.http.socket.{ WebSocketChannelEvent, WebSocketFrame }
import com.devsisters.shardcake._
import zio.stream.ZStream
import zio.stream.Take
import zio.http.internal.middlewares.Cors.CorsConfig

object ChatRoomApi {

  class Impl(
    sharding: Sharding,
    messenger: Messenger[Message],
    openChatRooms: Ref.Synchronized[Map[String, (Int, Receiver[ChatEvent], Hub[Take[Nothing, ChatEvent]])]],
    scope: Scope
  ) {

    def subscribe(chatRoomId: String): ZIO[Scope, Nothing, ZStream[Any, Throwable, ChatEvent]] =
      ZIO.acquireReleaseExit(
        openChatRooms.modifyZIO(openChatRooms =>
          openChatRooms.get(chatRoomId) match {
            case Some((count, receiver, hub)) =>
              ZIO.succeed(
                (
                  ZStream.fromHub(hub).flattenTake,
                  openChatRooms + (chatRoomId -> ((count + 1, receiver, hub)))
                )
              )
            case None                         =>
              for {
                _        <- ZIO.logDebug(s"Start subscription for $chatRoomId")
                hub      <- Hub.unbounded[Take[Nothing, ChatEvent]]
                receiver <-
                  sharding.receiver((queue: Dequeue[ChatEvent]) =>
                    ZStream.fromQueue(queue).runIntoHub(hub) *> ZIO.never
                  )
                stream   <- ZStream.fromHubScoped(hub)
              } yield (
                ZStream.execute(messenger.sendDiscard(chatRoomId)(Message.Subscribe(receiver))) ++
                  stream.flattenTake,
                openChatRooms + (chatRoomId -> (1, receiver, hub))
              )
          }
        )
      )((_, exit) =>
        openChatRooms
          .modify(openChatRooms =>
            openChatRooms.get(chatRoomId) match {
              case Some((count, receiver, hub)) =>
                if (count <= 1 || exit.isInterrupted)
                  (
                    for {
                      _ <- ZIO.logDebug(
                             s"Shutting down subscription for $chatRoomId ${if (exit.isInterrupted) "on interruption"
                             else "no more client"}"
                           )
                      _ <- ZIO.unless(exit.isInterrupted)(
                             messenger
                               .sendDiscard(chatRoomId)(Message.Unsubscribe(receiver))
                               .catchAllCause(ZIO.logErrorCause("Failed to unsubscribe", _))
                           )
                      _ <- hub.shutdown
                    } yield (),
                    (openChatRooms - chatRoomId)
                  )
                else
                  (
                    ZIO.unit,
                    (openChatRooms + (chatRoomId -> (count - 1, receiver, hub)))
                  )
              case None                         => ZIO.unit -> openChatRooms
            }
          )
          .flatten
      )

    def socketApp(chatRoomId: String, userId: String) =
      for {
        webSocketScope <- scope.fork
        events         <- webSocketScope.extend(subscribe(chatRoomId))
      } yield Http.collectZIO[WebSocketChannelEvent] {
        // Apparently this event is not fired despite what the doc says
        // case ChannelEvent(channel, ChannelEvent.ChannelRegistered)   =>
        case ChannelEvent(channel, ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) =>
          for {
            _     <- webSocketScope.extend(
                       events.runForeach { event =>
                         ZIO.log(s"Chat event: $event") *>
                           channel.writeAndFlush(WebSocketFrame.text(event.asJson.noSpaces))
                       }.catchAllCause(e => ZIO.logErrorCause("Websocket error", e) *> channel.close()).forkScoped
                     )
            users <- messenger.send(chatRoomId)(Message.Join(userId, _))
            _     <-
              channel.writeAndFlush(WebSocketFrame.text((ChatEvent.UsersInRoom(users): ChatEvent).asJson.noSpaces))
          } yield ()
        case ChannelEvent(channel, ChannelEvent.ChannelUnregistered)                                          =>
          for {
            _ <- ZIO.log("channel unregistered")
            _ <- webSocketScope.close(Exit.unit)
            _ <- messenger.sendDiscard(chatRoomId)(Message.Leave(userId))
          } yield ()

        case ChannelEvent(channel, ChannelEvent.ChannelRead(WebSocketFrame.Text(text))) =>
          for {
            message <- ZIO.fromEither(parse(text).flatMap(_.as[String]))
            _       <- messenger.sendDiscard(chatRoomId)(Message.Write(userId, message))
          } yield ()
      }

    val config: CorsConfig =
      CorsConfig(
        allowedOrigin = {
          case origin @ Header.Origin.Value(_, "localhost" | "127.0.0.1", _) =>
            Some(Header.AccessControlAllowOrigin.Specific(origin))
          case _                                                             => None
        },
        allowedMethods = Header.AccessControlAllowMethods(Method.GET)
      )

    val app: HttpApp[Any, Throwable] =
      Http.collectZIO[Request] {
        case req @ Method.GET -> !! / "chatRooms" / chatRoomId / "history" =>
          val from = req.url.queryParams.get("from").flatMap(_.headOption).map(_.toInt).getOrElse(0)
          val to   = req.url.queryParams.get("to").flatMap(_.headOption).map(_.toInt).getOrElse(-1)
          for {
            history <- messenger.send(chatRoomId)(Message.GetHistory(from, to, _))
          } yield Response.json((history: Seq[ChatEvent]).asJson.noSpaces)

        case Method.GET -> !! / "chatRooms" / chatRoomId / "users" / userId =>
          for {
            sApp <- socketApp(chatRoomId, userId)
            r    <- sApp.toSocketApp.toResponse
          } yield r
      } @@ HttpAppMiddleware.cors(config)
  }

  val make: URIO[Sharding with Scope, App[Any]] =
    for {
      sharding      <- ZIO.service[Sharding]
      messenger      = sharding.messenger(ChatRoom)
      openChatRooms <-
        Ref.Synchronized.make(Map.empty[String, (Int, Receiver[ChatEvent], Hub[Take[Nothing, ChatEvent]])])
      scope         <- ZIO.scope
    } yield new Impl(sharding, messenger, openChatRooms, scope).app.withDefaultErrorResponse
}
