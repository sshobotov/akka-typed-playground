package xite.assignment

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Api extends DefaultJsonProtocol with CircePimps {

  def route(system: ActorSystem[ApplicationService.Request])
           (implicit ec: ExecutionContext, materializer: ActorMaterializer, askTimeout: Timeout): Route = {
    implicit val scheduler = system.scheduler

    implicit val rejectionHandling =
      RejectionHandler.newBuilder()
        .handle { case MalformedRequestContentRejection(_, DecodingFailures(errs)) =>
          val errors = errs.map { err => Errors.invalidTemplate.format(err.field) }
          complete(StatusCodes.BadRequest, RequestFailure(errors))
        }
        .result()

    post {
      path("register") {
        entity(as[RegistrationData]) { data =>
          validated(Validation.registrationRules)(data) {
            val result: Future[ApplicationService.Response] =
              system ? (ref => ApplicationService.Register(data, ref))

            onServiceResponse(system.log.error(_, "Register request is failed"))(result) {
              case Right(offer) => complete(offer)
              case Left(reason) =>
                complete(StatusCodes.BadRequest, RequestFailure(NonEmptyList.of(reason)))
            }
          }
        }
      } ~
      path("action") {
        entity(as[VideoActionData]) { data =>
          val onResponse =
            onServiceResponse(system.log.error(_, "Action request is failed")) _

          val checkUp: Future[ApplicationService.Response] =
            system ? (ref => ApplicationService.CheckUp(data, ref))

          onResponse(checkUp) { result =>
            val rules: VideoActionData => ValidatedNel[String, Unit] =
              Validation.videoActionRules(_, result.left.toOption)

            validated(rules)(data) {
              val result: Future[ApplicationService.Response] =
                system ? (ref => ApplicationService.Action(data, ref))

              onResponse(result) {
                case Right(offer) => complete(offer)
                case Left(reason) =>
                  complete(StatusCodes.BadRequest, RequestFailure(NonEmptyList.of(reason)))
              }
            }
          }
        }
      }
    }
  }

  private def validated[T, E](check: T => ValidatedNel[String, Unit])(entry: T): Directive0 =
    Directive { inner =>
      check(entry) match {
        case Validated.Invalid(errs) => complete(StatusCodes.BadRequest, RequestFailure(errs))
        case _                       => inner(())
      }
    }

  private def onServiceResponse(log: Throwable => Unit)
                               (future: Future[ApplicationService.Response])
      : Directive1[Either[String, UserRecommendation]] =
    Directive { inner =>
      import ApplicationService._
      import actor.ServiceFailure._

      onComplete(future) {
        case Success(Ok(offer)) =>
          inner(Tuple1(Right(offer)))

        case Failure(error) =>
          log(error)
          complete(StatusCodes.InternalServerError)

        case Success(Failed(UnexpectedState)) =>
          complete(StatusCodes.InternalServerError)

        case Success(Failed(InvalidUserId)) =>
          inner(Tuple1(Left(Errors.userNotFound)))

        case Success(Failed(InvalidVideoId)) =>
          inner(Tuple1(Left(Errors.badVideoId)))
      }
    }

  object Errors {
    val invalidTemplate = "%s is not valid"
    val invalidEmail    = "userName is not valid"
    val invalidUsername = "email is not valid"
    val invalidAge      = "age is not valid"
    val invalidGender   = "gender is not valid"
    val invalidAction   = "action is not valid"

    val userNotFound    = "userId does not exist"
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

    def videoActionRules(entry: VideoActionData, error: Option[String]): ValidatedNel[String, Unit] =
      cond(error.nonEmpty,                                        error.get) combine
      cond(VideoActionData.Action.values contains entry.actionId, Errors.invalidAction)
  }

}
