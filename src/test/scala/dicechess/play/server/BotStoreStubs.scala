package dicechess.play.server

import cats.effect.IO
import dicechess.play.store.{BotStore, OwnedBot, OwnerClaim}

/** The ownership half of [[BotStore]], inert (#253). Four suites build their own `BotStore` double for reasons that
  * have nothing to do with ownership, and each needed the same four no-op members — so one interface addition meant
  * four identical edits. Mixing this in means a suite implements only what it actually exercises.
  *
  * `register` reports "not claimed" rather than raising: a double that never registers anything is the normal case
  * here, and a raise would turn an incidental call into a confusing failure far from its cause.
  */
trait UnownedBotStore extends BotStore:
  def register(team: String, name: String, tokenHash: String, owner: Option[String]): IO[Boolean] = IO.pure(false)
  def claimOwner(team: String, name: String, owner: String): IO[OwnerClaim] = IO.pure(OwnerClaim.NotRegistered)
  def releaseOwner(team: String, name: String, owner: String): IO[Boolean]  = IO.pure(false)
  def botsOwnedBy(owner: String): IO[List[OwnedBot]]                        = IO.pure(Nil)
