import cats.effect._
import cats.implicits._
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

trait CS {

  def test[F[_]:ContextShift:Sync](implicit ec: ExecutionContext) : F[Unit] =
    for {
      _ <- ContextShift[F].evalOn(ec)(Sync[F].delay( println("hi") ))
      _ <- Sync[F].delay( println("Welcome") )
    } yield ()

  def blockingThreadPool[F[_]](implicit F: Sync[F]) : Resource[F, ExecutionContext] =
    Resource(F.delay{ 
      val executor = Executors.newCachedThreadPool()
      val ec = ExecutionContext.fromExecutor(executor)
      (ec, F.delay(executor.shutdown))
    })

  def fib[F[_]](n : Int, a : Long = 0, b : Long = 1)(implicit F: Sync[F], cs: ContextShift[F]) : F[Long] = {
    F.suspend {
      val next =
        if (n > 0) fib(n -1, b, a +b) else F.pure(a)
      if (n % 100 == 0) cs.shift *> next else next
    }
  }

  def readName[F[_]](implicit F:Sync[F]) : F[String] =
    F.delay {
      println("Enter your name")
      scala.io.StdIn.readLine()
    }
}

object CtxS extends IOApp with CS {

  override def run(args: List[String]) = {
    val name = blockingThreadPool[IO].use{ ec =>
      contextShift.evalOn(ec)(readName[IO])
    }
    for {
      n <- name
      _ <- IO(println(s"Hello $n"))
      _ <- IO(println(s"Enter a number to compute the fibonacci number"))
      x <- IO(io.StdIn.readLine.toInt)
      r <- blockingThreadPool[IO].use{ ec => contextShift.evalOn(ec)(fib[IO](x)) }
      _ <- IO(println(s"Result: fib($x) => $r"))
    } yield ExitCode.Success
  }

}


