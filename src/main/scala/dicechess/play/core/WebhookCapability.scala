package dicechess.play.core

/** Availability of a webhook capability in the public registry. A reserved capability is a stable name clients may
  * discover, but it is not yet legal in a registration.
  */
enum WebhookCapabilityStatus(val wireName: String):
  case Available extends WebhookCapabilityStatus("available")
  case Reserved  extends WebhookCapabilityStatus("reserved")

/** A webhook protocol extension known to play-api.
  *
  * The enum and its declaration order are the single source of truth for discovery, validation and canonical storage.
  * Wire names are deliberately exact: no aliases, case folding or whitespace trimming are accepted.
  */
enum WebhookCapability(
    val wireName: String,
    val status: WebhookCapabilityStatus
):
  case Draws    extends WebhookCapability("draws", WebhookCapabilityStatus.Available)
  case Doubling extends WebhookCapability("doubling", WebhookCapabilityStatus.Reserved)

  /** Derived from status so discovery cannot advertise a contradictory pair. */
  def selectable: Boolean = status == WebhookCapabilityStatus.Available

object WebhookCapability:

  /** Stable registry order, also used for canonical persisted arrays. */
  val registry: List[WebhookCapability] = WebhookCapability.values.toList

  /** Capabilities currently accepted by `POST /bot/webhook`. */
  val selectableCapabilities: List[WebhookCapability] = registry.filter(_.selectable)

  def fromWireName(name: String): Option[WebhookCapability] = registry.find(_.wireName == name)

  /** Defensively enforce the writable subset at any internal service boundary and restore canonical registry order. */
  def canonicalizeSelection(
      capabilities: List[WebhookCapability]
  ): Either[String, List[WebhookCapability]] =
    capabilities.find(!_.selectable) match
      case Some(capability) => Left(s"webhook capability is not available: ${capability.wireName}")
      case None             => Right(selectableCapabilities.filter(capabilities.toSet.contains))

  /** Parse and canonicalize a registration selection.
    *
    * Every input member must be both known and selectable. Duplicates are accepted, then collapsed into registry
    * order. The entire selection fails on the first invalid member, so a mixed request can never be partially stored.
    */
  def parseSelection(names: List[String]): Either[String, List[WebhookCapability]] =
    names
      .foldLeft[Either[String, Set[WebhookCapability]]](Right(Set.empty)):
        case (left @ Left(_), _) => left
        case (Right(selected), name) =>
          fromWireName(name) match
            case None => Left(s"unknown webhook capability: $name")
            case Some(capability) if !capability.selectable =>
              Left(s"webhook capability is not available: ${capability.wireName}")
            case Some(capability) => Right(selected + capability)
      .flatMap(selected => canonicalizeSelection(selected.toList))
