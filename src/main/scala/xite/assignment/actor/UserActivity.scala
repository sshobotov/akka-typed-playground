package xite.assignment.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import xite.assignment.model._

object UserActivity {
  type VideoActionTracker = (VideoId, ActorRef[VideoActionData])

  sealed trait Request

  final case class Activate(data: UserRecommendation, client: ActorRef[Response]) extends Request
  final case class CheckUp(data: VideoActionData, client: ActorRef[Response]) extends Request
  final case class Record(data: VideoActionData, client: ActorRef[Response]) extends Request

  sealed trait Response

  final case object Ok extends Response
  final case class Failed(reason: ServiceFailure) extends Response

  def manager(registered: Map[UserId, VideoActionTracker]): Behavior[Request] =
    Behaviors.setup { _ =>
      def recording(data: VideoActionData, client: ActorRef[Response])
                   (act: ActorRef[VideoActionData] => Unit): Behavior[Request] =
        registered.get(data.userId) match {
          case Some((videoId, recorder)) if videoId == data.videoId =>
            act(recorder)

            client ! Ok
            Behaviors.same

          case Some(_) =>
            client ! Failed(ServiceFailure.InvalidVideoId)
            Behaviors.same

          case _ =>
            client ! Failed(ServiceFailure.InvalidUserId)
            Behaviors.same
        }

      Behaviors.receive { (ctx, request) =>
        request match {
          case Activate(data, client) =>
            val trackerRef = registered.get(data.userId) match {
              case Some((_, reusable)) => reusable
              case _                   => ctx.spawn(recorder(data.userId), s"recorder-${data.userId}")
            }

            client ! Ok
            manager(registered + (data.userId -> (data.videoId, trackerRef)))

          case CheckUp(data, client) =>
            recording(data, client) { _ => /* dry run */ }

          case Record(data, client) =>
            recording(data, client) { _ ! data }
        }
      }
    }

  private def recorder(userId: UserId): Behavior[VideoActionData] =
    Behaviors.receive { case (ctx, target) =>
      ctx.log.debug(s"Recording $target")
      Behaviors.same
    }
}
