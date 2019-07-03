import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._

trait AsyncF { 

  def delayed[F[_]:Async] : F[Unit] = for {
    _ <- Sync[F].delay(println("Starting"))
    _ <- Async[F].async{ (cb: Either[Throwable, Unit] => Unit) =>
      Thread.sleep(2000)
      cb(Right(()))
    }
    _ <- Sync[F].delay(println("Done!"))
  } yield ()

}

object AsyncTest extends IOApp with AsyncF {
  override def run(args: List[String]) = {
    delayed[IO].unsafeRunSync()
    IO(ExitCode.Success)
  }
}


