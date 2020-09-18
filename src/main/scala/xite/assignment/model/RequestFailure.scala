package sshobotov.akka.model

import cats.data.NonEmptyList

final case class RequestFailure(errors: NonEmptyList[String])
