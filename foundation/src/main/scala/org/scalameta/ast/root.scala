package org.scalameta.ast

import scala.language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.reflect.macros.whitebox.Context

class root extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro RootMacros.impl
}

class RootMacros(val c: Context) {
  import c.universe._
  import Flag._
  def impl(annottees: Tree*): Tree = {
    def transform(cdef: ClassDef): ClassDef = {
      val q"${Modifiers(flags, privateWithin, anns)} trait $name[..$tparams] extends { ..$earlydefns } with ..$parents { $self => ..$stats }" = cdef
      // TODO: think of better ways to abstract this away from the public API
      val Tree = tq"_root_.scala.meta.Tree"
      val Datum = tq"_root_.scala.Any"
      val Data = tq"_root_.scala.collection.immutable.Seq[$Datum]"
      val Adt = q"_root_.org.scalameta.adt"
      val AstInternal = q"_root_.org.scalameta.ast.internal"
      val q"..$boilerplate" = q"""
        final override def toString: String = {
          import _root_.scala.meta.ui._
          import _root_.scala.meta.dialects.Scala211
          this.show[Code] // we can't parameterize toString, so we default to Scala prettyprinting
        }
        private[meta] final def summary: String = {
          var result = this.toString.replace(scala.compat.Platform.EOL, " ")
          if (result.length > 60) result = result.take(60) + "..."
          result
        }

        // NOTE: these are internal APIs designed to be used only by hosts
        // TODO: these APIs will most likely change in the future
        // because we would like to make sure that trees are guaranteed to be immutable
        private[meta] def scratchpad: $Data = internalScratchpad
        private[meta] def appendScratchpad(datum: $Datum): ThisType = internalCopy(scratchpad = internalScratchpad :+ datum)
        private[meta] def withScratchpad(data: $Data): ThisType = internalCopy(scratchpad = data)
        private[meta] def mapScratchpad(f: $Data => $Data): ThisType = internalCopy(scratchpad = f(internalScratchpad))

        // NOTE: these are internal APIs that are meant to be used only in the implementation of the framework
        // host implementors should not utilize these APIs
        // TODO: turn the prototype argument of internalCopy into ThisType
        // if done naively, this isn't going to compile for prototypes of @branch traits as ThisType there is abstract
        protected def internalPrototype: ThisType
        protected def internalParent: $Tree
        protected def internalScratchpad: $Data
        private[meta] def internalCopy(prototype: $Tree = internalPrototype, parent: $Tree = internalParent, scratchpad: $Data = internalScratchpad): ThisType
      """
      val stats1 = stats ++ boilerplate
      val anns1 = q"new $AstInternal.root" +: q"new $Adt.root" +: anns
      val parents1 = parents :+ tq"$AstInternal.Ast"
      q"${Modifiers(flags, privateWithin, anns1)} trait $name[..$tparams] extends { ..$earlydefns } with ..$parents1 { $self => ..$stats1 }"
    }
    val expanded = annottees match {
      case (cdef @ ClassDef(mods, _, _, _)) :: rest if mods.hasFlag(TRAIT) => transform(cdef) :: rest
      case annottee :: rest => c.abort(annottee.pos, "only traits can be @root")
    }
    q"{ ..$expanded; () }"
  }
}