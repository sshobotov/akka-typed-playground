package xite.assignment

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cats.data._
import cats.implicits._

import xite.assignment.model._

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

object Application extends DefaultJsonProtocol {
  def main(args: Array[String]): Unit = {
    val videos = for (_ <- 1 to 10) yield Random.nextLong
    val system = ActorSystem(ApplicationService.main(videos), "system")

    implicit val untypedSystem = system.toUntyped
    implicit val materializer = ActorMaterializer()

    implicit val ec = system.executionContext
    implicit val scheduler = system.scheduler
    implicit val askTimeout: Timeout = 1.minute

    val routes: Route =
      post {
        path("register") {
          entity(as[RegistrationData]) { data =>
            validated(Rules.registrationRules)(data) {
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
            validated(Rules.videoActionRules)(data) {
              val result: Future[ApplicationService.Response] =
                system ? (ref => ApplicationService.Action(data, ref))

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
        }
      }

    Http()
      .bindAndHandle(routes, "localhost", 8085)
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())

    Await.result(system.whenTerminated, Duration.Inf)
  }

  def validated[T, E](check: T => ValidatedNel[String, Unit])(entry: T): Directive0 =
    Directive { inner =>
      check(entry) match {
        case Validated.Invalid(errs) => reject(errs.map(ValidationRejection(_, None)).toList: _*)
        case _                       => inner(())
      }
    }

  object ApplicationService {
    sealed trait Request

    final case class Register(data: RegistrationData, client: ActorRef[Response]) extends Request
    final case class Action(data: VideoActionData, client: ActorRef[Response]) extends Request

    sealed trait Response

    final case class Ok(offer: UserRecommendation) extends Response
    final case class Failed(errors: Seq[String]) extends Response

    def main(videos: Seq[Long]): Behavior[Request] = Behaviors.setup { ctx =>
      val userRepository = ctx.spawn(UserRepository.repository(Map.empty, Map.empty), "user-repository")
      val videoProvider  = ctx.spawn(VideoRecommendation.provider(Queue(videos: _*)), "video-provider")
      val actionManager  = ctx.spawn(UserActivity.manager(Map.empty), "action-manager")

      Behaviors.receiveMessage {
        case Register(data, client) =>
          ctx.spawn(
            userRecommendation(data, userRepository, videoProvider, actionManager, client),
            s"register-${data.email}")
          Behaviors.same

        case Action(data, client) =>
          ctx.spawn(
            actionTracking(data, actionManager, client),
            s"action-${data.userId}-${data.videoId}")
          Behaviors.same
      }
    }

    private def userRecommendation(
        data:           RegistrationData,
        userRepository: ActorRef[UserRepository.Operation],
        videoProvider:  ActorRef[VideoRecommendation.Request],
        actionManager:  ActorRef[UserActivity.Request],
        client:         ActorRef[Response]): Behavior[NotUsed] =
      Behaviors.setup[AnyRef] { ctx =>
        userRepository ! UserRepository.Insert(data, ctx.self)

        var userRecommendation: Option[UserRecommendation] = None

        Behaviors.receive { (ctx, msg) =>
          msg match {
            case UserRepository.User(id) =>
              videoProvider ! VideoRecommendation.Request(id, ctx.self)
              Behaviors.same

            case VideoRecommendation.Response(offer) =>
              userRecommendation = Some(offer)

              actionManager ! UserActivity.Activate(offer, ctx.self)
              Behaviors.same

            case UserActivity.Ok =>
              val result =
                userRecommendation match {
                  case Some(offer) => Ok(offer)
                  case _           =>
                    ctx.log.error("Unexpected state: no recommendation while activated tracker")
                    Failed(Seq.empty)
                }

              client ! result
              Behaviors.stopped

            case _ =>
              Behaviors.unhandled
          }
        }
      }.narrow[NotUsed]

    private def actionTracking(
        data: VideoActionData,
        actionManager: ActorRef[UserActivity.Request],
        client: ActorRef[Response]): Behavior[UserActivity.Response] =
      Behaviors.setup { ctx =>
        actionManager ! UserActivity.Track(data, ctx.self)

        Behaviors.receive { (_, response) =>
          response match {
            case UserActivity.Ok =>
              client ! Ok(UserRecommendation(data.userId, data.videoId))
              Behaviors.stopped

            case UserActivity.BadUserId =>
              client ! Failed(Seq(Errors.userNotFound))
              Behaviors.stopped

            case UserActivity.BadVideoId(_) =>
              client ! Failed(Seq(Errors.badVideoId))
              Behaviors.stopped
          }
        }
      }
  }

  object UserRepository {
    type Email = String

    sealed trait Operation

    final case class Insert(data: RegistrationData, client: ActorRef[User]) extends Operation
    final case class User(id: UserId)

    def repository(
        entries: Map[UserId, RegistrationData],
        index:   Map[Email, UserId]): Behavior[Operation] =
      Behaviors.receive { (_, operation) =>
        operation match {
          case Insert(data, client) =>
            index.get(data.email) match {
              case Some(userId) =>
                client ! User(userId)
                repository(entries + (userId -> data), index)

              case _            =>
                val id = generateUniqueId(entries)

                client ! User(id)
                repository(entries + (id -> data), index + (data.email -> id))
            }
        }
      }

    @tailrec
    private def generateUniqueId(entries: Map[UserId, RegistrationData]): UserId = {
      val id = generateId

      if (entries.contains(id)) generateUniqueId(entries)
      else id
    }

    private def generateId = Random.nextLong
  }

  object VideoRecommendation {
    final case class Request(userId: UserId, client: ActorRef[Response])
    final case class Response(entry: UserRecommendation)

    def provider(entries: Queue[VideoId]): Behavior[Request] = Behaviors.receive { (_, request) =>
      val (id, updated) = entries.dequeue
      request.client ! Response(UserRecommendation(request.userId, id))

      provider(updated.enqueue(id))
    }
  }

  object UserActivity {
    type VideoActionTracker = (VideoId, ActorRef[VideoActionData])

    sealed trait Request

    final case class Activate(data: UserRecommendation, client: ActorRef[Response]) extends Request
    final case class Track(data: VideoActionData, client: ActorRef[Response]) extends Request

    sealed trait Response

    final case object Ok extends Response
    final case object BadUserId extends Response
    final case class BadVideoId(current: VideoId) extends Response

    def manager(registered: Map[UserId, VideoActionTracker]): Behavior[Request] =
      Behaviors.receive { (ctx, request) =>
        request match {
          case Activate(data, client) =>
            val trackerRef = registered.get(data.userId) match {
              case Some((_, reusable)) => reusable
              case _                   => ctx.spawn(tracker(data.userId), s"tracker-${data.userId}")
            }

            client ! Ok
            manager(registered + (data.userId -> (data.videoId, trackerRef)))

          case Track(data, client) =>
            registered.get(data.userId) match {
              case Some((videoId, tracker)) if videoId == data.videoId =>
                tracker ! data

                client ! Ok
                Behaviors.same

              case Some((videoId, _)) =>
                client ! BadVideoId(videoId)
                Behaviors.same

              case _ =>
                client ! BadUserId
                Behaviors.same
            }
        }
      }

    def tracker(userId: UserId): Behavior[VideoActionData] =
      Behaviors.receive { case (ctx, target) =>
        ctx.log.debug(s"Track $target")
        Behaviors.same
      }
  }

  object Errors {
    val invalidEmail  = "email is not valid"
    val invalidAge    = "age is not valid"
    val invalidGender = "gender is not valid"
    val invalidAction = "action is not valid"

    val userNotFound  = "userId does not exist"
    val badVideoId    = "video does not correspond to last given"
  }

  object Rules {
    private def cond[E](test: Boolean, error: => E) = Validated.condNel(test, (), error)

    def registrationRules(entry: RegistrationData): ValidatedNel[String, Unit] =
      cond(entry.email.nonEmpty, Errors.invalidEmail) combine
      cond(entry.age >= 5 && entry.age <= 120, Errors.invalidAge) combine
      cond(RegistrationData.Gender.values contains entry.gender, Errors.invalidGender)

    def videoActionRules(entry: VideoActionData): ValidatedNel[String, Unit] =
      cond(VideoActionData.Action.values contains entry.actionId, Errors.invalidAction)
  }
}
