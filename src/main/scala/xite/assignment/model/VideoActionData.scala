package xite.assignment.model

import VideoActionData.Action

final case class VideoActionData(userId: Long, videoId: Long, actionId: Action)

object VideoActionData {
  type Action = Byte

  object Action {
    val Like: Byte = 1
    val Skip: Byte = 2
    val Play: Byte = 3

    val values: Set[Action] = Set(Like, Skip, Play)
  }
}
