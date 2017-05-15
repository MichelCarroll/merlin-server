import java.util.UUID


import akka.NotUsed
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.{ActorMaterializer, ClosedShape, FlowShape, OverflowStrategy}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Sink, Source}
import akka.stream.scaladsl.GraphDSL.Implicits._
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.sslconfig.util.ConfigLoader
import sun.misc.resources.Messages_sv

import concurrent.ExecutionContext.Implicits._
import scala.io.StdIn
import scala.util.Random

sealed trait RoomMessage

import shared.SocketMessage
import shared.SocketMessage._

case class SocketMessageFromUser(userId: String, message: SocketMessage) extends RoomMessage
case class UserJoined(userId: String, actor: ActorRef) extends RoomMessage
case class UserLeft(userId: String) extends RoomMessage

class ChatRoomActor(roomId: Int) extends Actor {

  var participants = Map[String, ActorRef]()

  override def receive: Receive = {

    case SocketMessageFromUser(fromUserId, message) =>
      message match {
        case Offer(toUserId, t, s) =>
          participants(toUserId) ! Offer(fromUserId, t, s)

        case Answer(toUserId, t, s) =>
          participants(toUserId) ! Answer(fromUserId, t, s)

        case IceCandidateOutgoing(toUserId, c, i) =>
          participants(toUserId) ! IceCandidateIncoming(fromUserId, c, i)

        case OfferRequest(_) =>
        case IceCandidateIncoming(_,_,_) =>
      }

    case UserJoined(userId, actorRef) =>
      participants.values.foreach(_ ! OfferRequest(userId))
      participants += userId -> actorRef

    case UserLeft(userId) =>
      participants -= userId

  }

}

class ChatRoom(roomId: Int)(implicit actorSystem: ActorSystem) {

  private[this] val chatRoomActor = actorSystem.actorOf(Props(classOf[ChatRoomActor], roomId))

  def websocketFlow(): Flow[Message, Message, _] = {

    val userId = UUID.randomUUID().toString

    val source = Source.actorRef[SocketMessage](bufferSize = 5, overflowStrategy = OverflowStrategy.fail)
    val graph = GraphDSL.create(source) { implicit builder => chatSource =>

      import GraphDSL.Implicits._

      val fromWebsocket = builder.add {
        Flow[Message].collect {
          case TextMessage.Strict(txt) => {
            println(txt)
            SocketMessageFromUser(userId, upickle.default.read[SocketMessage](txt))
          }
        }
      }

      val backToWebsocket = builder.add {
        Flow[SocketMessage].map {
          case x: SocketMessage => TextMessage(upickle.default.write[SocketMessage](x))
        }
      }

      val chatActorSink = Sink.actorRef[RoomMessage](chatRoomActor, UserLeft(userId))

      val merge = builder.add(Merge[RoomMessage](2))

      val actorAsSource = builder.materializedValue.map(actor => UserJoined(userId, actor))


      fromWebsocket ~> merge.in(0)

      actorAsSource ~> merge.in(1)

      merge ~> chatActorSink

      chatSource ~> backToWebsocket

      FlowShape(fromWebsocket.in, backToWebsocket.out)
    }

    Flow.fromGraph[Message, Message, ActorRef](graph)

  }


  def sendMessage(message: RoomMessage): Unit = chatRoomActor ! message

}

object ChatRoom {
  def apply(roomId: Int)(implicit actorSystem: ActorSystem) = new ChatRoom(roomId)
}

object ChatRooms {

  var chatRooms = Map[Int, ChatRoom]()

  def findOrCreate(roomId: Int)(implicit actorSystem: ActorSystem): ChatRoom =
    chatRooms.getOrElse(roomId, createNewChatRoom(roomId))

  private def createNewChatRoom(roomId: Int)(implicit actorSystem: ActorSystem) = {
    val chatRoom = ChatRoom(roomId)
    chatRooms += roomId -> chatRoom
    chatRoom
  }
}

/**
  * Created by michelcarroll on 2017-05-14.
  */


object Server extends App {

  val config = ConfigFactory.defaultApplication()

  implicit val actorSystem = ActorSystem("akka-stream", config)
  implicit val actorMaterializer = ActorMaterializer()

  val interface = "localhost"
  val port = 8080

  import akka.http.scaladsl.server.Directives._

  val echoService: Flow[Message, Message, _] = Flow[Message].map {
    case TextMessage.Strict(txt) => TextMessage(s"Echo: $txt")
    case _ => TextMessage("Message not supported")
  }

  val route = get {
    pathEndOrSingleSlash {
      handleWebSocketMessages(ChatRooms.findOrCreate(123).websocketFlow())
    }
  }

  val bindingFuture = Http().bindAndHandle(route, interface, port).onSuccess {
    case (binding) =>
      println(s"Server is now online at http://$interface:$port. Press ENTER to stop it")
      StdIn.readLine()
      binding.unbind().onComplete(_ => actorSystem.terminate())
  }

}
