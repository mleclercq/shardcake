package livechat

import io.circe.Codec
import io.circe.generic.semiauto._

sealed trait ChatEvent
object ChatEvent {

  final case class UserJoined(user: String)                                               extends ChatEvent
  final case class UserLeft(user: String)                                                 extends ChatEvent
  final case class UserWrote(timestamp: Long, offset: Int, user: String, message: String) extends ChatEvent
  final case class UsersInRoom(users: Set[String])                                        extends ChatEvent

  implicit val codec: Codec.AsObject[ChatEvent] = deriveCodec
}
