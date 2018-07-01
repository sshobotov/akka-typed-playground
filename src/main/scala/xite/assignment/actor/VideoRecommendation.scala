package xite.assignment.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import xite.assignment.model._

import scala.collection.immutable.Queue

object VideoRecommendation {
  final case class Request(userId: UserId, client: ActorRef[Response])
  final case class Response(entry: UserRecommendation)

  def provider(entries: Queue[VideoId]): Behavior[Request] = Behaviors.receive { (_, request) =>
    val (id, updated) = entries.dequeue
    request.client ! Response(UserRecommendation(request.userId, id))

    provider(updated.enqueue(id))
  }
}
