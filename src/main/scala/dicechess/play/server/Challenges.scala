package dicechess.play.server

import cats.effect.kernel.Outcome
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*

import scala.concurrent.duration.*

/** Pending bot-to-bot challenges. Creating one notifies the target's live account stream AND leaves the entry
  * discoverable via `GET /bot/challenges` until it expires — so a bot that was offline when the push happened can still
  * find and claim it by polling. Accepting seats a game and emits `gameStart` to both bots; declining notifies the
  * challenger. Only the challenged bot may accept or decline. A background [[sweeper]] expires unclaimed entries,
  * declining them back to the challenger, so the map never grows without bound.
  */
final class Challenges private (
    pending: Ref[IO, Map[String, Challenges.Entry]],
    events: BotEvents,
    registry: GameRegistry,
    nextId: Ref[IO, Long],
    ttl: FiniteDuration,
    maxPendingPerBot: Int,
    admitBoth: (Principal, Principal) => IO[Boolean],
    admissionGuard: Option[AdmissionGuard]
):
  import Challenges.*

  /** Create a pending challenge, or say why it is refused (self-challenge, per-challenger cap). The result carries
    * whether the target currently holds an account stream — advisory only: an offline target can still discover the
    * entry via `GET /bot/challenges` before the TTL expires.
    *
    * `rated` (#282) is the challenger's own choice, carried on the offer so the target can see it before accepting. An
    * anonymous participant (an anon-team bot) is silently degraded to casual by `GameRegistry.isRated` rather than
    * refused, which is the opposite of what a rated SEEK does on accept — deliberately. A seek is a public offer, so
    * refusing an anonymous accepter leaves it standing for someone who can play it; a challenge is addressed to one
    * named bot, so there is no third party to leave it for and refusing would only destroy the offer.
    *
    * An uncategorised control degrades here too (#280), and for the honesty reason rather than the courtesy one: the
    * offer is what the target reads before accepting, so advertising `rated: true` for a game `GameRegistry.isRated`
    * will make casual would misinform exactly the party the field exists to inform.
    */
  def create(
      challenger: Principal,
      target: Principal,
      timeControl: TimeControl = TimeControl.Unlimited,
      rated: Boolean = false
  ): IO[Either[CreateRejected, Created]] =
    if challenger == target then IO.pure(Left(CreateRejected.SelfChallenge))
    else
      (nextId.getAndUpdate(_ + 1), IO.monotonic).flatMapN: (n, now) =>
        // `actualRated`, the name `Lobby.create` gives the same degrade — the two are one rule applied to the two
        // places an offer is published, and a reader comparing them should not have to notice a rename.
        val actualRated = rated && RatingCategory.of(timeControl).isDefined
        val challenge   = Challenge(s"challenge-$n", challenger, target, timeControl, actualRated)
        pending
          .modify: current =>
            if current.values.count(_.challenge.challenger == challenger) >= maxPendingPerBot then
              (current, Left(CreateRejected.TooManyPending))
            else (current.updated(challenge.id, Entry(challenge, now)), Right(challenge))
          .flatMap:
            case Left(rejected) => IO.pure(Left(rejected))
            case Right(created) =>
              events.publish(target, BotEvent.ChallengeReceived(created.id, challenger)) *>
                events.online(target).map(online => Right(Created(created, online)))

  /** The caller's pending challenges: addressed to it (`in` — accept/decline by id) and created by it (`out` — watch
    * their fate). The polling counterpart of the live `ChallengeReceived`/`ChallengeDeclined` pushes.
    */
  def listFor(principal: Principal): IO[(List[Challenge], List[Challenge])] =
    pending.get.map: current =>
      val all = current.values.toList.sortBy(_.createdAt).map(_.challenge)
      (all.filter(_.target == principal), all.filter(_.challenger == principal))

  /** The challenged bot accepts: seat a game (challenger = White, target = Black) and tell both bots its id.
    *
    * Declared capacity (#189) is checked before the entry is claimed, not after: a `Busy` accept must leave the
    * challenge pending so it can be taken once a game finishes, whereas claiming first would consume it to say no.
    */
  def accept(by: Principal, id: String): IO[Either[Rejected, String]] =
    pending.get.map(resolve(_, by, id)).flatMap {
      case Left(rejected)   => IO.pure(Left(rejected))
      case Right(challenge) =>
        admissionGuard match
          case Some(guard) =>
            guard
              .acquire(List(challenge.challenger, challenge.target), AdmissionPurpose.Direct)
              .flatMap:
                case Left(AdmissionGuard.AdmissionError.Busy(_))     => IO.pure(Left(Rejected.Busy))
                case Left(AdmissionGuard.AdmissionError.Failed(err)) => IO.pure(Left(Rejected.Failed(err)))
                case Right(ticket)                                   =>
                  seat(by, id, ticket).guaranteeCase:
                    case Outcome.Succeeded(_) => IO.unit
                    case Outcome.Errored(_)   => ticket.release
                    case Outcome.Canceled()   => ticket.release
          case None =>
            admitBoth(challenge.challenger, challenge.target).ifM(
              seatLegacy(by, id),
              IO.pure(Left(Rejected.Busy))
            )
    }

  /** Claim the entry (so two accepts can't both seat a game) and start the game within an atomic reservation. */
  private def seat(by: Principal, id: String, ticket: AdmissionGuard.AdmissionTicket): IO[Either[Rejected, String]] =
    claim(by, id).flatMap {
      case Left(rejected) =>
        ticket.release.as(Left(rejected))
      case Right(challenge) =>
        registry
          .createRoomInternal(
            challenge.challenger,
            challenge.target,
            challenge.timeControl,
            origin = GameOrigin.Direct,
            requestedRated = challenge.rated,
            ladder = false
          )
          .flatMap {
            case Left(error) =>
              ticket.release.as(Left(Rejected.Failed(error)))
            case Right((gameId, _)) =>
              ticket
                .commit(gameId)
                .flatMap:
                  case true =>
                    val started = BotEvent.GameStart(gameId.value)
                    events.publish(challenge.challenger, started) *>
                      events.publish(challenge.target, started).as(Right(gameId.value))
                  case false =>
                    registry
                      .deregister(gameId, List(challenge.challenger, challenge.target))
                      .as(Left(Rejected.Failed("reservation lease expired before room creation completed")))
          }
    }

  private def seatLegacy(by: Principal, id: String): IO[Either[Rejected, String]] =
    claim(by, id).flatMap {
      case Left(rejected)   => IO.pure(Left(rejected))
      case Right(challenge) =>
        registry
          .create(
            challenge.challenger,
            challenge.target,
            challenge.timeControl,
            requestedRated = challenge.rated,
            origin = GameOrigin.Direct
          )
          .flatMap {
            case Left(error)        => IO.pure(Left(Rejected.Failed(error)))
            case Right((gameId, _)) =>
              val started = BotEvent.GameStart(gameId.value)
              events.publish(challenge.challenger, started) *>
                events.publish(challenge.target, started).as(Right(gameId.value))
          }
    }

  /** The challenged bot declines: drop it and tell the challenger. */
  def decline(by: Principal, id: String): IO[Either[Rejected, Unit]] =
    claim(by, id).flatMap {
      case Left(rejected)   => IO.pure(Left(rejected))
      case Right(challenge) => events.publish(challenge.challenger, BotEvent.ChallengeDeclined(id)).as(Right(()))
    }

  /** Expire entries past the TTL, declining each back to its challenger — a listening one learns promptly, a polling
    * one sees the entry vanish from its `out` list.
    */
  def sweep: IO[Unit] =
    IO.monotonic
      .flatMap: now =>
        pending.modify: current =>
          val (alive, expired) = current.partition((_, entry) => now - entry.createdAt < ttl)
          (alive, expired.values.toList)
      .flatMap(_.traverse_ { entry =>
        events.publish(entry.challenge.challenger, BotEvent.ChallengeDeclined(entry.challenge.id))
      })

  /** Background TTL-sweep loop; start once at boot. */
  def sweeper(interval: FiniteDuration = SweepInterval): IO[Unit] = (IO.sleep(interval) *> sweep).foreverM

  /** Atomically remove a pending challenge if `by` is its target — so two accepts can't both seat a game. */
  private def claim(by: Principal, id: String): IO[Either[Rejected, Challenge]] =
    pending.modify { current =>
      resolve(current, by, id) match
        case right @ Right(_) => (current.removed(id), right)
        case left             => (current, left)
    }

  /** Whether `by` may act on challenge `id` in this snapshot of the map — shared by the read-only pre-check and the
    * atomic [[claim]], so the two can never disagree about what counts as not-found or not-yours.
    */
  private def resolve(current: Map[String, Entry], by: Principal, id: String): Either[Rejected, Challenge] =
    current.get(id) match
      case None                                        => Left(Rejected.NotFound)
      case Some(entry) if entry.challenge.target != by => Left(Rejected.NotYours)
      case Some(entry)                                 => Right(entry.challenge)

object Challenges:

  /** How long an unclaimed challenge stays pending (and discoverable via `GET /bot/challenges`). Long enough for a
    * polling bot on a lazy timer to find it, short enough that a challenge to a gone bot doesn't strand its
    * challenger's cap for long.
    */
  val DefaultTtl: FiniteDuration = 5.minutes

  /** How often the background sweep runs. */
  private val SweepInterval: FiniteDuration = 15.seconds

  /** Cap on one bot's outstanding challenges — bounds the pending map against a create-loop. */
  val DefaultMaxPendingPerBot: Int = 10

  /** A pending challenge plus its creation stamp (monotonic, for the TTL sweep). */
  final private case class Entry(challenge: Challenge, createdAt: FiniteDuration)

  /** Why a create was refused. */
  enum CreateRejected:
    case SelfChallenge  // a bot cannot challenge itself
    case TooManyPending // the challenger is at its pending cap

  /** A created pending challenge, plus whether the target currently holds an account stream (advisory). */
  final case class Created(challenge: Challenge, targetOnline: Boolean)

  /** Why an accept/decline was refused. */
  enum Rejected:
    case NotFound // no pending challenge with that id
    case NotYours // the caller is not the challenged bot
    case Busy     // a side is at its declared concurrent-game capacity (#189); the challenge stays pending
    case Failed(reason: String) // the game could not be seated

  /** Capacity check for a proposed pairing. A plain function rather than a [[SeatGuard]] dependency, matching how
    * `GameRoom.create` takes `persist`: it keeps this class about the challenge lifecycle, and lets every existing test
    * build one without a bot store. The default admits everyone, so an unwired `Challenges` behaves as it did before
    * per-bot capacity existed.
    */
  val AdmitAny: (Principal, Principal) => IO[Boolean] = (_, _) => IO.pure(true)

  def create(
      events: BotEvents,
      registry: GameRegistry,
      ttl: FiniteDuration = DefaultTtl,
      maxPendingPerBot: Int = DefaultMaxPendingPerBot,
      admitBoth: (Principal, Principal) => IO[Boolean] = AdmitAny,
      admissionGuard: Option[AdmissionGuard] = None
  ): IO[Challenges] =
    (Ref.of[IO, Map[String, Entry]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new Challenges(_, events, registry, _, ttl, maxPendingPerBot, admitBoth, admissionGuard))
