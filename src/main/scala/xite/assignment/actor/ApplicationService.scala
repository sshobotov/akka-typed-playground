package sshobotov.akka.actor

import akka.NotUsed
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import sshobotov.akka.model._

import scala.collection.immutable.Queue

object ApplicationService {
  sealed trait Request

  final case class Register(data: RegistrationData, client: ActorRef[Response]) extends Request
  final case class CheckUp(data: VideoActionData, client: ActorRef[Response]) extends Request
  final case class Action(data: VideoActionData, client: ActorRef[Response]) extends Request

  sealed trait Response

  final case class Ok(offer: UserRecommendation) extends Response
  final case class Failed(reason: ServiceFailure) extends Response

  /**
    * Serves as gateway for a calls outside of the system
    */
  def main(videos: Seq[Long]): Behavior[Request] = Behaviors.setup { ctx =>
    val userRepository = ctx.spawn(UserRepository.repository(Map.empty, Map.empty), "user-repository")
    val videoProvider  = ctx.spawn(VideoRecommendation.provider(Queue(videos: _*)), "video-provider")
    val actionManager  = ctx.spawn(UserActivity.manager(videoProvider), "action-manager")

    Behaviors.receiveMessage {
      case Register(data, client) =>
        ctx.spawn(
          userRecommendation(data, userRepository, videoProvider, actionManager, client),
          s"register-${data.email}")
        Behaviors.same

      case CheckUp(data, client) =>
        ctx.spawn(
          actionChecking(data, actionManager, client),
          s"checkup-${data.userId}-${data.videoId}")
        Behaviors.same

      case Action(data, client) =>
        ctx.spawn(
          actionRecording(data, actionManager, videoProvider, client),
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

      Behaviors.receive { (ctx, msg) =>
        msg match {
          case UserRepository.User(id) =>
            actionManager ! UserActivity.Activate(id, ctx.self)
            Behaviors.same

          case UserActivity.Ok(offer) =>
            client ! Ok(offer)
            Behaviors.stopped

          case _ =>
            Behaviors.unhandled
        }
      }
    }.narrow[NotUsed]

  private def actionChecking(
      data:          VideoActionData,
      actionManager: ActorRef[UserActivity.Request],
      client:        ActorRef[Response]): Behavior[UserActivity.Response] =
    Behaviors.setup { ctx =>
      actionManager ! UserActivity.CheckUp(data, ctx.self)

      Behaviors.receive { (_, msg) =>
        msg match {
          case UserActivity.Ok(offer) =>
            client ! Ok(offer)
            Behaviors.stopped

          case UserActivity.Failed(reason) =>
            client ! Failed(reason)
            Behaviors.stopped
        }
      }
    }

  private def actionRecording(
      data:          VideoActionData,
      actionManager: ActorRef[UserActivity.Request],
      videoProvider: ActorRef[VideoRecommendation.Request],
      client:        ActorRef[Response]): Behavior[NotUsed] =
    Behaviors.setup[AnyRef] { ctx =>
      actionManager ! UserActivity.Track(data, ctx.self)

      Behaviors.receive { (_, msg) =>
        msg match {
          case UserActivity.Ok(offer) =>
            client ! Ok(offer)
            Behaviors.same

          case UserActivity.Failed(reason) =>
            client ! Failed(reason)
            Behaviors.stopped

          case _ =>
            Behaviors.unhandled
        }
      }
    }.narrow[NotUsed]
}
