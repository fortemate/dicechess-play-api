package dicechess.play.server

import cats.effect.kernel.Outcome
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*

import java.security.SecureRandom
import scala.concurrent.duration.*

/** In-memory pool of open lobby seeks (polling model). A seek is a public game offer anyone can accept; the creator
  * holds a capability `secret` to poll its status and cancel it. Liveness is by TTL: the creator's status poll
  * refreshes the seek, and a background [[sweeper]] drops seeks whose creator has gone quiet — so an abandoned tab
  * never strands a seek.
  *
  * Accepting is atomic (open → claimed) so two accepters can't both seat a game; the game randomly assigns creator and
  * accepter to White/Black (see `accept`), and each side's seat token — plus the seat it names — reaches the right
  * player (the accepter in the accept response, the creator on its next status poll).
  */
final class Lobby private (
    seeks: Ref[IO, Map[String, Lobby.Entry]],
    registry: GameRegistry,
    nextId: Ref[IO, Long],
    ttl: FiniteDuration,
    botTtl: FiniteDuration,
    maxOpenSeeksPerBot: Int,
    admitBoth: (Principal, Principal) => IO[Boolean],
    resolveNicknames: List[String] => IO[Map[String, String]],
    admissionGuard: Option[AdmissionGuard]
):
  import Lobby.*

  /** Post an open seek; returns it plus the creator's capability secret (needed to poll its status / cancel it). The
    * seek carries the creator's public face (`kind`/`name`) so humans can see — and choose — bot opponents. A bot's
    * open seeks are capped (`Left` when spent); guests keep the uncapped 15s-poll semantics.
    *
    * `rated` is silently forced to `false` for an anonymous creator (#279) — the same degrade `GameRegistry.isRated`
    * already applies at game creation, so a guest asking for rated just gets a casual seek rather than a rejection. The
    * seek published afterward is therefore never rated=true with an anonymous creator behind it, which is what lets
    * `accept` reason about the ACCEPTER alone (see there).
    *
    * An uncategorised control (`Unlimited`/`PerMove`) forces it false the same way (#280): there is no scale for a game
    * of unbounded length, so a seek offering one cannot be rated no matter who accepts it. Degrading here rather than
    * only at creation is what keeps the PUBLIC list honest — a browser choosing between seeks reads this flag.
    */
  def create(
      creator: Principal,
      timeControl: TimeControl,
      rated: Boolean
  ): IO[Either[CreateRejected, (Seek, String)]] =
    (nextId.getAndUpdate(_ + 1), randomSecret, IO.monotonic, resolveNicknames(List(creator.externalId)))
      .flatMapN: (n, secret, now, names) =>
        // A registered creator's nickname, so a human browsing the lobby can see WHO is offering rather than only
        // "Anonymous". Resolved once here — a seek lives 15 seconds between polls, so there is nothing to go stale.
        val face        = PublicPlayer.ofExternalId(creator.externalId, names)
        val actualRated = rated && !GameRegistry.isAnonymous(creator) && RatingCategory.of(timeControl).isDefined
        val seek        = Seek(s"seek-$n", timeControl, face.kind, face.name, actualRated)
        seeks.modify: current =>
          val openByCreator = current.values.count(e => e.creator == creator && e.state == EntryState.Open)
          if face.kind == PlayerKind.Bot && openByCreator >= maxOpenSeeksPerBot then
            (current, Left(CreateRejected.TooManyOpenSeeks))
          else (current.updated(seek.id, Entry(seek, creator, secret, EntryState.Open, now)), Right((seek, secret)))

  /** Only seeks still `Open` — claimed/matched ones are hidden from the public list. */
  def list: IO[List[Seek]] =
    seeks.get.map(_.values.collect { case e if e.state == EntryState.Open => e.seek }.toList.sortBy(_.id))

  /** Poll a seek's status with the creator's secret, refreshing its liveness. `None` = unknown seek or wrong secret. */
  def status(id: String, secret: String): IO[Option[SeekStatus]] =
    IO.monotonic.flatMap: now =>
      seeks.modify: current =>
        current.get(id) match
          case Some(e) if e.secret == secret =>
            val status = e.state match
              case EntryState.Matched(m) => SeekStatus.Matched(m.gameId, m.token, m.seat)
              case _                     => SeekStatus.Open
            (current.updated(id, e.copy(lastSeenAt = now)), Some(status))
          case _ => (current, None)

  /** Accept an open seek: seat a game (assign creator and accepter randomly to White/Black) and return the accepter's
    * game + seat token.
    *
    * A rated seek refuses an anonymous accepter outright (#279) — `RequiresAccount`, checked before capacity and before
    * the seek is claimed, so a guest bouncing off a rated offer leaves it open for someone who can actually play it.
    * This is a refusal, not the create-time silent downgrade: the seek already told every browser it was rated, so
    * honouring that or rejecting are the only two options that do not lie to the creator.
    *
    * Declared capacity (#189) is checked before the seek is claimed, so a `Busy` accept leaves the offer open for
    * someone else instead of consuming it — the same reasoning as `Challenges.accept`.
    */
  def accept(id: String, accepter: Principal): IO[Either[Rejected, Match]] =
    seeks.get.map(resolve(_, id, accepter)).flatMap {
      case Left(rejected)                                                      => IO.pure(Left(rejected))
      case Right((_, _, rated)) if rated && GameRegistry.isAnonymous(accepter) =>
        IO.pure(Left(Rejected.RequiresAccount))
      case Right((creator, _, _)) =>
        admissionGuard match
          case Some(guard) =>
            guard
              .acquire(List(creator, accepter), AdmissionPurpose.Direct)
              .flatMap:
                case Left(AdmissionGuard.AdmissionError.Busy(_))     => IO.pure(Left(Rejected.Busy))
                case Left(AdmissionGuard.AdmissionError.Failed(err)) => IO.pure(Left(Rejected.Failed(err)))
                case Right(ticket)                                   =>
                  seat(id, accepter, ticket).guaranteeCase:
                    case Outcome.Succeeded(_) => IO.unit
                    case Outcome.Errored(_)   => ticket.release
                    case Outcome.Canceled()   => ticket.release
          case None =>
            admitBoth(creator, accepter).ifM(seatLegacy(id, accepter), IO.pure(Left(Rejected.Busy)))
    }

  /** Claim the seek (so two accepters can't both seat a game) and start the game within an atomic reservation. */
  private def seat(
      id: String,
      accepter: Principal,
      ticket: AdmissionGuard.AdmissionTicket
  ): IO[Either[Rejected, Match]] =
    (claim(id, accepter), randomBoolean).flatMapN {
      case (Left(rejected), _) =>
        ticket.release.as(Left(rejected))
      case (Right((creator, tc, rated)), swapColor) =>
        val (white, black) = if swapColor then (accepter, creator) else (creator, accepter)
        registry
          .createRoomInternal(
            white,
            black,
            tc,
            origin = GameOrigin.Lobby,
            requestedRated = rated,
            ladder = false
          )
          .flatMap {
            case Left(error) =>
              seeks.update(_.removed(id)) *>
                ticket.release.as(Left(Rejected.Failed(error)))
            case Right((gameId, room)) =>
              val tokens                      = room.joinTokens
              val (creatorSeat, accepterSeat) = if swapColor then (Seat.Black, Seat.White) else (Seat.White, Seat.Black)
              (tokens.get(creatorSeat), tokens.get(accepterSeat)) match
                case (Some(creatorToken), Some(accepterToken)) =>
                  ticket
                    .commit(gameId)
                    .flatMap:
                      case true =>
                        seeks
                          .update(
                            _.updatedWith(id)(
                              _.map(
                                _.copy(state = EntryState.Matched(Match(gameId.value, creatorToken, creatorSeat)))
                              )
                            )
                          )
                          .as(Right(Match(gameId.value, accepterToken, accepterSeat)))
                      case false =>
                        seeks.update(_.removed(id)) *>
                          registry
                            .deregister(gameId, List(white, black))
                            .as(Left(Rejected.Failed("reservation lease expired before room creation completed")))
                case _ =>
                  seeks.update(_.removed(id)) *>
                    ticket.release.as(Left(Rejected.Failed("missing seat token")))
          }
    }

  private def seatLegacy(id: String, accepter: Principal): IO[Either[Rejected, Match]] =
    (claim(id, accepter), randomBoolean).flatMapN {
      case (Left(rejected), _)                      => IO.pure(Left(rejected))
      case (Right((creator, tc, rated)), swapColor) =>
        val (white, black) = if swapColor then (accepter, creator) else (creator, accepter)
        registry.create(white, black, tc, requestedRated = rated, origin = GameOrigin.Lobby).flatMap {
          case Left(error)           => seeks.update(_.removed(id)).as(Left(Rejected.Failed(error)))
          case Right((gameId, room)) =>
            val tokens                      = room.joinTokens
            val (creatorSeat, accepterSeat) = if swapColor then (Seat.Black, Seat.White) else (Seat.White, Seat.Black)
            (tokens.get(creatorSeat), tokens.get(accepterSeat)) match
              case (Some(creatorToken), Some(accepterToken)) =>
                seeks
                  .update(
                    _.updatedWith(id)(
                      _.map(
                        _.copy(state = EntryState.Matched(Match(gameId.value, creatorToken, creatorSeat)))
                      )
                    )
                  )
                  .as(Right(Match(gameId.value, accepterToken, accepterSeat)))
              case _ =>
                seeks.update(_.removed(id)).as(Left(Rejected.Failed("missing seat token")))
        }
    }

  /** Cancel only a still-`Open` seek (secret-gated): a claimed/matched seek has a game in flight, so removing it would
    * strand the creator's seat token. Returns whether a seek was removed.
    */
  def cancel(id: String, secret: String): IO[Boolean] =
    seeks.modify: current =>
      current.get(id) match
        case Some(e) if e.secret == secret && e.state == EntryState.Open => (current.removed(id), true)
        case _                                                           => (current, false)

  /** Drop seeks whose creator hasn't polled within its TTL (gone). Bot seeks get the longer `botTtl`, sized for a
    * poll-only bot on a lazy timer holding a standing offer.
    */
  def sweep: IO[Unit] =
    IO.monotonic.flatMap(now => seeks.update(_.filter((_, e) => now - e.lastSeenAt < ttlOf(e))))

  private def ttlOf(e: Entry): FiniteDuration = if e.seek.kind == PlayerKind.Bot then botTtl else ttl

  /** Background TTL-sweep loop; start once at boot. */
  def sweeper(interval: FiniteDuration = SweepInterval): IO[Unit] = (IO.sleep(interval) *> sweep).foreverM

  /** Atomically move an open seek to `Claimed`, so two accepters can't both seat a game. A creator cannot accept its
    * own seek: the room seats principals, and one principal on both seats has no distinguishable seat.
    */
  private def claim(id: String, accepter: Principal): IO[Either[Rejected, (Principal, TimeControl, Boolean)]] =
    seeks.modify: current =>
      resolve(current, id, accepter) match
        case right @ Right(_) =>
          (current.updatedWith(id)(_.map(_.copy(state = EntryState.Claimed))), right)
        case left => (current, left)

  /** Whether `accepter` may take seek `id` in this snapshot — shared by the read-only pre-check and the atomic
    * [[claim]], so the two can never disagree about what counts as taken or as one's own seek. The returned `rated` is
    * the seek's own, unconditionally — the accepter-anonymity check happens in [[accept]], before this is called.
    */
  private def resolve(
      current: Map[String, Entry],
      id: String,
      accepter: Principal
  ): Either[Rejected, (Principal, TimeControl, Boolean)] =
    current.get(id) match
      case None                             => Left(Rejected.NotFound)
      case Some(e) if e.creator == accepter => Left(Rejected.OwnSeek)
      case Some(e)                          =>
        e.state match
          case EntryState.Open => Right((e.creator, e.seek.timeControl, e.seek.rated))
          case _               => Left(Rejected.AlreadyTaken)

object Lobby:

  /** How long a seek survives without the creator polling it (creator presumed gone). */
  val DefaultTtl: FiniteDuration = 15.seconds

  /** Bot seeks live longer between polls: a poll-only bot on a ~1-minute timer must be able to hold a standing offer
    * without a 15s heartbeat. Bots are authenticated and their open seeks are capped, so the looser TTL is safe.
    */
  val DefaultBotTtl: FiniteDuration = 2.minutes

  /** Cap on one bot's simultaneously OPEN seeks — bounds the lobby against a seek-spamming bot. */
  val DefaultMaxOpenSeeksPerBot: Int = 3

  /** How often the background sweep runs. */
  val SweepInterval: FiniteDuration = 5.seconds

  /** A game id plus the seat token and the seat it names, for one side. */
  final case class Match(gameId: String, token: String, seat: Seat)

  /** Status a creator's poll reports back. */
  enum SeekStatus:
    case Open
    case Matched(gameId: String, token: String, seat: Seat)

  /** Why a create was refused. */
  enum CreateRejected:
    case TooManyOpenSeeks // the bot is at its open-seeks cap

  /** Why an accept was refused. */
  enum Rejected:
    case NotFound
    case AlreadyTaken
    case OwnSeek         // the creator itself tried to accept
    case Busy            // a side is at its declared concurrent-game capacity (#189); the seek stays open
    case RequiresAccount // rated seek, anonymous accepter (#279); the seek stays open — see `accept`'s own doc
    case Failed(reason: String)

  /** Capacity check for a proposed pairing — same function-shaped seam, and same permissive default, as
    * `Challenges.AdmitAny`.
    */
  val AdmitAny: (Principal, Principal) => IO[Boolean] = (_, _) => IO.pure(true)

  private enum EntryState:
    case Open
    case Claimed
    case Matched(m: Match)

  final private case class Entry(
      seek: Seek,
      creator: Principal,
      secret: String,
      state: EntryState,
      lastSeenAt: FiniteDuration
  )

  def create(
      registry: GameRegistry,
      ttl: FiniteDuration = DefaultTtl,
      botTtl: FiniteDuration = DefaultBotTtl,
      maxOpenSeeksPerBot: Int = DefaultMaxOpenSeeksPerBot,
      admitBoth: (Principal, Principal) => IO[Boolean] = AdmitAny,
      // Display names for seek creators — `UserStore.nicknamesByExternalId` in production. Same shape and same default
      // as `GameRegistry.create`: no accounts means every human offers anonymously, as before #194.
      resolveNicknames: List[String] => IO[Map[String, String]] = _ => IO.pure(Map.empty),
      admissionGuard: Option[AdmissionGuard] = None
  ): IO[Lobby] =
    (Ref.of[IO, Map[String, Entry]](Map.empty), Ref.of[IO, Long](0L))
      .mapN((seeks, nextId) =>
        new Lobby(seeks, registry, nextId, ttl, botTtl, maxOpenSeeksPerBot, admitBoth, resolveNicknames, admissionGuard)
      )

  private def randomSecret: IO[String] = IO:
    val bytes = new Array[Byte](16)
    SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString

  private def randomBoolean: IO[Boolean] = IO(SecureRandom().nextBoolean())
