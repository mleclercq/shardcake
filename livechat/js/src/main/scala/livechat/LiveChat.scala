package livechat

import com.raquo.laminar.api.L._
import io.laminext.syntax.core._
import io.laminext.websocket.circe._
import io.laminext.fetch.circe._

import scala.scalajs.js
import scala.scalajs.js.annotation._
import org.scalajs.dom
import java.time.Instant
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._

object LiveChat {

  val host             = "localhost:8081"
  val room             = "room1"
  val thisUser: String = "Bob"
  val ws               =
    WebSocket
      .url(s"ws://$host/chatRooms/$room/users/$thisUser")
      .json[ChatEvent, String]
      .build(managed = true, reconnectRetries = 100)

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      appElement()
    )

  def appElement(): Element =
    div(
      cls := "container mx-auto max-w-lg flex flex-col space-y-2",
      ws.connect,
      h1(
        cls := "text-3xl self-center my-8",
        "Live Chat!"
      ),
      chatMessages(),
      messageInput(),
      div(
        cls := "flex space-x-2",
        code("connected:"),
        code(
          child.text <-- ws.isConnected.map(_.toString)
        )
      )
    )

  def chatMessages(): Element = {
    final class MessageId()
    final case class Message(id: MessageId, time: js.Date, offset: Option[Int], user: String, message: String)
    object Message {
      def apply(event: ChatEvent): Option[Message] =
        event match {
          case ChatEvent.UserWrote(time, offset, user, message) =>
            Some(Message(new MessageId(), new js.Date(time.toDouble), Some(offset), user, message))
          case ChatEvent.UserJoined(user) if user != thisUser   =>
            Some(Message(new MessageId(), new js.Date(), None, user, "-- joined the chat --"))
          case ChatEvent.UserLeft(user)                         =>
            Some(Message(new MessageId(), new js.Date(), None, user, "-- left the chat --"))
          case _                                                => None
        }
    }

    def renderMessage(message: Signal[Message]): HtmlElement =
      div(
        cls := "flex flex-row space-x-2",
        span(cls := "grow-0", child.text <-- message.map(m => m.time.toLocaleTimeString())),
        span(cls := "grow-0", child.text <-- message.map(m => s"${m.user}:")),
        span(cls := "grow", child.text <-- message.map(_.message))
      )

    val messagesVal = Var(Seq.empty[Message])
    val weReceive   = ws.received --> messagesVal.updater[ChatEvent]((messages, event) => messages ++ Message(event))

    val topMessageOffset =
      messagesVal.signal.map(_.collectFirst { case Message(_, _, Some(offset), _, _) => offset }.filter(_ != 0))

    val (historyStream, historyReceived) = EventStream.withCallback[FetchResponse[Seq[ChatEvent]]]
    def getHistory(from: Int, to: Int)   =
      Fetch
        .get(s"http://$host/chatRooms/$room/history?from=$from&to=$to")
        .decodeOkay[Seq[ChatEvent]]

    div(
      cls := "w-full h-96 overflow-auto border-2 border-gray-600 rounded-lg p-4 space-y-2",
      weReceive,
      if (messagesVal.now().isEmpty) getHistory(-10, -1) --> historyReceived else emptyNode,
      historyStream --> messagesVal.updater[FetchResponse[Seq[ChatEvent]]]((messages, events) =>
        events.data.flatMap(Message(_)) ++ messages
      ),
      child.maybe <-- topMessageOffset.map(
        _.map(offset =>
          button(
            "More Messages...",
            cls                                  := "w-full self-center bg-gray-100 hover:bg-gray-300 font-bold py-1 px-4 rounded",
            styleProp[String]("overflow-anchor") := "none",
            onClick.flatMap(_ => getHistory((offset - 10).max(0), (offset - 1).max(0))) --> historyReceived
          )
        )
      ),
      // messagesVal.signal.map(_.headOption.map(_.offset))
      children <-- messagesVal.signal.split(_.id)((_, _, s) =>
        renderMessage(s).amend(styleProp[String]("overflow-anchor") := "none")
      ),
      // CSS trick to make the scroll stick to the bottom
      // https://css-tricks.com/books/greatest-css-tricks/pin-scrolling-to-bottom/
      div(
        styleProp[String]("overflow-anchor") := "auto",
        height                               := "1px"
      )
    )
  }

  def messageInput(): Element = {
    val inputMessageVar = Var("")
    val submit          = Observer.combine(ws.send, Observer[String](_ => inputMessageVar.set("")))

    form(
      thisEvents(onSubmit.preventDefault).sample(inputMessageVar.signal).filter(_.nonEmpty) --> submit,
      cls := "flex flex-row w-full space-x-2",
      input(
        cls         := "grow shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 leading-tight focus:outline-none focus:shadow-outline",
        tpe         := "text",
        placeholder := "send a message",
        onMountFocus,
        disabled <-- !ws.isConnected,
        controlled(
          value <-- inputMessageVar,
          onInput.mapToValue --> inputMessageVar
        )
      ),
      button(
        "send",
        cls         := "bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded",
        disabled <-- (!ws.isConnected || inputMessageVar.signal.map(_.isEmpty()))
      )
    )
  }
}
