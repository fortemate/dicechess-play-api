package dicechess.play.core

/** A pending challenge from one bot to another.
  *
  * `rated` (#282) belongs to the OFFER, like `timeControl`: the challenger states it and the target takes the challenge
  * as offered or declines it. That is why it rides on this type rather than on the accept — a bot polling
  * `GET /bot/challenges` has to be able to see what it is agreeing to before it agrees.
  */
final case class Challenge(
    id: String,
    challenger: Principal,
    target: Principal,
    timeControl: TimeControl = TimeControl.Unlimited,
    rated: Boolean = false
)

/** Events pushed to a bot's account stream (`GET /bot/stream/event`), Lichess-shaped. */
enum BotEvent:
  case ChallengeReceived(id: String, challenger: Principal)
  case ChallengeDeclined(id: String)
  case GameStart(gameId: String)
