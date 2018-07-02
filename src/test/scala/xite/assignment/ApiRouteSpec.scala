package xite.assignment

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import akka.util.Timeout
import cats.data.NonEmptyList

import actor.ApplicationService
import model._

import scala.concurrent.duration._

class ApiRouteSpec extends WordSpec with Matchers with ScalatestRouteTest with BeforeAndAfterAll
    with DefaultJsonProtocol {
  val testSystem: ActorSystem[ApplicationService.Request] =
    ActorSystem(ApplicationService.main(Seq(4324556L, 6454556L)), "test")

  implicit val ec = testSystem.executionContext
  implicit val askTimeout: Timeout = 1.minute

  val testRoute: Route = Route.seal(Api.route(testSystem))

  override def afterAll(): Unit = {
    testSystem.terminate()
  }

  "Service API" should {
    var targetUserId: Long = 0

    "register user and provide recommendation" in {
      val data = RegistrationData("David", "david@gmail.com", 28, RegistrationData.Gender.Male)

      Post("/register", data) ~> testRoute ~> check {
        status shouldEqual StatusCodes.OK

        val entity = responseAs[UserRecommendation]
        targetUserId = entity.userId

        entity.videoId shouldEqual 4324556L
      }
    }

    "receive user reaction on video and provide new one" in {
      val data = VideoActionData(targetUserId, 4324556L, VideoActionData.Action.Like)

      Post("/action", data) ~> testRoute ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[UserRecommendation].videoId shouldEqual 6454556L
      }
    }

    "return error if not recent video's ID was used" in {
      val badData = VideoActionData(targetUserId, 4324556L, VideoActionData.Action.Like)

      Post("/action", badData) ~> testRoute ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[RequestFailure].errors.head shouldEqual Api.Errors.badVideoId
      }
    }

    "return accumulative errors for invalid data" in {
      val badData = RegistrationData("", "david", 0, 3: Byte)

      Post("/register", badData) ~> Route.seal(testRoute) ~> check {
        status shouldEqual StatusCodes.BadRequest

        val entity = responseAs[RequestFailure]
        val errors = NonEmptyList.of(Api.Errors.invalidUsername, Api.Errors.invalidEmail,
          Api.Errors.invalidAge, Api.Errors.invalidGender)

        entity.errors shouldEqual errors
      }
    }
  }
}
