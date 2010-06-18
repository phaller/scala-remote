import scala.actors._
import Actor._
import remote._
import RemoteActor._

import scala.tools.partest.FileSync._

case class StopService()
case class GetRequest(request: String)
case class GetResponse(request: GetRequest, response: Option[String])

object LookupService extends Actor {
  val lookupTable = Map(
      "foo" -> "bar",
      "cat" -> "dog",
      "java" -> "scala")
  def act() {
    alive(9100, serviceFactory = NioServiceFactory)
    register('lookupService, self)
    loop {
      react {
        case r @ GetRequest(req) =>
          sender ! GetResponse(r, lookupTable.get(req))
        case StopService() =>
          exit()
      }
    }
  }
}

object Test {
  def main(args: Array[String]) {
    println("Starting lookup service...")
    LookupService.start
    writeFlag()
  }
}
