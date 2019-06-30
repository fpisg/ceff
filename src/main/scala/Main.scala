import cats._
import cats.data._
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.implicits._
import java.io.{OutputStream, InputStream, File, FileOutputStream, FileInputStream}

trait IOFunctions {

  def inputStream(f:File, guard: Semaphore[IO]) : Resource[IO, FileInputStream] =
    Resource.make {
      IO(new FileInputStream(f))
    } { inStream => guard.withPermit{IO(inStream.close()).handleErrorWith(_ => IO.unit)} }

  def outputStream(f: File, guard: Semaphore[IO]) : Resource[IO, FileOutputStream] =
    Resource.make {
      IO(new FileOutputStream(f))
    } { outStream => guard.withPermit{ IO(outStream.close()).handleErrorWith(_ => IO.unit) }}

  def inputOutputStreams(in: File, out: File, guard: Semaphore[IO]): Resource[IO, (InputStream, OutputStream)] =
    for {
      inStream  <- inputStream(in, guard)
      outStream <- outputStream(out, guard)
    } yield (inStream, outStream)

  // Another way to express `inputOutputStreams` which is an alternative
  // pattern to development because there are 2 parts:
  // (a) describing the computation housed in `f` i.e. declaration-site
  // (b) deciding where the call-site is, in this case i have chosen to execute
  //     or rather evaluate it here (conforming to parlance)
  //
  def inputOutputStreams2(implicit concurrent : Concurrent[IO]) : Reader[File, Reader[File, Reader[Semaphore[IO], Resource[IO , (FileInputStream, FileOutputStream)]]]] =
    Reader{ (in: File) => 
      Reader{ (out: File) =>
        Reader { (guard: Semaphore[IO]) =>
          val f =
          IO(guard) >>=
            ((sem: Semaphore[IO]) => IO((inputStream(in, sem), sem))) >>=
              ((iFs: (Resource[IO, FileInputStream], Semaphore[IO])) => IO((iFs._1, outputStream(out, iFs._2))))

          for {
            p <- Monad[Id].pure(f.unsafeRunSync)
            l <- p._1
            r <- p._2
          } yield (l, r)
        }
      }
    }

  def transfer(origin: InputStream, destination: OutputStream) : IO[Long] = ???

  def copy(origin: File, destination: File)(implicit c: Concurrent[IO]) : IO[Long] = {
    for {
      guard <- Semaphore[IO](1)
      count <- inputOutputStreams(origin, destination, guard).use{ case (in,out) =>
        guard.withPermit(transmit(in,out,Array.ofDim[Byte](1024),0L))}
    } yield count
  }

  //  leveraging `inputOutputStreams2`
  def copy2(origin: File, destination: File)(implicit c: Concurrent[IO]) : IO[Long] = {
    for {
      guard <- Semaphore[IO](1)
      count <- inputOutputStreams2(c)(origin)(destination)(guard).use{ case (in,out) =>
        guard.withPermit(transfer(in,out))}
    } yield count
  }

  // `transmit` is literally describing a computation that acts on "origin" and
  // "destination" w/o involving any other construct.
  def transmit[F[_]:Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long) : F[Long] =
    for {
      amount <- Sync[F].delay(origin.read(buffer, 0, buffer.size))
      count  <- if (amount > -1) Sync[F].delay(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc+amount) else Sync[F].pure(acc)
    } yield count

}


object FileCopy extends IOApp with IOFunctions {

  override def run(args: List[String]) : IO[ExitCode] =
    for {
      _ <- if (args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files")) else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- copy(orig, dest)
      _     <- IO(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield ExitCode.Success
}

object EchoServer extends IOApp {
  
  import java.io._
  import java.net._
  import ExitCase._
  import cats.effect.syntax.all._

  def echoProtocol[F[_]:Sync](clientSock: Socket) : F[Unit] = {

    // what just happened?
    // `loop` describes an interaction between 2 IO resources, namely the
    // "reader" and "writer".
    def loop(reader: BufferedReader, writer: BufferedWriter) : F[Unit] = for {
      line <- Sync[F].delay(reader.readLine())
      _    <- line match {
        case "" => Sync[F].unit // empty line, we're done
        case _  => Sync[F].delay{writer.write(line); writer.newLine(); writer.flush()} >> loop(reader,writer)
      }
    } yield()

    // define a acquire-release for acquiring an IO-reader
    def reader(clientSock: Socket) : Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(new BufferedReader(new InputStreamReader(clientSock.getInputStream())))
      } { reader =>
          Sync[F].delay(reader.close()).handleErrorWith(_ => Sync[F].unit)
      }
    // define a acquire-release for acquiring an IO-writer
    def writer(clientSock: Socket) : Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay(new BufferedWriter(new PrintWriter(clientSock.getOutputStream())))
      } { writer =>
          Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def readerWriter(clientSock: Socket) : Resource[F, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSock)
        writer <- writer(clientSock)
      } yield (reader, writer)

    // basically, the 2 IO monads are defined as resources and then we use them
    // by hooking in the "how" via `loop`.
    readerWriter(clientSock).use{ case (reader,writer) =>
      loop(reader, writer)
    }
  }


  def serve[F[_]:Concurrent](serverSock: ServerSocket) : F[Unit] = {
    def close(socket:Socket) : F[Unit] = Sync[F].delay(socket.close).handleErrorWith(_ => Sync[F].unit)

    for {
      _ <- Sync[F].delay(serverSock.accept)
                  .bracketCase { socket => // you need to include this import : "import cats.effect.syntax.all._"
                    echoProtocol(socket).guarantee(close(socket)).start
                  }{ (socket, exit) => exit match {
                    case Completed => Sync[F].unit
                    case Error(_) | Canceled => close(socket)
                  }}
      _ <- serve(serverSock)
    } yield ()
  }

  def run(args: List[String]) : IO[ExitCode] = {
    def close[F[_]:Sync](socket: ServerSocket) : F[Unit] =
      Sync[F].delay(socket.close).handleErrorWith(_ => Sync[F].unit)

    IO(new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)))
      .bracket{
        serverSocket => serve[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } {
        serverSocket => close[IO](serverSocket) >> IO(println("server finished"))
      }
  }


}
