package xite.assignment.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import xite.assignment.model._

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
