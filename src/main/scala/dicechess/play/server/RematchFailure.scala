package dicechess.play.server

import dicechess.play.store.{CorruptRematchRecord, RematchTransitionRejected}
import java.sql.SQLException

/** JDBC/decoder messages can contain private row values. Keep diagnostic types, SQLSTATE and safe domain context. */
private[server] object RematchFailure:
  def describe(error: Throwable): String =
    List
      .iterate(Option(error), 4)(_.flatMap(e => Option(e.getCause)))
      .flatten
      .map { cause =>
        val kind = cause.getClass.getName
        cause match
          case e: CorruptRematchRecord      => s"$kind: ${e.getMessage}"
          case e: RematchTransitionRejected => s"$kind: ${e.getMessage}"
          case e: SQLException              =>
            val state = Option(e.getSQLState).filter(_.matches("[A-Z0-9]{5}")).getOrElse("unknown")
            s"$kind(sqlState=$state)"
          case _ =>
            val location = cause.getStackTrace.headOption
              .map(frame => s" at ${frame.getClassName}.${frame.getMethodName}:${frame.getLineNumber}")
              .getOrElse("")
            s"$kind$location"
      }
      .mkString(" caused by ")
