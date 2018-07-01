package xite.assignment

import io.circe.{CursorOp, DecodingFailure}

trait CircePimps {

  implicit class DecodingFailureOps(error: DecodingFailure) {
    def field: String =
      CursorOp.opsToPath(error.history).substring(1)
  }

}
