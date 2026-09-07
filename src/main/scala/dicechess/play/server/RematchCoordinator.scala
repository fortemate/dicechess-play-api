package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Console, Supervisor}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.store.*
import scala.concurrent.duration.*

/** Durable state is authoritative. The bounded-lived worker set only coalesces publication work on this node. */
final class RematchCoordinator private (
    registry: GameRegistry,
    pg: PgGameStore,
    pending: Ref[IO, Set[GameId]],
    supervisor: Supervisor[IO]
):
  /** A response lost at the transaction boundary must not leave accepted work dependent on another HTTP read. */
  private val recoverAccepted: IO[Unit] =
    def page(after: Option[GameId]): IO[Unit] = pg.rematchCommands.pendingCreation(after, 100).flatMap { rows =>
      rows.traverse_ {
        case Right(s)    => successor(s).void
        case Left(error) =>
          Console[IO].errorln(
            s"[play][rematch] skipped corrupt source ${error.rowId.value}: ${RematchFailure.describe(error)}"
          )
      } *> rows.lastOption.traverse_(s => page(Some(s.fold(_.rowId, _.sourceId))))
    }
    (page(None).handleErrorWith(error =>
      Console[IO].errorln(s"[play][rematch] accepted-work scan will retry: ${RematchFailure.describe(error)}")
    ) *>
      IO.sleep(1.second)).foreverM

  def readable(id: GameId): IO[Boolean] =
    registry.get(id).flatMap {
      case Some(_) => IO.pure(true)
      case None    => pg.archiveFor(id).map(_.isDefined)
    }

  /** Never expose a committed successor until GET state or history can actually read it. */
  def successor(s: RematchSession): IO[Option[RematchSuccessor]] =
    s.successorId
      .traverse(id => readable(id).ifM(pg.rematches.successor(id), IO.pure(None)))
      .map(_.flatten)
      .flatTap(game => ensureCreation(s).whenA(game.isEmpty))

  def ensureCreation(s: RematchSession): IO[Unit] =
    if s.phase != RematchPhase.Starting && s.phase != RematchPhase.Matched then IO.unit
    else
      IO.uncancelable { _ =>
        pending.modify(ids => (ids + s.sourceId, !ids(s.sourceId))).flatMap { fresh =>
          val run = registry.rematches
            .fold(IO.raiseError[Unit](new IllegalStateException("rematch service unavailable"))) { service =>
              service.create(s.sourceId).flatMap {
                case Right(_) => IO.unit
                case Left(_)  =>
                  pg.rematches.session(s.sourceId).flatMap {
                    case Some(current) if current.phase == RematchPhase.Starting =>
                      pg.rematches
                        .advance(s.sourceId, current.version, RematchChange.Close(RematchCloseReason.TechnicalFailure))
                        .void
                    case _ => IO.unit
                  }
              }
            }
            .handleErrorWith(error =>
              Console[IO].errorln(
                s"[play][rematch] publication pending for ${s.sourceId.value}: ${RematchFailure.describe(error)}"
              )
            )
            .guarantee(pending.update(_ - s.sourceId))
          supervisor
            .supervise(run)
            .void
            .handleErrorWith(e => pending.update(_ - s.sourceId) *> IO.raiseError(e))
            .whenA(fresh)
        }
      }

object RematchCoordinator:
  def resource(registry: GameRegistry, pg: PgGameStore): Resource[IO, RematchCoordinator] =
    for
      supervisor <- Supervisor[IO]
      pending    <- Resource.eval(Ref.of[IO, Set[GameId]](Set.empty))
      coordinator = new RematchCoordinator(registry, pg, pending, supervisor)
      _ <- coordinator.recoverAccepted.background
    yield coordinator
