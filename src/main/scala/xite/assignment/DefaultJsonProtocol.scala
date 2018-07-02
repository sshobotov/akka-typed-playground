package xite.assignment

import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

import xite.assignment.model._

trait DefaultJsonProtocol extends ErrorAccumulatingCirceSupport {
  implicit val userRecommendationDecoder: Decoder[UserRecommendation] = deriveDecoder
  implicit val registrationDataDecoder: Decoder[RegistrationData] = deriveDecoder
  implicit val videoActionDataDecoder: Decoder[VideoActionData] = deriveDecoder
  implicit val requestFailureDecoder: Decoder[RequestFailure] = deriveDecoder

  implicit val userRecommendationEncoder: Encoder[UserRecommendation] = deriveEncoder
  implicit val registrationDataEncoder: Encoder[RegistrationData] = deriveEncoder
  implicit val videoActionDataEncoder: Encoder[VideoActionData] = deriveEncoder
  implicit val requestFailureEncoder: Encoder[RequestFailure] = deriveEncoder
}
