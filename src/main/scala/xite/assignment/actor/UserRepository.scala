package sshobotov.akka.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import sshobotov.akka.model._

import scala.annotation.tailrec
import scala.util.Random

object UserRepository {
  type Email = String

  sealed trait Operation

  final case class Insert(data: RegistrationData, client: ActorRef[User]) extends Operation
  final case class User(id: UserId)

  /**
    * Manages user ID's
    */
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