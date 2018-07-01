package xite.assignment

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout

import xite.assignment.actor.ApplicationService

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

object Application extends DefaultJsonProtocol with CircePimps {
  def main(args: Array[String]): Unit = {
    val videos = for (_ <- 1 to 10) yield Random.nextLong
    val system = ActorSystem(ApplicationService.main(videos), "system")

    implicit val untypedSystem = system.toUntyped
    implicit val materializer = ActorMaterializer()

    implicit val ec = system.executionContext
    implicit val askTimeout: Timeout = 1.minute

    Http()
      .bindAndHandle(Api.route(system), "localhost", 8085)
      .failed
      .foreach { err =>
        System.err.println(s"Failed to bind to port: ${err.getMessage}")
        system.terminate()
      }

    Await.result(system.whenTerminated, Duration.Inf)
  }
}
