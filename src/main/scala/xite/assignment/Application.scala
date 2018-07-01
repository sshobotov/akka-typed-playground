package xite.assignment

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cats.data._
import cats.implicits._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport.DecodingFailures

import xite.assignment.actor.ApplicationService
import xite.assignment.model._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

object Application extends DefaultJsonProtocol with CircePimps {
  def main(args: Array[String]): Unit = {
    val videos = for (_ <- 1 to 10) yield Random.nextLong
    val system = ActorSystem(ApplicationService.main(videos), "system")

    implicit val untypedSystem = system.toUntyped
    implicit val materializer = ActorMaterializer()

    implicit val ec = system.executionContext
    implicit val scheduler = system.scheduler
    implicit val askTimeout: Timeout = 1.minute

    implicit val rejectionHandling =
      RejectionHandler.newBuilder()
        .handle { case MalformedRequestContentRejection(_, DecodingFailures(errs)) =>
          complete(StatusCodes.BadRequest, RequestFailure(errs.map(err => s"${err.field} is not valid")))
        }
        .result()

    val routes: Route =
      post {
        path("register") {
          entity(as[RegistrationData]) { data =>
            validated(Validation.registrationRules)(data) {
              val result: Future[ApplicationService.Response] =
                system ? (ref => ApplicationService.Register(data, ref))

              onComplete(result) {
                case Success(ApplicationService.Ok(offer)) =>
                  complete(offer)

                case Success(ApplicationService.Failed(errors)) =>
                  complete(errors)

                case Failure(err) =>
                  reject()
              }
            }
          }
        } ~
        path("action") {
          entity(as[VideoActionData]) { data =>
            // A bit of cheating since we know about limited pool of IDs
            val rules: VideoActionData => ValidatedNel[String, Unit] =
              Validation.videoActionRules(_, videos)

            validated(rules)(data) {
              val result: Future[ApplicationService.Response] =
                system ? (ref => ApplicationService.Action(data, ref))

              onComplete(result) {
                case Success(ApplicationService.Ok(offer)) =>
                  complete(offer)

                case Success(ApplicationService.Failed(errors)) =>
                  //complete(StatusCodes.BadRequest, RequestFailure(errors))
                  complete()

                case Failure(err) =>
                  //complete(StatusCodes.BadRequest, RequestFailure())
                  complete()
              }
            }
          }
        }
      }

    Http()
      .bindAndHandle(routes, "localhost", 8085)
      .failed
      .foreach { err =>
        System.err.println(s"Failed to bind to port: ${err.getMessage}")
        system.terminate()
      }

    Await.result(system.whenTerminated, Duration.Inf)
  }

  def validated[T, E](check: T => ValidatedNel[String, Unit])(entry: T): Directive0 =
    Directive { inner =>
      check(entry) match {
        case Validated.Invalid(errs) => complete(400, RequestFailure(errs))
        case _                       => inner(())
      }
    }

  object Errors {
    val invalidEmail    = "userName is not valid"
    val invalidUsername = "email is not valid"
    val invalidAge      = "age is not valid"
    val invalidGender   = "gender is not valid"
    val invalidAction   = "action is not valid"

    val userNotFound    = "userId does not exist"
    val videoNotFound   = "videoId does not exist"
    val badVideoId      = "video does not correspond to last given"
  }

  object Validation {
    object EmailValidation {
      private val emailRegex =
        """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

      def isValid(value: String): Boolean =
        value.nonEmpty && emailRegex.findFirstIn(value).isDefined
    }

    private def cond[E](test: Boolean, error: => E) = Validated.condNel(test, (), error)

    def registrationRules(entry: RegistrationData): ValidatedNel[String, Unit] =
      cond(entry.userName.trim.nonEmpty,                          Errors.invalidUsername) combine
      cond(EmailValidation.isValid(entry.email),                  Errors.invalidEmail) combine
      cond(entry.age >= 5 && entry.age <= 120,                    Errors.invalidAge) combine
      cond(RegistrationData.Gender.values contains entry.gender,  Errors.invalidGender)

    def videoActionRules(entry: VideoActionData, videosPool: Seq[VideoId]): ValidatedNel[String, Unit] =
      cond(videosPool contains entry.videoId,                     Errors.videoNotFound) combine
      cond(VideoActionData.Action.values contains entry.actionId, Errors.invalidAction)
  }
}
