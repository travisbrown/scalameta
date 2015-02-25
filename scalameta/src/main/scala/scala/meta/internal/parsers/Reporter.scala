package scala.meta
package internal
package parsers

// TODO: when I grow up I want to become a monad, just like my daddy
// TODO: when we have Positions, attach them to the exception being thrown
private[meta] trait Reporter {
  def deprecationWarning(msg: String, at: Token): Unit = ()
  def deprecationWarning(msg: String, at: Tree): Unit = {
    def fallback = ()
    at.origin.tokens.headOption.map(at => deprecationWarning(msg, at)).getOrElse(())
  }
  def syntaxError(msg: String, at: Token): Nothing = throw Exception(s"syntax error at $at: $msg")
  def syntaxError(msg: String, at: Tree): Nothing = {
    def fallback = throw Exception(s"syntax error: $msg")
    at.origin.tokens.headOption.map(at => syntaxError(msg, at = at)).getOrElse(fallback)
  }
}

private[meta] object Reporter {
  def apply() = new Reporter {}
}
