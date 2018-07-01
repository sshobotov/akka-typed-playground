package xite.assignment.actor

import akka.NotUsed
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import xite.assignment.model._

import scala.collection.immutable.Queue

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
            client ! Failed(Seq(/*Errors.userNotFound*/))
            Behaviors.stopped

          case UserActivity.BadVideoId(_) =>
            client ! Failed(Seq(/*Errors.badVideoId*/))
            Behaviors.stopped
        }
      }
    }
}
