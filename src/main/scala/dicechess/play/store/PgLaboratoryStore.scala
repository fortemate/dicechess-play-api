package dicechess.play.store

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.core.LaboratoryProtocol.*
import dicechess.play.wire.Codecs.given
import dicechess.play.wire.LaboratoryCodecs.encodeValidated
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.util.ExecutionContexts
import io.circe.Json
import io.circe.syntax.*
import org.flywaydb.core.Flyway

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.duration.*

/** Dedicated database with no analytics outbox, bot, rating, profile or sporting-archive interfaces. */
final class PgLaboratoryStore private (
    private[store] val xa: HikariTransactor[IO],
    retention: FiniteDuration
) extends LaboratoryStore:
  import PgLaboratoryStore.*

  override val durable: Boolean = true

  private def transact[A](action: ConnectionIO[A]): IO[A] = action.transact(xa).timeout(10.seconds)

  private def locked[A](ids: List[GameId])(action: ConnectionIO[A]): ConnectionIO[A] =
    ids.map(_.value).distinct.sorted.traverse_ { id =>
      sql"SELECT 1 FROM pg_advisory_xact_lock(hashtextextended($id, 0))".query[Int].unique.void
    } *> action

  private def read(id: GameId, lock: Boolean = false): ConnectionIO[Option[(GameSnapshot, Instant)]] =
    val query = fr"""SELECT snapshot, version, variant_id, variant_revision, protocol_version, rules_version,
                 dice_algorithm, time_control, expires_at, status
          FROM laboratory.games WHERE game_id = ${id.value}::uuid""" ++
      (if lock then fr" FOR UPDATE" else Fragment.empty)
    query
      .query[Row]
      .option
      .flatMap(_.traverse(decodeRow(_).liftTo[ConnectionIO]))

  override def load(id: GameId): IO[Option[GameSnapshot]] = transact {
    for
      row <- read(id)
      now <- databaseTime
    yield row.filter(_._2.isAfter(now)).map(_._1)
  }

  override def loadActive: IO[List[(GameId, GameSnapshot)]] = transact {
    sql"""SELECT game_id::text, snapshot, version, variant_id, variant_revision, protocol_version,
                 rules_version, dice_algorithm, time_control, expires_at, status
          FROM laboratory.games WHERE status = 'active' AND expires_at > clock_timestamp() ORDER BY game_id"""
      .query[(String, Json, Long, String, Int, Int, String, String, Json, Instant, String)]
      .to[List]
      .flatMap(_.traverse {
        case (id, json, version, variant, revision, protocol, rules, algorithm, control, expiry, status) =>
          decodeRow((json, version, variant, revision, protocol, rules, algorithm, control, expiry, status))
            .map(r => GameId(id) -> r._1)
            .liftTo[ConnectionIO]
      })
  }

  override def save(id: GameId, snapshot: GameSnapshot): IO[Unit] =
    load(id)
      .flatMap {
        case None if snapshot.version != 0 || snapshot.started || snapshot.ended =>
          IO.raiseError(LaboratoryStorageFailure("missing or expired game cannot be resumed"))
        case previous => commit(id, previous.map(_.version), snapshot)
      }
      .flatMap {
        case LaboratoryWrite.Applied | LaboratoryWrite.Unchanged => IO.unit
        case LaboratoryWrite.Conflict => IO.raiseError(LaboratoryStorageFailure("snapshot write conflict"))
      }

  override def commit(id: GameId, expectedVersion: Option[Long], snapshot: GameSnapshot): IO[LaboratoryWrite] =
    prepared(snapshot).liftTo[IO].flatMap { case (json, transcript) =>
      transact(locked(List(id))(persist(id, expectedVersion, snapshot, json, transcript)))
    }

  private def persist(
      id: GameId,
      expectedVersion: Option[Long],
      snapshot: GameSnapshot,
      json: Json,
      transcript: Json
  ): ConnectionIO[LaboratoryWrite] =
    for
      previous <- read(id, lock = true)
      now      <- databaseTime
      result   <- previous match
        case Some((_, expiry)) if !expiry.isAfter(now) => LaboratoryWrite.Conflict.pure[ConnectionIO]
        case Some((old, _)) if old == snapshot         => LaboratoryWrite.Unchanged.pure[ConnectionIO]
        case Some((old, _)) if expectedVersion != Some(old.version) || snapshot.version <= old.version || old.ended =>
          LaboratoryWrite.Conflict.pure[ConnectionIO]
        case Some((old, _)) =>
          LaboratoryStorage
            .validateUpdate(old, snapshot)
            .leftMap(_ => LaboratoryStorageFailure("immutable data or history changed"))
            .liftTo[ConnectionIO] *> write(id, snapshot, json, transcript).as(LaboratoryWrite.Applied)
        case None if expectedVersion.isDefined => LaboratoryWrite.Conflict.pure[ConnectionIO]
        case None                              => write(id, snapshot, json, transcript).as(LaboratoryWrite.Applied)
    yield result

  private def write(id: GameId, snapshot: GameSnapshot, json: Json, transcript: Json): ConnectionIO[Unit] =
    snapshot.lab.toRight(LaboratoryStorageFailure("missing laboratory state")).liftTo[ConnectionIO].flatMap { state =>
      val variant    = snapshot.effectiveVariant
      val status     = if snapshot.ended then "ended" else "active"
      val ttlSeconds = retention.toSeconds
      val game       = sql"""INSERT INTO laboratory.games
          (game_id, variant_id, variant_revision, protocol_version, rules_version, dice_algorithm,
           time_control, version, status, snapshot, expires_at)
          VALUES (${id.value}::uuid, ${variant.id}, ${variant.revision}, ${state.protocolVersion},
            ${state.provenance.rulesVersion}, ${state.provenance.diceAlgorithm}, ${snapshot.timeControl.asJson},
            ${snapshot.version}, $status, $json, now() + $ttlSeconds * interval '1 second')
          ON CONFLICT (game_id) DO UPDATE SET version = EXCLUDED.version, status = EXCLUDED.status,
            snapshot = EXCLUDED.snapshot, updated_at = now()""".update.run.void
      val history = sql"""INSERT INTO laboratory.transcripts (game_id, variant_id, variant_revision, payload)
          VALUES (${id.value}::uuid, ${variant.id}, ${variant.revision}, $transcript)
          ON CONFLICT (game_id) DO UPDATE SET payload = EXCLUDED.payload""".update.run.void
      val terminal = snapshot.status match
        case GameStatus.Active                                => ().pure[ConnectionIO]
        case GameStatus.Ended(GameOver(outcome, termination)) =>
          val result = Option.when(termination != Termination.Aborted)(outcome match
            case GameResult.Draw            => 0
            case GameResult.Win(Side.White) => 1
            case GameResult.Win(Side.Black) => -1)
          sql"""INSERT INTO laboratory.results (game_id, variant_id, variant_revision, result, termination)
              VALUES (${id.value}::uuid, ${variant.id}, ${variant.revision}, $result, ${termination.toString})
              ON CONFLICT (game_id) DO NOTHING""".update.run.void
      game *> history *> terminal
    }

  override def receiptFor(id: GameId, seat: Seat, commandId: String): IO[Option[LaboratoryStorage.AcceptedCommand]] =
    load(id).map(_.flatMap(_.lab).flatMap(_.receipts.find(r => r.seat == seat && r.command.commandId == commandId)))

  override def transcriptFor(id: GameId): IO[Option[LaboratoryTranscriptRecord]] = transact {
    sql"""SELECT t.variant_id, t.variant_revision, t.payload, g.expires_at, g.snapshot, g.status
          FROM laboratory.transcripts t JOIN laboratory.games g USING (game_id)
          WHERE t.game_id = ${id.value}::uuid AND g.expires_at > clock_timestamp()"""
      .query[(String, Int, Json, Instant, Json, String)]
      .option
      .flatMap(_.traverse { case (variant, revision, json, expiry, privateJson, status) =>
        (for
          record   <- LaboratoryTranscript.decode(json)
          snapshot <- privateJson.as[GameSnapshot].leftMap(_ => "invalid private transcript source")
          expected <- LaboratoryStorage.transcript(snapshot)
          _        <- Either.cond(
            VariantIdentity(variant, revision) == TwoPairs &&
              snapshot.ended == (status == "ended") && record.asJson == expected,
            (),
            "transcript disagrees with its private recovery source"
          )
        yield LaboratoryTranscriptRecord(TwoPairs, record.asJson, expiry))
          .leftMap(_ => LaboratoryStorageFailure("corrupt laboratory transcript"))
          .liftTo[ConnectionIO]
      })
  }

  override def resultFor(id: GameId): IO[Option[LaboratoryResult]] = transact {
    sql"""SELECT r.variant_id, r.variant_revision, g.rules_version, g.dice_algorithm, g.time_control,
                 r.result, r.termination, r.finished_at
          FROM laboratory.results r JOIN laboratory.games g USING (game_id)
          WHERE r.game_id = ${id.value}::uuid AND g.expires_at > clock_timestamp()"""
      .query[(String, Int, String, String, Json, Option[Int], String, Instant)]
      .option
      .flatMap(_.traverse { case (variant, revision, rules, algorithm, control, result, termination, finished) =>
        (for
          _      <- Either.cond(VariantIdentity(variant, revision) == TwoPairs, (), "unsupported result variant")
          time   <- control.as[TimeControl].leftMap(_ => "invalid result settings")
          _      <- encodeValidated(LaboratoryStorage.Conditions(TwoPairs, ProtocolVersion, time)).leftMap(_.message)
          reason <- Termination.values.find(_.toString == termination).toRight("invalid result termination")
        yield LaboratoryResult(TwoPairs, Provenance(rules, algorithm), time, result, reason, finished))
          .leftMap(_ => LaboratoryStorageFailure("corrupt laboratory result"))
          .liftTo[ConnectionIO]
      })
  }

  override def successorOf(source: GameId): IO[Option[GameId]] = transact(successor(source))

  private def successor(source: GameId): ConnectionIO[Option[GameId]] =
    sql"""SELECT r.successor_game_id::text, r.conditions FROM laboratory.rematches r
          JOIN laboratory.games g ON g.game_id = r.source_game_id
          WHERE r.source_game_id = ${source.value}::uuid AND g.expires_at > clock_timestamp()"""
      .query[(String, Json)]
      .option
      .flatMap(_.traverse { case (id, json) =>
        for
          conditions <- json
            .as[LaboratoryStorage.Conditions]
            .leftMap(_ => LaboratoryStorageFailure("corrupt laboratory rematch conditions"))
            .liftTo[ConnectionIO]
          origin <- read(source)
          target <- read(GameId(id))
          _      <- Either
            .cond(
              origin.exists(row => LaboratoryStorage.conditions(row._1) == conditions) &&
                target.exists(row => LaboratoryStorage.conditions(row._1) == conditions),
              (),
              LaboratoryStorageFailure("laboratory rematch inheritance disagrees with snapshots")
            )
            .liftTo[ConnectionIO]
        yield GameId(id)
      })

  override def createRematch(source: GameId, candidate: GameId, initial: GameSnapshot): IO[LaboratoryRematchWrite] =
    prepared(initial).liftTo[IO].flatMap { case (json, transcript) =>
      transact(locked(List(source, candidate)) {
        successor(source).flatMap {
          case Some(id) => LaboratoryRematchWrite.Existing(id).pure[ConnectionIO]
          case None     =>
            for
              origin   <- read(source, lock = true)
              occupied <- read(candidate)
              now      <- databaseTime
              result   <- origin match
                case Some((old, expiry))
                    if expiry.isAfter(now) && occupied.isEmpty && source != candidate && validRematch(old, initial) =>
                  write(candidate, initial, json, transcript) *>
                    sql"""INSERT INTO laboratory.rematches
                        (source_game_id, successor_game_id, variant_id, variant_revision, conditions)
                        VALUES (${source.value}::uuid, ${candidate.value}::uuid, ${TwoPairs.id}, ${TwoPairs.revision},
                          ${LaboratoryStorage.conditions(old).asJson})""".update.run
                      .as(LaboratoryRematchWrite.Created(candidate))
                case _ => LaboratoryRematchWrite.Rejected.pure[ConnectionIO]
            yield result
        }
      })
    }

  override def pruneExpired(limit: Int): IO[Int] =
    if limit <= 0 || limit > 1000 then IO.raiseError(LaboratoryStorageFailure("invalid retention batch size"))
    else
      transact {
        sql"""WITH expired AS (
          SELECT game_id FROM laboratory.games WHERE expires_at <= clock_timestamp()
          ORDER BY expires_at, game_id LIMIT $limit FOR UPDATE SKIP LOCKED
        ) DELETE FROM laboratory.games g USING expired e WHERE g.game_id = e.game_id""".update.run
      }

object PgLaboratoryStore:
  final case class Config(
      url: String,
      user: String,
      password: String,
      poolSize: Int = 2,
      retention: FiniteDuration = 30.days
  ):
    override def toString: String =
      s"LaboratoryDatabaseConfig(poolSize=$poolSize,retention=$retention,<private connection>)"

  private type Row = (Json, Long, String, Int, Int, String, String, Json, Instant, String)
  private val databaseTime: ConnectionIO[Instant] = sql"SELECT clock_timestamp()".query[Instant].unique

  private def decodeRow(row: Row): Either[LaboratoryStorageFailure, (GameSnapshot, Instant)] =
    val (json, version, variant, revision, protocol, rules, algorithm, control, expiry, status) = row
    (for
      snapshot <- json.as[GameSnapshot].leftMap(_ => "invalid snapshot")
      state    <- snapshot.lab.toRight("missing laboratory state")
      _        <- Either.cond(
        snapshot.effectiveVariant == VariantIdentity(variant, revision) &&
          snapshot.version == version && snapshot.ended == (status == "ended") &&
          state.protocolVersion == protocol && state.provenance == Provenance(
            rules,
            algorithm
          ) &&
          snapshot.timeControl.asJson == control,
        (),
        "projection disagrees with snapshot"
      )
    yield snapshot -> expiry).leftMap(_ => LaboratoryStorageFailure("corrupt laboratory snapshot"))

  private def prepared(snapshot: GameSnapshot): Either[LaboratoryStorageFailure, (Json, Json)] =
    (for
      _          <- Either.cond(snapshot.effectiveVariant == TwoPairs, (), "laboratory store requires explicit variant")
      json       <- encodeValidated(snapshot).leftMap(_.message)
      transcript <- LaboratoryStorage.transcript(snapshot)
    yield json -> transcript).leftMap(_ => LaboratoryStorageFailure("invalid laboratory snapshot"))

  private def validRematch(source: GameSnapshot, initial: GameSnapshot): Boolean =
    val sporting = source.status match
      case GameStatus.Ended(GameOver(_, termination)) => termination != Termination.Aborted
      case _                                          => false
    sporting && source.players.keySet == Set(Seat.White, Seat.Black) &&
    source.players.values.toSet.size == 2 && initial.players.values.toSet == source.players.values.toSet &&
    LaboratoryStorage.conditions(source) == LaboratoryStorage.conditions(initial) &&
    initial.version == 0 && initial.ply == 0 && !initial.started && !initial.pending && initial.turns.isEmpty &&
    initial.lastRoll.isEmpty && initial.clientSeeds.isEmpty && initial.status == GameStatus.Active &&
    initial.lab.exists(s =>
      s.turn.phase == Phase.AwaitingPlayers && s.receipts.isEmpty &&
        s.joined.isEmpty && s.frozenClientSeeds.isEmpty && !s.fairnessRevealed
    ) &&
    initial.serverSeed != source.serverSeed && initial.seatTokens.keySet == Set(Seat.White, Seat.Black) &&
    initial.seatTokens.values.toSet.size == 2 && initial.seatTokens.values.forall(t =>
      !source.seatTokens.values.toSet(t)
    )

  /** Refuse a database containing tables belonging to another service BEFORE creating a schema or Flyway history. */
  def migrate(config: Config): IO[Unit] =
    if config.poolSize <= 0 || config.retention < 1.day || config.retention > 365.days then
      IO.raiseError(LaboratoryStorageFailure("invalid laboratory database configuration"))
    else
      IO.blocking {
        val connection = DriverManager.getConnection(config.url, config.user, config.password)
        try
          val statement = connection.createStatement()
          try
            val rows = statement.executeQuery("""SELECT EXISTS (
              SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'laboratory')
                AND n.nspname NOT LIKE 'pg_%' AND c.relkind IN ('r', 'p', 'v', 'm', 'f'))""")
            try
              rows.next()
              rows.getBoolean(1)
            finally rows.close()
          finally statement.close()
        finally connection.close()
      }.flatMap { occupied =>
        if occupied then IO.raiseError(LaboratoryStorageFailure("database is not dedicated to laboratory storage"))
        else
          IO.blocking {
            Flyway
              .configure()
              .dataSource(config.url, config.user, config.password)
              .locations("classpath:db/laboratory")
              .schemas("laboratory")
              .createSchemas(true)
              .load()
              .migrate()
            ()
          }
      }

  def resource(config: Config): Resource[IO, PgLaboratoryStore] =
    for
      _         <- Resource.eval(migrate(config))
      connectEC <- ExecutionContexts.fixedThreadPool[IO](config.poolSize)
      xa        <- HikariTransactor
        .newHikariTransactor[IO]("org.postgresql.Driver", config.url, config.user, config.password, connectEC)
      _ <- Resource.eval(
        xa.configure(ds =>
          IO.delay {
            ds.setPoolName("laboratory-pool")
            ds.setMaximumPoolSize(config.poolSize)
            ds.setConnectionTimeout(5000)
          }
        )
      )
    yield new PgLaboratoryStore(xa, config.retention)
