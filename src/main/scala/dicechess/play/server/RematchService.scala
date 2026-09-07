package dicechess.play.server

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Console, Mutex}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.{EngineOps, GameRoom}
import dicechess.play.store.*

import java.security.SecureRandom
import java.time.Instant
import scala.concurrent.duration.*

/** Server-only creation seam. Caller authorization and the HTTP coordinator belong to #129. */
final class RematchService private (
    registry: GameRegistry,
    pg: PgGameStore,
    records: RematchStore,
    dice: () => IO[DiceSource],
    swapColours: IO[Boolean],
    now: IO[Instant],
    creationLock: Mutex[IO],
    registrations: Ref[IO, Map[GameId, Deferred[IO, Either[Throwable, RematchSuccessor]]]]
):
  /** Two persisted consents authorize creation. Only a readable live/history successor is returned. */
  def create(sourceId: GameId): IO[Either[String, RematchSuccessor]] =
    IO.uncancelable { poll =>
      creationLock.lock.surround(prepare(sourceId)).flatMap {
        case Left(reason) => IO.pure(Left(reason))
        case Right(game)  =>
          singleFlight(game).flatMap(wait => poll(wait).map(Right(_)))
      }
    }

  private def prepare(id: GameId): IO[Either[String, RematchSuccessor]] =
    records.session(id).flatMap {
      case None                               => IO.pure(Left("game_not_found"))
      case Some(s) if s.successorId.isDefined =>
        records.successor(s.successorId.get).map(_.toRight("temporarily_unavailable"))
      case Some(s) if s.phase != RematchPhase.Starting || s.consents != Set(Seat.White, Seat.Black) =>
        IO.pure(Left("invalid_transition"))
      case Some(s) if !s.source.admissible =>
        records
          .advance(id, s.version, RematchChange.Close(RematchCloseReason.TechnicalFailure))
          .as(Left("settings_unavailable"))
      case Some(s) =>
        val commit = for
          swap   <- swapColours
          random <- dice()
          next   <- GameId.random
          players = if swap then s.source.players.map((seat, p) => seat.opponent.get -> p) else s.source.players
          tokens <- GameRoom.mintTokens(players.keys)
          snapshot = GameSnapshot(
            0,
            EngineOps.InitialDfen,
            players,
            tokens,
            random.reveal,
            Map.empty,
            false,
            0,
            false,
            GameStatus.Active,
            s.source.conditions.timeControl,
            GameRoom.initialRemaining(s.source.conditions.timeControl, players.keys).view.mapValues(_.toMillis).toMap,
            Nil,
            Vector.empty,
            rated = Some(s.source.conditions.rated),
            ladder = Some(false),
            origin = Some(GameOrigin.Direct)
          )
          result <- records.commitSuccessor(id, s.version, next, snapshot)
          game   <- result match
            case RematchCommit.Committed(g) => IO.pure(Right(g))
            case RematchCommit.Existing(g)  => IO.pure(Right(g))
            case RematchCommit.Rejected     => IO.pure(Left("invalid_transition"))
        yield game
        val admitted = registry.attachedAdmissionGuard match
          case None        => commit
          case Some(guard) =>
            guard
              .admit[RematchSuccessor](s.source.players.values.toList, GameOrigin.Direct.admissionPurpose) { _ =>
                commit.map(_.map(g => g.gameId -> g))
              }
              .map(_.bimap(_.message, _._2))
        admitted.handleErrorWith { error =>
          // A failed response is not proof of rollback. Recover the committed draw before considering any retry.
          records.session(id).flatMap {
            case Some(current) if current.successorId.isDefined =>
              records.successor(current.successorId.get).flatMap {
                case Some(g) => IO.pure(Right(g))
                case None    => IO.raiseError(error)
              }
            case _ =>
              records.advance(id, s.version, RematchChange.Close(RematchCloseReason.TechnicalFailure)) *> IO.raiseError(
                error
              )
          }
        }
    }

  private def singleFlight(game: RematchSuccessor): IO[IO[RematchSuccessor]] =
    Deferred[IO, Either[Throwable, RematchSuccessor]].flatMap { fresh =>
      registrations
        .modify { pending =>
          pending.get(game.gameId) match
            case Some(existing) => (pending, Left(existing))
            case None           => (pending.updated(game.gameId, fresh), Right(fresh))
        }
        .flatMap {
          case Left(existing) => IO.pure(existing.get.rethrow)
          case Right(result)  =>
            registerUntilReadable(game, None).attempt
              .flatMap(result.complete)
              .void
              .guarantee(registrations.update(_ - game.gameId))
              .start
              .as(result.get.rethrow)
        }
    }

  private def registerUntilReadable(game: RematchSuccessor, built: Option[GameRoom]): IO[RematchSuccessor] =
    registry.get(game.gameId).flatMap {
      case Some(_) => IO.pure(game)
      case None    =>
        pg.archiveFor(game.gameId).flatMap {
          case Some(_) => IO.pure(game)
          case None    =>
            now.flatMap { at =>
              if !at.isBefore(game.joinDeadlineAt) then
                retryAbort(built.fold(technicalAbort(game))(_.abort), game.gameId).as(game)
              else
                val room = built.fold(registry.buildRematch(game, pg, now))(IO.pure)
                room.attempt.flatMap {
                  case Left(_)  => IO.sleep(100.millis) *> registerUntilReadable(game, None)
                  case Right(r) =>
                    r.hasEnded.flatMap {
                      case true  => r.result.as(game)
                      case false =>
                        now.flatMap { current =>
                          if !current.isBefore(game.joinDeadlineAt) then retryAbort(r.abort, game.gameId).as(game)
                          else
                            registry
                              .publishRematch(game.gameId, r)
                              .timeout(java.time.Duration.between(current, game.joinDeadlineAt).toNanos.nanos)
                              .attempt
                              .flatMap {
                                case Right(_) => IO.pure(game)
                                case Left(_)  => IO.sleep(100.millis) *> registerUntilReadable(game, Some(r))
                              }
                        }
                    }
                }
            }
        }
    }

  private def retryAbort(action: IO[Unit], id: GameId): IO[Unit] =
    action.handleErrorWith {
      case error: CorruptRematchRecord      => IO.raiseError(error)
      case error: RematchTransitionRejected => IO.raiseError(error)
      case _                                =>
        Console[IO].errorln(s"[play][rematch] game ${id.value} terminal save unavailable; retrying") *>
          IO.sleep(1.second) *> retryAbort(action, id)
    }

  private def technicalAbort(game: RematchSuccessor): IO[Unit] =
    pg.rematchSnapshot(game.gameId).flatMap { current =>
      if current.ended then IO.unit
      else
        val snapshot = current.copy(
          version = current.version + 1,
          pending = false,
          status = GameStatus.Ended(GameOver(GameResult.Draw, Termination.Aborted))
        )
        pg.saveRematch(
          game.gameId,
          snapshot,
          game.startup.copy(phase = RematchStartupPhase.Aborted, activatedAt = None)
        )
    }

  /** Called only at bootstrap, before ordinary registry resume and before accepting transports. */
  def recoverOnRestart: IO[Unit] =
    def failed(id: GameId): IO[Unit] =
      Console[IO].errorln(s"[play][rematch] game ${id.value} could not be recovered; skipped")
    def page(after: Option[GameId]): IO[Unit] = records.pendingStartupRecords(after, 100).flatMap { pending =>
      pending.traverse_ {
        case Left(error) => failed(error.rowId)
        case Right(game) =>
          technicalAbort(game).handleErrorWith {
            case _: CorruptRematchRecord      => failed(game.gameId)
            case _: RematchTransitionRejected => failed(game.gameId)
            case error                        => IO.raiseError(error)
          }
      } *> pending.lastOption.traverse_(last => page(Some(last.fold(_.rowId, _.gameId))))
    }
    records.closeUncommittedOnRestart.void *> page(None)

object RematchService:
  private[server] def create(
      registry: GameRegistry,
      pg: PgGameStore,
      dice: () => IO[DiceSource] = () => DiceSource.newCommitReveal(),
      swapColours: IO[Boolean] = IO.delay(new SecureRandom().nextBoolean()),
      now: IO[Instant] = IO.realTimeInstant,
      records: Option[RematchStore] = None
  ): IO[RematchService] =
    (Mutex[IO], Ref.of[IO, Map[GameId, Deferred[IO, Either[Throwable, RematchSuccessor]]]](Map.empty))
      .mapN((lock, pending) =>
        new RematchService(registry, pg, records.getOrElse(pg.rematches), dice, swapColours, now, lock, pending)
      )
