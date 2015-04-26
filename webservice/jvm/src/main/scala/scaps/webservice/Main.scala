package scaps.webservice

import scala.concurrent.duration.DurationInt
import akka.actor.ActorSystem
import akka.util.Timeout
import scaps.webapi.ScapsApi
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.Directive.pimpApply
import spray.routing.SimpleRoutingApp
import upickle._
import spray.http.StatusCodes
import spray.http.HttpEntity
import spray.http.MediaTypes
import scalatags.Text
import akka.actor.Props
import akka.io.IO
import spray.can.Http
import akka.actor.Actor
import akka.pattern.ask
import akka.actor.ActorRef
import akka.actor.Terminated

object Main extends App {
  implicit val system = ActorSystem("api-search-system")
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(1.second)

  val service = system.actorOf(Props[ScapsServiceActor], "scapsService")

  IO(Http) ! Http.Bind(service, "localhost", port = 8080)
}