package xite.assignment

package object actor {
  sealed trait ServiceFailure

  object ServiceFailure {
    case object InvalidUserId extends ServiceFailure
    case object InvalidVideoId extends ServiceFailure
    case object UnexpectedState extends ServiceFailure
  }
}
