package sshobotov.akka.model

import RegistrationData.Gender

final case class RegistrationData(userName: String, email: String, age: Int, gender: Gender)

object RegistrationData {
  type Gender = Byte

  object Gender {
    val Male:   Byte = 1
    val Female: Byte = 2

    val values: Set[Gender] = Set(Male, Female)
  }
}
