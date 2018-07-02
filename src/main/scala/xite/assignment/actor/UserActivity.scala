package xite.assignment.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import xite.assignment.model._

object UserActivity {
  type VideoActionTracker = (VideoId, ActorRef[VideoActionData])

  sealed trait Request

  final case class Activate(userId: UserId, client: ActorRef[Response]) extends Request
  final case class CheckUp(data: VideoActionData, client: ActorRef[Response]) extends Request
  final case class Track(data: VideoActionData, client: ActorRef[Response]) extends Request
  private case class NextOffer(data: UserRecommendation, client: ActorRef[Response]) extends Request

  sealed trait Response

  final case class Ok(next: UserRecommendation) extends Response
  final case class Failed(reason: ServiceFailure) extends Response

  /**
    * Manages action tracking actors
    */
  def manager(offerProvider: ActorRef[VideoRecommendation.Request]): Behavior[Request] =
    Behaviors.receive { (ctx, request) =>
      request match {
        case Activate(userId, client) =>
          ctx.child(Tracker.name(userId)) match {
            case Some(reusable) => reusable.upcast[Tracker.Request] ! Tracker.Recap(client)
            case _              =>
              ctx.spawn(Tracker.nextVideo(userId, offerProvider, offer => ctx.self ! NextOffer(offer, client)),
                s"new-video-$userId")
          }
          Behaviors.same

        case NextOffer(data, client) =>
          ctx.spawn(Tracker.create(offerProvider, data.userId, data.videoId), Tracker.name(data.userId))

          client ! Ok(data)
          Behaviors.same

        case CheckUp(data, client) =>
          ctx.child(Tracker.name(data.userId)) match {
            case Some(ref) => ref.upcast[Tracker.Request] ! Tracker.Action(data, dryRun = true, client)
            case _         => client ! Failed(ServiceFailure.InvalidUserId)
          }
          Behaviors.same

        case Track(data, client) =>
          ctx.child(Tracker.name(data.userId)) match {
            case Some(ref) => ref.upcast[Tracker.Request] ! Tracker.Action(data, dryRun = false, client)
            case _         => client ! Failed(ServiceFailure.InvalidUserId)
          }
          Behaviors.same
      }
    }

  private object Tracker {
    def name(userId: UserId): String = s"tracker-$userId"

    sealed trait Request

    // about `dryRun`: for private api we could use simple flag instead of more verbose separate messages
    case class Action(data: VideoActionData, dryRun: Boolean, client: ActorRef[Response]) extends Request
    case class Recap(client: ActorRef[Response]) extends Request
    private case class NextOffer(data: UserRecommendation, client: ActorRef[Response]) extends Request

    /**
      * Tracks user activity (actions) and recent user's video
      */
    def create(
        offerProvider: ActorRef[VideoRecommendation.Request],
        userId: UserId,
        recentVideoId: VideoId): Behavior[Request] =
      Behaviors.receive { case (ctx, record) =>
        record match {
          case Action(data, true, client) if data.videoId == recentVideoId =>
            client ! Ok(UserRecommendation(userId, recentVideoId))
            Behaviors.same

          case Action(data, false, client) if data.videoId == recentVideoId =>
            ctx.spawn(nextVideo(userId, offerProvider, offer => ctx.self ! NextOffer(offer, client)),
              s"next-video-$userId")
            Behaviors.same

          case Action(_, _, client) =>
            client ! Failed(ServiceFailure.InvalidVideoId)
            Behaviors.same

          case NextOffer(data, client) =>
            client ! Ok(data)
            create(offerProvider, userId, data.videoId)

          case Recap(client) =>
            client ! Ok(UserRecommendation(userId, recentVideoId))
            Behaviors.same
        }
      }

    def nextVideo(
        userId: UserId,
        offerProvider: ActorRef[VideoRecommendation.Request],
        ack: UserRecommendation => Unit): Behavior[VideoRecommendation.Response] =
      Behaviors.setup { ctx =>
        offerProvider ! VideoRecommendation.Request(userId, ctx.self)

        Behaviors.receive { (_, response) =>
          ack(response.entry)
          Behaviors.stopped
        }
      }
  }
}
