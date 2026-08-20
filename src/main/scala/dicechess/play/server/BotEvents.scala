package dicechess.play.server

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import dicechess.play.core.{BotEvent, Principal}
import fs2.Stream

/** Per-bot event bus for the Bot API account stream. Each `/bot/stream/event` connection owns a bounded queue; a
  * publisher fans out with a non-blocking `tryOffer` and drops a subscriber that falls behind, never blocking the
  * publisher — the same discipline as the game room's fan-out. Live-only: a subscriber sees events published after it
  * connects, and its stream ends on disconnect.
  */
final class BotEvents private (subscribers: Ref[IO, Map[Long, BotEvents.Sub]], nextId: Ref[IO, Long]):
  import BotEvents.*

  /** The live event feed addressed to `principal`. */
  def stream(principal: Principal): Stream[IO, BotEvent] =
    Stream
      .resource(Resource.make(register(principal))(sub => unregister(sub.id)))
      .flatMap(sub => Stream.fromQueueUnterminated(sub.queue).interruptWhen(sub.dropped.get.attempt))

  /** Whether `principal` currently holds at least one live account stream — advisory presence (a bot may also be a
    * poller that never streams; absence here is not absence from the platform).
    */
  def online(principal: Principal): IO[Boolean] =
    subscribers.get.map(_.values.exists(_.principal == principal))

  /** Deliver an event to every live stream addressed to `target`; a laggard is dropped, never blocking the caller. */
  def publish(target: Principal, event: BotEvent): IO[Unit] =
    subscribers.get.flatMap: subs =>
      subs.values.filter(_.principal == target).toList.traverse_ { sub =>
        sub.queue.tryOffer(event).flatMap {
          case true  => IO.unit
          case false => sub.dropped.complete(()).attempt.void
        }
      }

  private def register(principal: Principal): IO[Sub] =
    for
      id      <- nextId.getAndUpdate(_ + 1)
      queue   <- Queue.bounded[IO, BotEvent](EventBuffer)
      dropped <- Deferred[IO, Unit]
      sub = Sub(id, principal, queue, dropped)
      _ <- subscribers.update(_.updated(id, sub))
    yield sub

  private def unregister(id: Long): IO[Unit] = subscribers.update(_.removed(id))

object BotEvents:

  private val EventBuffer = 64

  final private case class Sub(id: Long, principal: Principal, queue: Queue[IO, BotEvent], dropped: Deferred[IO, Unit])

  def create: IO[BotEvents] =
    (Ref.of[IO, Map[Long, Sub]](Map.empty), Ref.of[IO, Long](0L)).mapN(new BotEvents(_, _))
