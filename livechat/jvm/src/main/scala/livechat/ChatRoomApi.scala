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
import com.devsisters.shardcake.errors.PodUnavailable

object ChatRoomApi {

  class Impl(
    sharding: Sharding,
    messenger: Messenger[Message],
    openChatRooms: Ref.Synchronized[Map[String, (Int, Hub[Take[Throwable, ChatEvent]])]],
    scope: Scope
  ) {

    def getUpdates(chatRoomId: String): ZStream[Any, Throwable, ChatEvent] =
      messenger.sendStreamAutoRestart(chatRoomId, -1)(Message.GetUpdates) {
        case (_, ChatEvent.UserWrote(_, offset, _, _)) =>
          // plus one here because if we were to restart the stream at that point, we want to get all the events that
          // happened after this one
          offset + 1
        case (offset, _)                               => offset
      }

    /**
     * Subscribes to the ChatEvents from a chat room. The subscription is automatically cancelled when the scope
     * closes.
     */
    def subscribe(chatRoomId: String): ZIO[Scope, Nothing, ZStream[Any, Throwable, ChatEvent]] =
      openChatRooms
        .modifyZIO(openChatRooms =>
          // Note: here we are trying to do the minimum required to return the updated `openChatRooms`
          // and minimize the time during which we hold the lock on the Ref.Synchronized.
          // The rest of the effects to be done to complete the subscription are returned by `modifyZIO`
          // and flatten bellow
          openChatRooms.get(chatRoomId) match {
            case Some((count, hub)) =>
              // Happy path: we already have a hub from which we can get the ChatEvent.
              // Not effect to be done inside `modifyZIO`
              ZIO.succeed(
                // The effect to run outside of `modifyZIO` to
                ZStream.fromHubScoped(hub).map(_.flattenTake),
                openChatRooms + (chatRoomId -> ((count + 1, hub)))
              )
            case None               =>
              // No hub, we need to create one and add it to the `openChatRooms` Ref
              // Creating the hub is the only effect we run inside `modifyZIO`, the rest (asking the entity to send
              // us ChatEvents) is returned by `modifyZIO` and flatten bellow.
              Hub.unbounded[Take[Throwable, ChatEvent]].map { hub =>
                (
                  // The effect to ask the entity to send us ChatEvents. This is run by `.flatten` bellow
                  for {
                    _      <- ZIO.log(s"Start getting messages for $chatRoomId")
                    stream <- ZStream.fromHubScoped(hub).map(_.flattenTake)
                    _      <- getUpdates(chatRoomId)
                                .runIntoHub(hub)
                                .ensuring(ZIO.log(s"Done getting messages for $chatRoomId"))
                                .forkScoped
                  } yield stream,
                  openChatRooms + (chatRoomId -> (1, hub))
                )
              }
          }
        )
        .flatten
        .withFinalizerExit((_, exit) =>
          openChatRooms
            .modify(openChatRooms =>
              openChatRooms.get(chatRoomId) match {
                case Some((count, hub)) =>
                  if (count <= 1 || exit.isInterrupted)
                    (
                      ZIO.log(
                        s"Shutting down subscription for $chatRoomId ${if (exit.isInterrupted) "on interruption"
                        else "no more client"}"
                      ) *>
                        hub.shutdown,
                      (openChatRooms - chatRoomId)
                    )
                  else ZIO.unit -> (openChatRooms + (chatRoomId -> (count - 1, hub)))
                case None               => ZIO.unit -> openChatRooms
              }
            )
            .flatten
        )

    def socketApp(chatRoomId: String, userId: String) =
      for {
        // The scope matching the lifecycle of the websocket
        webSocketScope <- scope.fork
        // subscribe to the ChatEvents from the chatRoom in the scope of the websocket (i.e. automatically cancel
        // subscription when the websocket closes)
        events         <- webSocketScope.extend(subscribe(chatRoomId))
      } yield Http.collectZIO[WebSocketChannelEvent] {
        // Apparently this event is not fired despite what the doc says
        // case ChannelEvent(channel, ChannelEvent.ChannelRegistered)   =>
        case ChannelEvent(channel, ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete)) =>
          for {
            _     <- webSocketScope.extend(
                       events.runForeach { event =>
                         ZIO.log(s"Send chat event $event in room $chatRoomId to $userId") *>
                           channel.writeAndFlush(WebSocketFrame.text(event.asJson.noSpaces))
                       }.catchAllCause(e => ZIO.logErrorCause("Websocket error", e) *> channel.close())
                         .forkScoped // TODO which scope is that ??
                     )
            users <- messenger.send(chatRoomId)(Message.Join(userId, _))
            _     <-
              channel.writeAndFlush(WebSocketFrame.text((ChatEvent.UsersInRoom(users): ChatEvent).asJson.noSpaces))
          } yield ()

        case ChannelEvent(channel, ChannelEvent.ChannelUnregistered) =>
          for {
            _ <- ZIO.log(s"Websocket for room $chatRoomId to $userId closed")
            _ <- webSocketScope.close(Exit.unit)
            // Note: We get ChannelUnregistered event when the pod is shuting down, but if this pod is also the one
            // running the chat room entity, the Leave message isn't delivered since the entity is also shutting
            // down. Not sure how to solve this...
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
        Ref.Synchronized.make(Map.empty[String, (Int, Hub[Take[Throwable, ChatEvent]])])
      scope         <- ZIO.scope
    } yield new Impl(sharding, messenger, openChatRooms, scope).app.withDefaultErrorResponse
}
