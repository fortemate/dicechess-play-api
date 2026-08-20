package dicechess.play.core

/** A public, open game offer in the lobby that anyone may accept. `kind`/`name` say WHO is offering — so a human can
  * see (and choose) a bot opponent — without ever leaking private ids: bots show their team-qualified name, a
  * registered player shows their nickname (#194 step 4), and a guest stays anonymous (`name` absent). The creator's
  * principal and capability secret stay server-side (see the server `Lobby`).
  *
  * `rated` (#279, ADR-0017) is the creator's own choice, made when the seek is posted — never inferred from who they
  * are. `Lobby.create` still degrades it to `false` for an anonymous creator, the same silent-safe fallback
  * `GameRegistry.isRated` already applies at game creation; an anonymous ACCEPTER is refused outright (see
  * `Lobby.accept`) rather than silently downgraded, since that would lie to the creator about the game they offered.
  */
final case class Seek(id: String, timeControl: TimeControl, kind: PlayerKind, name: Option[String], rated: Boolean)
