package dicechess.play.dice

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.LaboratoryProtocol
import dicechess.play.core.LaboratoryProtocol.DicePair

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CharacterCodingException, StandardCharsets}
import java.security.{MessageDigest, SecureRandom}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.annotation.tailrec

/** Private, position-independent offers for one laboratory macro-turn. This is deliberately separate from classic
  * DiceSource. The room must freeze post-commit client seeds, persist both offers and control their public revelation.
  */
trait LaboratoryDiceSource:
  def algorithm: String = LaboratoryDiceSource.Algorithm
  def commit: String
  def reveal: String
  def offers(turnNumber: Long, clientWhite: String, clientBlack: String): Either[String, LaboratoryDiceSource.Offers]

object LaboratoryDiceSource:
  val Algorithm: String = LaboratoryProtocol.DiceAlgorithm
  private val Domain    = (Algorithm + "\u0000").getBytes(StandardCharsets.US_ASCII)
  private val LastBlock = 0xffffffffL

  /** Both alternatives are private until the room explicitly projects the permitted reveal stage. */
  final case class Offers(first: DicePair, second: DicePair):
    def values: List[DicePair] = List(first, second)

  def commitReveal(serverSeed: Array[Byte]): Either[String, LaboratoryDiceSource] =
    Either
      .cond(serverSeed.length == 32, (), "laboratory server seed must contain exactly 32 bytes")
      .map(_ => new Committed(serverSeed.clone()))

  def newCommitReveal(): IO[LaboratoryDiceSource] = IO:
    val seed = new Array[Byte](32)
    SecureRandom().nextBytes(seed)
    new Committed(seed)

  /** Recovery fails closed for an unknown algorithm or malformed seed, without including private input in errors. */
  def fromHexSeed(seedHex: String, algorithm: String = Algorithm): Either[String, LaboratoryDiceSource] =
    for
      _      <- Either.cond(algorithm == Algorithm, (), "unsupported laboratory dice algorithm")
      _      <- Either.cond(seedHex.matches("[0-9a-fA-F]{64}"), (), "laboratory server seed must be 64 hex characters")
      source <- commitReveal(seedHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
    yield source

  final private class Committed(seed: Array[Byte]) extends LaboratoryDiceSource:
    val commit: String = hex(MessageDigest.getInstance("SHA-256").digest(seed))
    def reveal: String = hex(seed)

    def offers(turnNumber: Long, clientWhite: String, clientBlack: String): Either[String, Offers] =
      for
        _     <- Either.cond(LaboratoryProtocol.validVersion(turnNumber), (), "invalid laboratory turn number")
        white <- utf8(clientWhite)
        black <- utf8(clientBlack)
        base = Domain ++ lengthPrefixed(white) ++ lengthPrefixed(black) ++ ByteBuffer
          .allocate(8)
          .putLong(turnNumber)
          .array()
        first  <- derive(seed, base, 0)
        second <- derive(seed, base, 1)
      yield Offers(first, second)

  private def utf8(value: String): Either[String, Array[Byte]] =
    Either
      .catchOnly[CharacterCodingException]:
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        val bytes   = new Array[Byte](encoded.remaining())
        encoded.get(bytes)
        bytes
      .leftMap(_ => "laboratory client seed is not valid Unicode")

  private def lengthPrefixed(bytes: Array[Byte]): Array[Byte] =
    ByteBuffer.allocate(4).putInt(bytes.length).array() ++ bytes

  private def derive(seed: Array[Byte], base: Array[Byte], offerIndex: Int): Either[String, DicePair] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(seed, "HmacSHA256"))
    samplePair: block =>
      mac.doFinal(base ++ Array(offerIndex.toByte) ++ ByteBuffer.allocate(4).putInt(block.toInt).array())

  /** Separate sampling seam lets tests exercise rejection, block refill and exhaustion without replacing the RNG.
    * Counter exhaustion is an explicit error: never wrap, reuse a block or fall back to biased modulo reduction.
    */
  private[dice] def samplePair(
      blockAt: Long => Array[Byte],
      lastBlock: Long = LastBlock
  ): Either[String, DicePair] =
    @tailrec
    def loop(block: Long, accepted: List[Int]): Either[String, DicePair] =
      val dice = accepted ++ blockAt(block).iterator
        .map(_ & 0xff)
        .filter(_ < 252)
        .map(b => b % 6 + 1)
        .take(2 - accepted.size)
        .toList
      dice match
        case first :: second :: Nil  => Right(DicePair(first, second))
        case _ if block == lastBlock => Left("laboratory dice counter exhausted")
        case _                       => loop(block + 1, dice)
    loop(0L, Nil)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
