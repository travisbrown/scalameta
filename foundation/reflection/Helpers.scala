package scala
package org.scalameta.reflection

import scala.tools.nsc.Global
import scala.reflect.internal.Flags
import scala.collection.mutable

trait Helpers {
  self: _root_.org.scalameta.reflection.GlobalToolkit =>

  import global._
  import definitions._
  import treeInfo._
  import build._

  implicit class RichFoundationHelperTree[T <: Tree](tree: T) {
    def copyAttrs(other: Tree): T = tree.copyAttrs(other)
  }

  // NOTE: partially copy/pasted from TreeInfo.scala and ReificationSupport.scala in scalac
  object SyntacticTemplate {
    def unapply(in: Template): Some[(List[Tree], ValDef, List[Tree], List[Tree])] = {
      def filterBody(body: List[Tree]) = body filter {
        case _: ValDef | _: TypeDef => true
        // keep valdef or getter for val/var
        case dd: DefDef if dd.mods.hasAccessorFlag => !nme.isSetterName(dd.name) && !in.body.exists {
          case vd: ValDef => dd.name == vd.name.dropLocal
          case _ => false
        }
        case md: MemberDef => !md.mods.isSynthetic
        case tree => true
      }
      def lazyValDefRhs(body: Tree) = {
        body match {
          case Block(List(Assign(_, rhs)), _) => rhs
          case _ => body
        }
      }
      def recoverBody(body: List[Tree]) = body map {
        case vd @ ValDef(vmods, vname, _, vrhs) if nme.isLocalName(vname) =>
          in.body find {
            case dd: DefDef => dd.name == vname.dropLocal
            case _ => false
          } map { dd =>
            val DefDef(dmods, dname, _, _, _, drhs) = dd
            // get access flags from DefDef
            val vdMods = (vmods &~ Flags.AccessFlags) | (dmods & Flags.AccessFlags).flags
            // for most cases lazy body should be taken from accessor DefDef
            val vdRhs = if (vmods.isLazy) lazyValDefRhs(drhs) else vrhs
            copyValDef(vd)(mods = vdMods, name = dname, rhs = vdRhs)
          } getOrElse (vd)
        // for abstract and some lazy val/vars
        case dd @ DefDef(mods, name, _, _, tpt, rhs) if mods.hasAccessorFlag =>
          // transform getter mods to field
          val vdMods = (if (!mods.hasStableFlag) mods | Flags.MUTABLE else mods &~ Flags.STABLE) &~ Flags.ACCESSOR
          ValDef(vdMods, name, tpt, rhs)
        case tr => tr
      }
      object UnCtor {
        def unapply(tree: Tree): Option[(Modifiers, List[List[ValDef]], List[Tree], Symbol, List[List[Tree]])] = tree match {
          case DefDef(mods, nme.MIXIN_CONSTRUCTOR, _, _, _, build.SyntacticBlock(lvdefs :+ _)) =>
            Some((mods | Flag.TRAIT, Nil, lvdefs, NoSymbol, Nil))
          case DefDef(mods, nme.CONSTRUCTOR, Nil, vparamss, _, build.SyntacticBlock(lvdefs :+ Applied(core, _, argss) :+ _)) =>
            Some((mods, vparamss, lvdefs, core.symbol, argss))
          case _ => None
        }
      }
      def indexOfCtor(trees: List[Tree]) = {
        trees.indexWhere { case UnCtor(_, _, _, _, _) => true ; case _ => false }
      }
      val body1 = recoverBody(filterBody(in.body))
      val (rawEdefs, rest) = body1.span(treeInfo.isEarlyDef)
      val (gvdefs, etdefs) = rawEdefs.partition(treeInfo.isEarlyValDef)
      var (fieldDefs, lvdefs, superSym, superArgss, body2) = rest.splitAt(indexOfCtor(rest)) match {
        case (fieldDefs, UnCtor(_, _, lvdefs, superSym, superArgss) :: body2) => (fieldDefs, lvdefs, superSym, superArgss, body2)
        case (Nil, body2) => (Nil, Nil, NoSymbol, Nil, body2)
      }
      // TODO: really discern `... extends C()` and `... extends C`
      if (superArgss == List(Nil) && superSym.info.paramss.flatten.isEmpty) superArgss = Nil
      val evdefs = gvdefs.zip(lvdefs).map {
        case (gvdef @ ValDef(_, _, tpt, _), ValDef(_, _, _, rhs)) =>
          copyValDef(gvdef)(tpt = tpt, rhs = rhs)
      }
      val edefs = evdefs ::: etdefs
      val parents = in.parents match {
        case firstParent +: otherParents =>
          val firstParent1 = superArgss.foldLeft(firstParent)((curr, args) => Apply(curr, args).setType(firstParent.tpe).appendScratchpad(superSym))
          firstParent1 +: otherParents
        case Nil =>
          Nil
      }
      Some(parents, in.self, edefs, body2)
    }
  }

  object collapseEmptyTrees extends Transformer {
    override def transform(tree: Tree): Tree = tree match {
      case tree @ PackageDef(pid, stats) if stats.exists(_.isEmpty) => transform(treeCopy.PackageDef(tree, pid, stats.filter(!_.isEmpty)))
      case tree @ Block(stats, expr) if stats.exists(_.isEmpty) => transform(treeCopy.Block(tree, stats.filter(!_.isEmpty), expr))
      case tree @ Template(parents, self, stats) if stats.exists(_.isEmpty) => transform(treeCopy.Template(tree, parents, self, stats.filter(!_.isEmpty)))
      case _ => super.transform(tree)
    }
  }

  object ApplyImplicitView {
    def unapply(tree: Tree): Option[(Tree, Tree)] = tree match {
      case tree: ApplyImplicitView => Some((tree.fun, tree.args.head))
      case _ => None
    }
  }

  object ApplyToImplicitArgs {
    def unapply(tree: Tree): Option[(Tree, List[Tree])] = tree match {
      case tree: ApplyToImplicitArgs => Some((tree.fun, tree.args))
      case _ => None
    }
  }

  object DesugaredSetter {
    def unapply(tree: Tree): Option[(Tree, Tree)] = tree.metadata.get("originalAssign").map(_.asInstanceOf[(Tree, Name, List[Tree])]) match {
      case Some((Desugared(lhs), name: TermName, Desugared(List(rhs)))) if nme.isSetterName(tree.symbol.name) && name == nme.EQL =>
        require(lhs.symbol.owner == tree.symbol.owner && lhs.symbol.name.setterName == tree.symbol.name)
        Some((lhs, rhs))
      case _ =>
        None
    }
  }

  object DesugaredUpdate {
    def unapply(tree: Tree): Option[(Tree, Tree)] = tree match {
      case Apply(core @ Select(lhs, _), args :+ rhs) if core.symbol.name == nme.update && !tree.hasMetadata("originalAssign") =>
        Some((Apply(lhs, args).setType(NoType), rhs))
      case _ =>
        None
    }
  }

  object DesugaredOpAssign {
    def unapply(tree: Tree): Option[(Tree, List[Tree])] = tree.metadata.get("originalAssign").map(_.asInstanceOf[(Tree, Name, List[Tree])]) match {
      case Some((Desugared(qual), name: TermName, Desugared(args))) if name != nme.EQL =>
        Some((Select(qual, name).setType(NoType), args))
      case _ => None
    }
  }

  object DesugaredSymbolLiteral {
    def unapply(tree: Tree): Option[scala.Symbol] = tree match {
      case Apply(fn, List(Literal(Constant(s_value: String)))) if fn.symbol == SymbolModule => Some(scala.Symbol(s_value))
      case _ => None
    }
  }

  sealed trait Enum
  case class Generator(pat: Tree, rhs: Tree) extends Enum
  case class Val(pat: Tree, rhs: Tree) extends Enum
  case class Guard(cond: Tree) extends Enum

  object DesugaredFor {
    def unapply(tree: Tree): Option[(List[Enum], Tree, Boolean)] = {
      // TODO: looks like a bug / tree shape incompatibility in UnFor
      // seemingly innocent `(l1, l2) = i` produces four SyntacticValEq's
      // (x$2 @ (_: <empty>)) = i: @scala.unchecked match {
      //   case (x$1 @ scala.Tuple2((l1 @ _), (l2 @ _))) => scala.Tuple3(x$1, l1, l2)
      // }
      // (x$1 @ (_: <empty>)) = x$2._1
      // (l1 @ (_: <empty>)) = x$2._2
      // (l2 @ (_: <empty>)) = x$2._3
      def postprocessSyntacticEnums(enums: List[Tree]): List[Tree] = {
        val buf = mutable.ListBuffer[Tree]()
        var i = 0
        while (i < enums.length) {
          val enum = enums(i)
          i += 1
          enum match {
            case SyntacticValEq(
                Bind(name, Typed(Ident(nme.WILDCARD), EmptyTree)),
                Match(Annotated(_, rhs), List(CaseDef(pat, _, marker)))) =>
              val skip = definitions.TupleClass.seq.indexOf(marker.tpe.typeSymbol) + 1
              require(skip > 0)
              i += skip
              buf += SyntacticValEq(pat, rhs)
            case _ =>
              buf += enum
          }
        }
        buf.toList
      }
      // TODO: looks like there's another problem in UnFor that sometimes appends (or fails to remove) extraneous refutability checks
      // I don't have time to catch it right now, so I'm just getting rid of those checks as a workaround
      object DropRefutabilityChecks {
        def unapply(tree: Tree): Some[Tree] = tree match {
          case Apply(
            Select(tree, nme.withFilter),
            List(Function(List(ValDef(_, name, _, _)), _)))
          if name.startsWith(nme.CHECK_IF_REFUTABLE_STRING) =>
            unapply(tree)
          case _ =>
            Some(tree)
        }
      }
      // TODO: not sure whether it's a bug or a feature, but SyntacticValFrom and SyntacticValEq sometimes produce weird trees, e.g.:
      // (x$1 @ scala.Tuple2((i1 @ _), (i2 @ _))) <- List(scala.Tuple2(1, 2), scala.Tuple2(2, 3))
      // (j @ (_: <empty>)) = i1
      // Again, there's too much to do and too little time, so I'm going to work around
      // upd. Actually, the EmptyTree thingie is the result of Ensugar, which translates all originless TypeTrees to EmptyTree
      // It looks like we'll have to copy/paste the entire UnFor here and adapt it accordingly
      object DropUselessPatterns {
        def unapply(tree: Tree): Some[Tree] = tree match {
          case tree @ Bind(name, pat) if name.startsWith("x$") => unapply(pat)
          case tree @ Bind(name, Typed(wild @ Ident(nme.WILDCARD), EmptyTree)) => Some(treeCopy.Bind(tree, name, wild))
          case tree => Some(tree)
        }
      }
      // NOTE: UnClosure creates binds that are totally unattributed
      // here we somewhat approximate the attributes to at least give the host an idea of what's going on
      def approximateAttributes(pat: Tree, rhs: Tree): Tree = pat match {
        case pat @ Bind(name: TermName, Ident(nme.WILDCARD)) if pat.tpe == null && pat.symbol == NoSymbol =>
          Bind(NoSymbol.newTermSymbol(name).setInfo(rhs.tpe), Ident(nme.WILDCARD).setType(rhs.tpe)).setType(rhs.tpe)
        case pat =>
          pat
      }
      def extractOurEnums(enums: List[Tree]): List[Enum] = enums map {
        case SyntacticValFrom(DropUselessPatterns(pat), DropRefutabilityChecks(rhs)) => Generator(approximateAttributes(pat, rhs), rhs)
        case SyntacticValEq(DropUselessPatterns(pat), DropRefutabilityChecks(rhs)) => Val(approximateAttributes(pat, rhs), rhs)
        case SyntacticFilter(tree) => Guard(tree)
      }
      tree match {
        case SyntacticFor(enums, body) => Some((extractOurEnums(postprocessSyntacticEnums(enums)), body, false))
        case SyntacticForYield(enums, body) => Some((extractOurEnums(postprocessSyntacticEnums(enums)), body, true))
        case _ => None
      }
    }
  }
}