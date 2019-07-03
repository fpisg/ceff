package service.async

import cats._
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import scala.concurrent.{Future, ExecutionContext}

import java.util.concurrent.Executors

// @author Raymond Tay
// @version 1.0
//
trait Service { 

  def apiCall(message: String) = Future.successful(s"[$message] I am here.")

  def run[F[_]:Async](message: String)(implicit ec: ExecutionContext) : F[String] =
    Async[F].async { (cb: Either[Throwable, String] => Unit) => 
      import scala.util.{Failure, Success}

      apiCall(message).onComplete {
        case Success(value) =>
          Thread.sleep(1000)
          cb(Right(value))
        case Failure(error) => cb(Left(error))
      }
    }

}

object Services extends IOApp with Service {

  override def run(args: List[String]) = {
    val executor = Executors.newCachedThreadPool()
    implicit val ec = ExecutionContext.fromExecutor(executor)

    for {
      _ <- Sync[IO].delay(println("Starting ..."))
      result <- run[IO]("A_TEST_MESSAGE")
      _ <- Sync[IO].delay(println(s"We are truly done, with outcome: $result."))
    } yield ()

    //run[IO]("aoaoaoaoaoaoa").unsafeRunSync

    IO{ ExitCode.Success }
  }

}


