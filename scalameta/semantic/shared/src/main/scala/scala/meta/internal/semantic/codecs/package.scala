package scala.meta.internal.semantic

import scala.meta.io._
import scala.meta.{semantic => m}
import scala.meta.internal.semantic.{proto => p}

package object codecs {
  implicit class XtensionProtoSerializable[A](val a: A) extends AnyVal {
    def toMeta[B](implicit ev: ProtoDecoder[B, A]): B = ev.fromProto(a)
    def toProto[B](implicit ev: ProtoEncoder[A, B]): B = ev.toProto(a)
    def toProtoOpt[B](implicit ev: ProtoEncoder[A, B]): Option[B] = Option(toProto[B])
  }

  implicit val AttributedSourceCodec: ProtoCodec[m.AttributedSource, p.AttributedSource] =
    new ProtoCodec[m.AttributedSource, p.AttributedSource] {
      override def toProto(e: m.AttributedSource): p.AttributedSource = e match {
        case m.AttributedSource(path, names, messages, denotations) =>
          implicit val resolvedNameEncoder = new ProtoEncoder[(m.Anchor, m.Symbol), p.ResolvedName] {
            override def toProto(e: (m.Anchor, m.Symbol)): p.ResolvedName = e match {
              case (m.Anchor(_, start, end), symbol) =>
                p.ResolvedName(Option(p.Range(start, end)), symbol.syntax)
            }
          }
          implicit val messageEncoder = new ProtoEncoder[m.Message, p.Message] {
            override def toProto(e: m.Message): p.Message = e match {
              case m.Message(m.Anchor(_, start, end), sev, msg) =>
                p.Message(Option(p.Range(start, end)), p.Message.Severity.fromValue(sev.id), msg)
            }
          }
          implicit val symbolDenotationEncoder = new ProtoEncoder[(m.Symbol, m.Denotation), p.SymbolDenotation] {
            override def toProto(e: (m.Symbol, m.Denotation)): p.SymbolDenotation = e match {
              case (symbol, denotation) =>
                p.SymbolDenotation(symbol.syntax, Option(p.Denotation(denotation.flags)))
            }
          }
          p.AttributedSource(path.toString,
                             names.map(_.toProto[p.ResolvedName]).toSeq,
                             messages.map(_.toProto[p.Message]),
                             denotations.map(_.toProto[p.SymbolDenotation]).toSeq)
      }
      override def fromProto(e: p.AttributedSource): m.AttributedSource = e match {
        case p.AttributedSource(path, names, messages, denotations) =>
          implicit val resolvedNameDecoder = new ProtoDecoder[(m.Anchor, m.Symbol), p.ResolvedName] {
            override def fromProto(e: p.ResolvedName): (m.Anchor, m.Symbol) = e match {
              case p.ResolvedName(Some(p.Range(start, end)), m.Symbol(symbol)) =>
                m.Anchor(RelativePath(path), start, end) -> symbol
            }
          }
          implicit val messageDecoder = new ProtoDecoder[m.Message, p.Message] {
            override def fromProto(e: p.Message): m.Message = e match {
              case p.Message(Some(p.Range(start, end)), sev, message) =>
                m.Message(m.Anchor(RelativePath(path), start, end), m.Severity.fromId(sev.value), message)
            }
          }
          implicit val symbolDenotationEncoder = new ProtoDecoder[(m.Symbol, m.Denotation), p.SymbolDenotation] {
            override def fromProto(e: p.SymbolDenotation): (m.Symbol, m.Denotation) = e match {
              case p.SymbolDenotation(m.Symbol(symbol), Some(p.Denotation(flags))) =>
                symbol -> m.Denotation(flags)
            }
          }
          m.AttributedSource(RelativePath(path),
                             names.map(_.toMeta[(m.Anchor, m.Symbol)]).toMap,
                             messages.map(_.toMeta[m.Message]).toList,
                             denotations.map(_.toMeta[(m.Symbol, m.Denotation)]).toMap)
      }
    }

  implicit val DatabaseCodec: ProtoCodec[m.Database, p.Database] =
    new ProtoCodec[m.Database, p.Database] {
      override def toProto(e: m.Database): p.Database = e match {
        case m.Database(sources) =>
          p.Database(sources.map(_.toProto[p.AttributedSource]).toSeq)
      }
      override def fromProto(e: p.Database): m.Database = e match {
        case p.Database(sources) =>
          m.Database(sources.map(_.toMeta[m.AttributedSource]).toList)
      }
    }
}