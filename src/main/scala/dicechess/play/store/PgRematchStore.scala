package dicechess.play.store

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.game.{EngineOps, GameRoom}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*

import java.sql.SQLException
import java.time.Instant
import scala.concurrent.duration.*

/** One locked source row serializes contenders across processes, not just inside a coordinator's mutex. */
final private[store] class PgRematchStore(xa: Transactor[IO]) extends RematchStore with RematchCommands:
  import PgRematchStore.*

  def pendingCreation(after: Option[GameId], limit: Int): IO[List[Either[CorruptRematchRecord, RematchSession]]] =
    (sessionColumns ++ fr"""WHERE (phase = 'starting' OR (phase = 'matched' AND successor_id IN
      (SELECT g.game_id FROM play.rematch_successors g WHERE g.startup_phase <> 'active'
       AND NOT EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.game_id))))""" ++
      after.fold(Fragment.empty)(id => fr"AND source_game_id > ${id.value}::uuid") ++
      fr"ORDER BY source_game_id LIMIT ${limit.max(1).min(100)}")
      .query[SessionRow]
      .to[List]
      .map(_.map(decodeSession))
      .transact(xa)
      .timeout(Timeout)

  def current(sourceId: GameId): IO[RematchRead] =
    (for
      state <- readSession(sourceId, lock = true)
      now   <- clock
      fresh <- state.traverse(expire(_, now))
    yield RematchRead(fresh, now)).transact(xa).timeout(Timeout)

  def command(
      sourceId: GameId,
      seat: Seat,
      requestId: java.util.UUID,
      action: RematchAction
  ): IO[RematchCommandResult] =
    (for
      state  <- readSession(sourceId, lock = true)
      now    <- clock
      fresh  <- state.traverse(expire(_, now))
      result <- fresh match
        case None => RematchCommandResult(RematchRead(None, now), Some("game_not_found")).pure[ConnectionIO]
        case Some(s) if !s.source.players.contains(seat) =>
          RematchCommandResult(RematchRead(Some(s), now), Some("not_participant")).pure[ConnectionIO]
        case Some(s) =>
          for
            // Opportunistic per-source cleanup bounds recognition; permanent source/link records are never deleted.
            _ <- sql"""DELETE FROM play.rematch_commands WHERE source_game_id = ${sourceId.value}::uuid
                       AND recorded_at < ${now.minusSeconds(86400)}""".update.run
            receipt <- sql"""SELECT action, error_code FROM play.rematch_commands
                       WHERE source_game_id = ${sourceId.value}::uuid AND seat = ${seat.toString}
                       AND request_id = $requestId""".query[(String, Option[String])].option
            outcome <- receipt match
              case Some((prior, error)) =>
                RematchCommandResult(
                  RematchRead(Some(s), now),
                  if prior == action.wire then error else Some("request_id_conflict")
                ).pure[ConnectionIO]
              case None =>
                val transition = RematchCommands.transition(s, seat, action, now)
                val next       = transition.toOption.filter(_ != s).map(_.copy(version = s.version + 1)).getOrElse(s)
                val error      = transition.left.toOption
                for
                  _ <- writeProgress(next).whenA(next != s)
                  _ <-
                    sql"""INSERT INTO play.rematch_commands (source_game_id, seat, request_id, action, error_code, recorded_at)
                        VALUES (${sourceId.value}::uuid, ${seat.toString}, $requestId, ${action.wire}, $error, $now)""".update.run
                yield RematchCommandResult(RematchRead(Some(next), now), error)
          yield outcome
    yield result).transact(xa).timeout(Timeout)

  def session(sourceId: GameId): IO[Option[RematchSession]] =
    readSession(sourceId, lock = false).transact(xa).timeout(Timeout)

  def successor(gameId: GameId): IO[Option[RematchSuccessor]] =
    readSuccessor(gameId).transact(xa).timeout(Timeout)

  def advance(sourceId: GameId, expectedVersion: Long, change: RematchChange): IO[RematchWrite] =
    (for
      current <- readSession(sourceId, lock = true)
      now     <- clock
      result  <- current match
        case None => RematchWrite.Missing.pure[ConnectionIO]
        case Some(s)
            if s.version != expectedVersion || s.phase == RematchPhase.Closed ||
              s.phase == RematchPhase.Matched =>
          RematchWrite.Conflict(s).pure[ConnectionIO]
        case Some(s) =>
          val next =
            if (s.phase == RematchPhase.Available || s.phase == RematchPhase.Offered) && !now.isBefore(s.deadlineAt)
            then Some(s.copy(phase = RematchPhase.Closed, closedReason = Some(RematchCloseReason.Expired)))
            else
              change match
                case RematchChange.Offer(by) if s.phase == RematchPhase.Available && by.side.isDefined =>
                  Some(
                    s.copy(
                      phase = RematchPhase.Offered,
                      consents = Set(by),
                      offeredBy = Some(by),
                      deadlineAt = now.plusSeconds(WindowSeconds)
                    )
                  )
                case RematchChange.Accept(by)
                    if s.phase == RematchPhase.Offered &&
                      s.offeredBy.flatMap(_.opponent).contains(by) =>
                  Some(s.copy(phase = RematchPhase.Starting, consents = Seats))
                case RematchChange.Close(reason) if canClose(s.phase, reason) =>
                  Some(s.copy(phase = RematchPhase.Closed, closedReason = Some(reason)))
                case _ => None
          next match
            case None    => RematchWrite.Conflict(s).pure[ConnectionIO]
            case Some(n) =>
              val updated = n.copy(version = s.version + 1)
              writeProgress(updated).as(RematchWrite.Applied(updated))
    yield result).transact(xa).timeout(Timeout)

  def commitSuccessor(
      sourceId: GameId,
      expectedVersion: Long,
      gameId: GameId,
      initialSnapshot: GameSnapshot
  ): IO[RematchCommit] =
    (for
      current <- readSession(sourceId, lock = true)
      result  <- current match
        case Some(s) if s.successorId.isDefined =>
          readSuccessor(s.successorId.get).flatMap:
            case Some(game) => RematchCommit.Existing(game).pure[ConnectionIO]
            case None       =>
              new IllegalStateException("rematch successor invariant violated").raiseError[ConnectionIO, RematchCommit]
        case Some(s)
            if s.version == expectedVersion && s.phase == RematchPhase.Starting &&
              s.consents == Seats && gameId != sourceId && gameId != s.rootId && validInitial(
                s.source,
                initialSnapshot
              ) =>
          unusedGameId(gameId).ifM(
            insertSuccessor(s, gameId, initialSnapshot),
            RematchCommit.Rejected.pure[ConnectionIO]
          )
        case _ => RematchCommit.Rejected.pure[ConnectionIO]
    yield result)
      .transact(xa)
      .timeout(Timeout)
      .recover { case e: SQLException if e.getSQLState == "23505" => RematchCommit.Rejected }

  def pendingStartup(after: Option[GameId], limit: Int): IO[List[RematchSuccessor]] =
    pendingStartupRecords(after, limit).flatMap(_.sequence.liftTo[IO])

  override def pendingStartupRecords(
      after: Option[GameId],
      limit: Int
  ): IO[List[Either[CorruptRematchRecord, RematchSuccessor]]] =
    val cursor = after.fold("00000000-0000-0000-0000-000000000000")(_.value)
    (successorColumns ++ fr"""WHERE startup_phase <> 'active' AND (startup_phase = 'awaiting_joins' OR
          (startup_phase = 'aborted' AND EXISTS
            (SELECT 1 FROM play.games g WHERE g.id = game_id AND g.status = 'active')))
        AND game_id > $cursor::uuid ORDER BY game_id LIMIT ${limit.max(0).min(500).toLong}""")
      .query[SuccessorRow]
      .to[List]
      .map(_.map(decodeSuccessor))
      .transact(xa)
      .timeout(Timeout)

  def updateStartup(gameId: GameId, expectedVersion: Long, next: RematchStartup): IO[Boolean] =
    (for
      current <- (successorColumns ++ fr"WHERE game_id = ${gameId.value}::uuid FOR UPDATE")
        .query[SuccessorRow]
        .option
        .flatMap(_.traverse(decodeSuccessor).liftTo[ConnectionIO])
      now    <- clock
      result <- current match
        case Some(g)
            if g.startupVersion == expectedVersion && g.startup.phase == RematchStartupPhase.AwaitingJoins &&
              g.startup.joined.subsetOf(next.joined) && next.joined.subsetOf(Seats) &&
              (next.phase == RematchStartupPhase.Aborted || now.isBefore(g.joinDeadlineAt)) &&
              (if next.phase == RematchStartupPhase.Active then
                 next.joined == Seats &&
                 next.activatedAt
                   .exists(at => !at.isBefore(g.committedAt) && at.isBefore(g.joinDeadlineAt) && !at.isAfter(now))
               else next.activatedAt.isEmpty) =>
          sql"""UPDATE play.rematch_successors SET startup_phase = ${next.phase.stored},
                startup_version = startup_version + 1, joined_white = ${next.joined(Seat.White)},
                joined_black = ${next.joined(Seat.Black)}, activated_at = ${next.activatedAt}
                WHERE game_id = ${gameId.value}::uuid""".update.run.map(_ == 1)
        case _ => false.pure[ConnectionIO]
    yield result).transact(xa).timeout(Timeout)

  def closeUncommittedOnRestart: IO[Int] =
    sql"""UPDATE play.rematch_sessions SET phase = 'closed', closed_reason = 'restart', version = version + 1
          WHERE phase IN ('offered', 'starting') AND successor_id IS NULL""".update.run.transact(xa).timeout(Timeout)

private[store] object PgRematchStore:
  private val WindowSeconds                = 15L
  private val Timeout                      = 5.seconds
  private val Seats                        = Set(Seat.White, Seat.Black)
  private def clock: ConnectionIO[Instant] = sql"SELECT clock_timestamp()".query[Instant].unique

  private type SessionRow = (
      String,
      String,
      Json,
      Instant,
      String,
      Long,
      Boolean,
      Boolean,
      Option[String],
      Instant,
      Option[String],
      Option[String]
  )
  private type SuccessorRow = (String, String, Json, Instant, Instant, Long, String, Boolean, Boolean, Option[Instant])

  private val sessionColumns = fr"""SELECT source_game_id::text, root_game_id::text, source, ended_at, phase,
      version, consent_white, consent_black, offered_by, deadline_at, closed_reason, successor_id::text
      FROM play.rematch_sessions"""
  private val successorColumns = fr"""SELECT game_id::text, source_game_id::text, initial_snapshot, committed_at,
      join_deadline_at, startup_version, startup_phase, joined_white, joined_black, activated_at
      FROM play.rematch_successors"""

  private def decode[A: Decoder](
      json: Json,
      table: String,
      id: String,
      field: String
  ): Either[CorruptRematchRecord, A] =
    json.as[A].leftMap(_ => CorruptRematchRecord(table, GameId(id), field))
  private def required[A](value: Option[A], table: String, id: String, field: String): Either[CorruptRematchRecord, A] =
    value.toRight(CorruptRematchRecord(table, GameId(id), field))
  private def seats(white: Boolean, black: Boolean): Set[Seat] =
    Option.when(white)(Seat.White).toSet ++ Option.when(black)(Seat.Black)

  private def decodeSession(row: SessionRow): Either[CorruptRematchRecord, RematchSession] =
    val (id, root, source, ended, phase, version, white, black, offeredBy, deadline, reason, successor) = row
    val table = "rematch_sessions"
    for
      decodedSource <- decode[RematchSource](source, table, id, "source")
      decodedPhase  <- required(RematchPhase.values.find(_.toString.toLowerCase == phase), table, id, "phase")
      decodedOffer  <- offeredBy.traverse(s => required(Seat.values.find(_.toString == s), table, id, "offered_by"))
      decodedReason <- reason.traverse(r =>
        required(RematchCloseReason.values.find(_.stored == r), table, id, "closed_reason")
      )
    yield RematchSession(
      GameId(id),
      GameId(root),
      decodedSource,
      ended,
      decodedPhase,
      version,
      seats(white, black),
      decodedOffer,
      deadline,
      decodedReason,
      successor.map(GameId(_))
    )

  private def decodeSuccessor(row: SuccessorRow): Either[CorruptRematchRecord, RematchSuccessor] =
    val (id, source, snapshot, committed, deadline, version, phase, white, black, activated) = row
    val table                                                                                = "rematch_successors"
    for
      decodedSnapshot <- decode[GameSnapshot](snapshot, table, id, "initial_snapshot")
      decodedPhase    <- required(RematchStartupPhase.values.find(_.stored == phase), table, id, "startup_phase")
    yield RematchSuccessor(
      GameId(id),
      GameId(source),
      decodedSnapshot,
      committed,
      deadline,
      version,
      RematchStartup(decodedPhase, seats(white, black), activated)
    )

  private def readSession(id: GameId, lock: Boolean): ConnectionIO[Option[RematchSession]] =
    (sessionColumns ++ fr"WHERE source_game_id = ${id.value}::uuid" ++ (if lock then fr"FOR UPDATE"
                                                                        else Fragment.empty))
      .query[SessionRow]
      .option
      .flatMap(_.traverse(decodeSession).liftTo[ConnectionIO])
  private def readSuccessor(id: GameId): ConnectionIO[Option[RematchSuccessor]] =
    (successorColumns ++ fr"WHERE game_id = ${id.value}::uuid")
      .query[SuccessorRow]
      .option
      .flatMap(_.traverse(decodeSuccessor).liftTo[ConnectionIO])

  private def expire(s: RematchSession, now: Instant): ConnectionIO[RematchSession] =
    if (s.phase == RematchPhase.Available || s.phase == RematchPhase.Offered) && !now.isBefore(s.deadlineAt) then
      val next =
        s.copy(phase = RematchPhase.Closed, closedReason = Some(RematchCloseReason.Expired), version = s.version + 1)
      writeProgress(next).as(next)
    else s.pure[ConnectionIO]

  private def writeProgress(s: RematchSession): ConnectionIO[Unit] =
    sql"""UPDATE play.rematch_sessions SET phase = ${s.phase.toString.toLowerCase}, version = ${s.version},
          consent_white = ${s.consents(Seat.White)}, consent_black = ${s.consents(Seat.Black)},
          offered_by = ${s.offeredBy.map(_.toString)}, deadline_at = ${s.deadlineAt},
          closed_reason = ${s.closedReason.map(_.stored)}, successor_id = ${s.successorId.map(_.value)}::uuid
          WHERE source_game_id = ${s.sourceId.value}::uuid""".update.run.void

  /** Called inside the existing terminal save transaction, after the immutable result projection was inserted. */
  def captureSource(id: GameId, snapshot: GameSnapshot): ConnectionIO[Unit] =
    RematchSource.capture(snapshot).traverse_ { captured =>
      for
        result <- sql"""SELECT finished_at, white_external_id, black_external_id FROM play.game_results
                         WHERE game_id = ${id.value}::uuid""".query[(Instant, String, String)].unique
        (endedAt, white, black) = result
        root <- sql"""SELECT s.source_game_id::text, s.root_game_id::text, s.source FROM play.rematch_successors g
                      JOIN play.rematch_sessions s ON s.source_game_id = g.source_game_id
                      WHERE g.game_id = ${id.value}::uuid""".query[(String, String, Json)].option
        rootSource <- root
          .traverse(r => decode[RematchSource](r._3, "rematch_sessions", r._1, "source"))
          .liftTo[ConnectionIO]
        rootId = root.fold(id.value)(_._2)
        source = captured.copy(conditions = rootSource.fold(captured.conditions)(_.conditions))
        // Do not attribute a later rewritten terminal snapshot to different participants than the first result.
        _ <- sql"""INSERT INTO play.rematch_sessions (source_game_id, root_game_id, source, ended_at, deadline_at)
                   VALUES (${id.value}::uuid, $rootId::uuid, ${source.asJson}, $endedAt,
                           ${endedAt.plusSeconds(
            WindowSeconds
          )}) ON CONFLICT (source_game_id) DO NOTHING""".update.run.void
          .whenA(source.players(Seat.White).externalId == white && source.players(Seat.Black).externalId == black)
      yield ()
    }

  private def unusedGameId(id: GameId): ConnectionIO[Boolean] =
    sql"""SELECT NOT (EXISTS (SELECT 1 FROM play.games WHERE id = ${id.value}::uuid)
        OR EXISTS (SELECT 1 FROM play.game_results WHERE game_id = ${id.value}::uuid)
        OR EXISTS (SELECT 1 FROM play.game_archive WHERE game_id = ${id.value}::uuid)
        OR EXISTS (SELECT 1 FROM play.rematch_sessions WHERE source_game_id = ${id.value}::uuid)
        OR EXISTS (SELECT 1 FROM play.rematch_successors WHERE game_id = ${id.value}::uuid))""".query[Boolean].unique

  private def insertSuccessor(s: RematchSession, gameId: GameId, initial: GameSnapshot): ConnectionIO[RematchCommit] =
    for
      now <- clock
      snapshot = initial.copy(createdAtEpochMs = Some(now.toEpochMilli))
      deadline = now.plusSeconds(WindowSeconds)
      // Strict insert still fences two unrelated sources racing for the same candidate ID.
      _ <- sql"""INSERT INTO play.games (id, status, snapshot, origin)
                 VALUES (${gameId.value}::uuid, 'active', ${snapshot.asJson}, 'direct')""".update.run
      _ <- sql"""INSERT INTO play.rematch_successors
                 (game_id, source_game_id, initial_snapshot, committed_at, join_deadline_at)
                 VALUES (${gameId.value}::uuid, ${s.sourceId.value}::uuid, ${snapshot.asJson}, $now, $deadline)""".update.run
      _ <- writeProgress(s.copy(phase = RematchPhase.Matched, version = s.version + 1, successorId = Some(gameId)))
    yield RematchCommit.Committed(
      RematchSuccessor(
        gameId,
        s.sourceId,
        snapshot,
        now,
        deadline,
        0,
        RematchStartup(RematchStartupPhase.AwaitingJoins, Set.empty, None)
      )
    )

  private def canClose(phase: RematchPhase, reason: RematchCloseReason): Boolean =
    reason match
      case RematchCloseReason.TechnicalFailure | RematchCloseReason.Restart => true
      case RematchCloseReason.Cancelled | RematchCloseReason.Declined       => phase == RematchPhase.Offered
      // Expiry is decided from the database clock, never a caller's premature close command.
      case RematchCloseReason.Expired => false

  private def validInitial(source: RematchSource, snapshot: GameSnapshot): Boolean =
    val tokens = snapshot.seatTokens
    snapshot.status == GameStatus.Active && snapshot.version == 0 && snapshot.ply == 0 && !snapshot.started &&
    !snapshot.pending && snapshot.dfen == EngineOps.InitialDfen && snapshot.turns.isEmpty && snapshot.lastRoll.isEmpty &&
    snapshot.clientSeeds.isEmpty && snapshot.pendingDrawOffer.isEmpty && snapshot.lastDrawOfferer.isEmpty &&
    snapshot.players.keySet == Seats && snapshot.players.values.toSet == source.players.values.toSet &&
    tokens.keySet == Seats && tokens.values.forall(_.nonEmpty) && tokens.values.toSet.size == 2 &&
    snapshot.serverSeed.matches("[0-9a-f]{64}") && snapshot.origin.contains(GameOrigin.Direct) &&
    snapshot.ladder.contains(false) && snapshot.rated.contains(source.conditions.rated) &&
    snapshot.timeControl == source.conditions.timeControl &&
    snapshot.remainingMs == GameRoom.initialRemaining(snapshot.timeControl, Seats).view.mapValues(_.toMillis).toMap
