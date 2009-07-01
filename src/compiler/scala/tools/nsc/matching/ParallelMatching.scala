/* NSC -- new Scala compiler
 * Copyright 2005-2009 LAMP/EPFL
 * Copyright 2007 Google Inc. All Rights Reserved.
 * Author: bqe@google.com (Burak Emir)
 */
// $Id$

package scala.tools.nsc.matching

import util.Position
import collection._
import mutable.BitSet
import immutable.IntMap
import MatchUtil.ListPlus._
import MatchUtil.Implicits._
import MatchUtil._

/** Translation of match expressions.
 *
 *  `p':  pattern
 *  `g':  guard
 *  `bx': body index
 *
 *   internal representation is (temp:List[Symbol], row:List[Row])
 *
 *         tmp1      tmp_n
 *    Row( p_11  ...  p_1n   g_1  b_1 ) + subst
 *
 *    Row( p_m1  ...  p_mn   g_m  b_m ) + subst
 *
 *  @author Burak Emir
 */
trait ParallelMatching extends ast.TreeDSL {
  self: transform.ExplicitOuter with PatternNodes with CodeFactory =>

  import global.{typer => _, _}
  import analyzer.Typer;
  import symtab.Flags
  import Types._
  import CODE._
 
  object Implicits {
    implicit def mkPattern(t: Tree) = Pattern(t)    
  }
  import Implicits._
  
  def DBG(msg: String) = if (settings.debug.value) println(msg)
  def logAndReturn[T](s: String, x: T): T = { log(s + x.toString) ; x }
 
  /**
   * Encapsulates a symbol being matched on.
   * 
   * sym match { ... }
   *
   * results in Scrutinee(sym).
   *
   * Note that we only ever match on Symbols, not Trees: a temporary variable
   * is created for any expressions being matched on. 
   */ 
  case class Scrutinee(val sym: Symbol) {
    import definitions._
    
    def mkList(xs: List[Symbol]) = sym :: xs
    def mkList(prefix: List[Symbol], suffix: List[Symbol]) = prefix ::: sym :: suffix
    def isDefined = sym ne NoSymbol
    def accessors = sym.caseFieldAccessors
    def id = ID(sym)
    def tpe = sym.tpe
    def pos = sym.pos
    def isChar = tpe.widen.isChar
    def isSimple = isChar || tpe.widen.isInt
    lazy val sequenceType = tpe.widen baseType SeqClass
    lazy val elementType = sequenceType match {
      case NoType => Predef.error("arg " + tpe + " not subtype of Seq[A]")
      case _      => tpe.typeArgs(0)
    }
    // for propagating "unchecked" to synthetic vars
    def flags: List[Long] = if (sym hasFlag Flags.TRANS_FLAG) List(Flags.TRANS_FLAG) else Nil

    def assertIsSubtype(other: Type) = assert(isSubType(tpe, other), "problem "+tpe+" not <: "+other)
    def casted(headType: Type)(implicit theOwner: Symbol) =
      if (tpe =:= headType) this
      else new Scrutinee(newVar(pos, headType, flags))
  }
    
  case class Pattern(tree: Tree) {
    import definitions._
    lazy val    sym = tree.symbol
    lazy val    tpe = tree.tpe
    lazy val symtpe = sym.tpe
    lazy val prefix = tpe.prefix
    lazy val tpeIfHead  = stripped.tree match {
      case p @ (_:Ident | _:Select) => singleType(stripped.prefix, stripped.sym) //should be singleton object
      case __UnApply(_,argtpe,_)    => argtpe
      case _                        => tpe
    }
    
    /** 
     * Can this pattern be part of a switch statement?
     */
    lazy val isSimpleSwitchCandidate = stripped.tree match {
      case Literal(const : Constant) if isNumeric(const.tag) =>
        const.tag match {
          case FloatTag | DoubleTag | LongTag => false
          case _ => true
        }
      case _ => false
    }
    
    /** returns if pattern can be considered a no-op test ??for expected type?? */
    final def isDefault: Boolean = isDefaultPattern(tree);
    
    final def isEquals = tpe match {
      case TypeRef(_, sym, _) => sym eq EqualsPatternClass
      case _                  => false
    }
    
    final def getAlternativeBranches: List[Tree] = {
      
      
      def get_BIND(pctx: Tree => Tree, p: Tree): List[Tree] = p match { 
        case b @ Bind(n, p)   => get_BIND((x: Tree) => pctx(treeCopy.Bind(b, n, x) setType x.tpe), p)
        case Alternative(ps)  => ps map pctx
      }
      get_BIND(x => x, tree)
    }
    
    // XXX move right place
    def allBindings: List[Bind] = allBindingsInt(tree)
    private def allBindingsInt(t: Tree): List[Bind] = t match {
      case b @ Bind(_, t) => b :: allBindingsInt(t)
      case _              => Nil
    }
    
    /** All variables binding the given pattern. */
    def boundVariables: List[Symbol] = allBindings.foldRight(List[Symbol]())(_.symbol :: _)

    /** The pattern with its variable bindings stripped. */
    def stripped: Tree = if (allBindings.isEmpty) tree else allBindings.last.body

    final def definedVars: List[Symbol] = ParallelMatching.this.definedVars(tree)

    /** returns true if pattern tests an object */
    final def isObjectTest(head: Type) =
      (sym ne null) && (sym != NoSymbol) && prefix.isStable && (head =:= singleType(prefix, sym))
  }
  
  case class Patterns(scrut: Scrutinee, ps: List[Pattern]) {
    private lazy val column = ps.map(_.tree)
    lazy val head = ps.head
    lazy val tail = Patterns(scrut, ps.tail)
    lazy val last = ps.last
    lazy val headType = head.tpeIfHead
    lazy val isCaseHead = headType.isCaseClass
    lazy val dummies = if (isCaseHead) getDummies(headType.typeSymbol.caseFieldAccessors.length) else Nil
    lazy val size = ps.length
    
    def apply(i: Int): Tree = ps(i).tree
    def zip() = column.zipWithIndex
    def zip[T](ys: List[T]) = column zip ys
    
    def isObjectTest(pat: Pattern)  = pat isObjectTest headType
    def isObjectTest(pat: Tree)     = Pattern(pat) isObjectTest headType
    // an unapply for which we don't need a type test
    def isUnapplyHead = head.tree match {
      case __UnApply(_,argtpe,_)  => scrut.tpe <:< argtpe
      case _                      => false
    }
    
    def isSimpleSwitch: Boolean =
      scrut.isSimple && ps.init.forall(_.isSimpleSwitchCandidate) &&
      // TODO: This needs to also allow the case that the last is a compatible type pattern.
      (last.isSimpleSwitchCandidate || last.isDefault)
    
    def mkRule(rest: Rep)(implicit rep: RepFactory): RuleApplication =
      logAndReturn("mkRule: ", head match {
          case x if x.isEquals          => new MixEquals(this, rest)
          case Pattern(x: ArrayValue)   => if (isRightIgnoring(x)) new MixSequenceStar(this, rest)
                                           else new MixSequence(this, rest)
          case _ if isSimpleSwitch      => new MixLiterals(this, rest)
          case _ if isUnapplyHead       => new MixUnapply(this, rest)
          case _                        => new MixTypes(this, rest)
        }
      )
  }
 
  /**
   * Class encapsulating a guard expression in a pattern match:
   *   case ... if(tree) => ...
   */ 
  case class Guard(tree: Tree) {
    def isEmpty = tree eq EmptyTree
    def duplicate = Guard(tree.duplicate)
  }
  val NoGuard = Guard(EmptyTree)

  /** picks which rewrite rule to apply
   *  @precondition: column does not contain alternatives (ensured by initRep)
   */
  def MixtureRule(scrut: Scrutinee, column: List[Tree], rest: Rep)(implicit rep: RepFactory): RuleApplication =
    Patterns(scrut, column map Pattern) mkRule rest

  sealed abstract class RuleApplication(rep: RepFactory) {
    def scrut: Scrutinee
    implicit def typer = rep.typer

    // used in MixEquals and MixSequence
    final protected def repWithoutHead(pats: Patterns, rest: Rep)(implicit theOwner: Symbol): Rep = {
      val nfailrow = List.map2(pats.tail.ps, rest.row.tail)((p, r) => r.insert(p))
      rep.make(scrut.mkList(rest.temp), nfailrow)
    }

    /** translate outcome of the rule application into code (possible involving recursive application of rewriting) */
    def tree(implicit theOwner: Symbol, failTree: Tree): Tree 
  }

  case class ErrorRule(implicit rep:RepFactory) extends RuleApplication(rep) {
    def scrut: Scrutinee = impossible
    final def tree(implicit theOwner: Symbol, failTree: Tree) = failTree
  }

  /**  {case ... if guard => bx} else {guardedRest} */
  case class VariableRule(subst: Bindings, guard: Guard, guardedRest: Rep, bx: Int)(implicit rep:RepFactory) extends RuleApplication(rep) {
    def scrut: Scrutinee = impossible
    final def tree(implicit theOwner: Symbol, failTree: Tree): Tree = {
      val body      = typer typed rep.requestBody(bx, subst)
      def vdefs     = subst.targetParams
      def typedElse = guardedRest.toTree
      def typedIf   = typer typed (IF (guard.duplicate.tree) THEN body ELSE typedElse)

      if (guard.isEmpty) body
      else typer typed squeezedBlock(vdefs, typedIf)
    }
  } 

  /** mixture rule for literals
   */
  class MixLiterals(val pats: Patterns, val rest: Rep)(implicit rep:RepFactory) extends RuleApplication(rep) {
    // e.g. (1,1) (1,3) (42,2) for column { case ..1.. => ;; case ..42..=> ;; case ..1.. => }
    var defaultV: Set[Symbol] = emptySymbolSet
    var defaultIndexSet = new BitSet(pats.size)
    
    def insertDefault(tag: Int, vs: Traversable[Symbol]) {
      defaultIndexSet += tag
      defaultV = defaultV ++ vs
    }

    def haveDefault: Boolean    = !defaultIndexSet.isEmpty 
    def defaultRows: List[Row]  = defaultIndexSet.toList reverseMap grabRow

    protected var tagIndices = IntMap.empty[List[Int]]
    protected def grabRow(index: Int): Row = {
      val r = rest.row(index)
      if (defaultV.isEmpty) r
      else r.insert2(Nil, pats(index).boundVariables, scrut.sym)  // get vars
    }

    /** inserts row indices using in to list of tagIndices */
    protected def tagIndicesToReps(implicit theOwner: Symbol) : List[(Int, Rep)] = 
      tagIndices map { case (k, v) => (k, rep.make(rest.temp, v.reverseMap(grabRow) ::: defaultRows)) } toList

    protected def defaultsToRep(implicit theOwner: Symbol) = rep.make(rest.temp, defaultRows)

    protected def insertTagIndexPair(tag: Int, index: Int) =
      tagIndices = tagIndices.update(tag, index :: tagIndices.getOrElse(tag, Nil))

    /** returns 
     *  @return list of continuations, 
     *  @return variables bound to default continuation,  
     *  @return optionally, a default continuation
     **/
    def getTransition(implicit theOwner: Symbol): (List[(Int,Rep)], Set[Symbol], Option[Rep]) =
      (tagIndicesToReps, defaultV, if (haveDefault) Some(defaultsToRep) else None)

    val Patterns(scrut, patterns) = pats
    
    val varMap: List[(Int, List[Symbol])] =
      (for ((x, i) <- pats.zip) yield x.stripped match {
        case p @ LIT(c: Int)          => insertTagIndexPair(c, i)           ; Some(c,       definedVars(x))
        case p @ LIT(c: Char)         => insertTagIndexPair(c.toInt, i)     ; Some(c.toInt, definedVars(x))
        case p if isDefaultPattern(p) => insertDefault(i, x.boundVariables) ; None
      }) . flatMap(x => x) . reverse

    // lazy
    private def bindVars(Tag: Int, orig: Bindings): Bindings  = {
      def myBindVars(rest:List[(Int,List[Symbol])], bnd: Bindings): Bindings  = rest match {
        case Nil => bnd
        case (Tag,vs)::xs => myBindVars(xs, bnd.add(vs, scrut.sym))
        case (_,  vs)::xs => myBindVars(xs, bnd)
      }
      myBindVars(varMap, orig)
    }

    final def tree(implicit theOwner: Symbol, failTree: Tree): Tree = {
      val (branches, defaultV, defaultRep) = this.getTransition // tag body pairs
      val cases = for ((tag, r) <- branches) yield {
        val r2 = rep.make(r.temp, r.row map (x => x rebind bindVars(tag, x.subst)))
        
        CASE(Literal(tag)) ==> r2.toTree
      }
      lazy val ndefault         = defaultRep.map(_.toTree) getOrElse failTree
      lazy val casesWithDefault = cases ::: List(CASE(WILD(definitions.IntClass.tpe)) ==> ndefault)
      
      cases match {
        case CaseDef(lit,_,body) :: Nil   => IF (scrut.id ANY_== lit) THEN body ELSE ndefault
        case _                            =>
          val target: Tree = if (scrut.isChar) scrut.id DOT nme.toInt else scrut.id // chars to ints
          target MATCH (casesWithDefault: _*)
      }
    }
    override def toString = {
      "MixLiterals {\n  pats: %s\n  varMap: %s\n}".format(
        pats, varMap
      )
    }
  }
  
  /** mixture rule for unapply pattern
   */
  class MixUnapply(val pats: Patterns, val rest: Rep)(implicit rep: RepFactory)
  extends RuleApplication(rep) {
    val Patterns(scrut, patterns) = pats
    val Strip(vs, unapp) = pats.head.tree
    lazy val ua @ UnApply(app @ Apply(fxn, appargs), args) = unapp
    
    object sameUnapplyCall {
      def sameFunction(fn1: Tree) = fxn.symbol == fn1.symbol && (fxn equalsStructure fn1)
      def unapply(t: Tree) = t match {
        case UnApply(Apply(fn1, _), args) if sameFunction(fn1)  => Some(args)
        case _                                                  => None
      }
    }

    def newVarCapture(pos: Position,tpe: Type)(implicit theOwner:Symbol) = newVar(pos, tpe, scrut.flags)

    /** returns (unapply-call, success-rep, optional fail-rep*/
    final def getTransition(implicit theOwner: Symbol): (Tree, List[Tree], Rep, Option[Rep]) = {
      val ures        = newVarCapture(ua.pos, app.tpe)
      val rhs         = Apply(fxn, scrut.id :: appargs.tail) setType ures.tpe
      val uacall      = typedValDef(ures, rhs)
      val zipped      = pats zip rest.row
      val nrowsOther  = zipped.tail flatMap { 
        case (Stripped(sameUnapplyCall(_)), _)  => Nil
        case (pat, r)                           => List(r insert pat)
      }
        
      val nrepFail = 
        if (nrowsOther.isEmpty) None
        else Some(rep.make(scrut mkList rest.temp, nrowsOther))
        
      def mkTransition(vdefs: List[Tree], ntemps: List[Symbol], nrows: List[Row]) =
        (uacall, vdefs, rep.make(scrut.mkList(ntemps, rest.temp), nrows), nrepFail)          
        
      // Second argument is number of dummies to prepend in the default case
      def mkNewRows(sameFilter: (List[Tree]) => List[Tree], dum: Int) =
        for ((pat @ Strip(vs, p), r) <- zipped) yield p match {
          case sameUnapplyCall(args)  => r.insert2(sameFilter(args) ::: List(EmptyTree), vs, scrut.sym)
          case _                      => r insert (getDummies(dum) ::: List(pat))
        }
      
      import definitions.{ getProductArgs, productProj }
      // 0 args => Boolean, 1 => Option[T], >1 => Option[? <: ProductN[T1,...,Tn]]
      args.length match {
        case 0  =>
          mkTransition(Nil, Nil, mkNewRows((xs) => Nil, 0))
          
        case 1 =>
          val vtpe  = app.tpe typeArgs 0
          val vsym  = newVarCapture(ua.pos, vtpe)
          val nrows = mkNewRows(xs => List(xs.head), 1)
          val vdef  = typedValDef(vsym, fn(ID(ures), nme.get))
          
          mkTransition(List(vdef), List(vsym), nrows)
        
        case _ =>
          val uresGet   = newVarCapture(ua.pos, app.tpe typeArgs 0)
          val vdefHead  = typedValDef(uresGet, fn(ID(ures), nme.get))
          val ts        = getProductArgs(uresGet.tpe).get
          val nrows     = mkNewRows(identity, ts.size)
          
          val (vdefs: List[Tree], vsyms: List[Symbol]) = List.unzip(
            for ((vtpe, i) <- ts.zip((1 to ts.size).toList)) yield {
              val vchild  = newVarCapture(ua.pos, vtpe)
              val accSym  = productProj(uresGet, i)
              val rhs     = typer typed fn(ID(uresGet), accSym)

              (typedValDef(vchild, rhs), vchild)
            })
            
          mkTransition(vdefHead :: vdefs, vsyms, nrows)
      }
    } /* def getTransition(...) */

    final def tree(implicit theOwner: Symbol, failTree: Tree) = {
      val (uacall, vdefs, srep, frep) = this.getTransition
      val succ = srep.toTree
      val fail = frep map (_.toTree) getOrElse (failTree)
      val cond = 
        if (uacall.symbol.tpe.isBoolean) typer typed ID(uacall.symbol)
        else uacall.symbol IS_DEFINED

      typer typed (squeezedBlock(
        List(rep handleOuter uacall),
        IF(cond) THEN squeezedBlock(vdefs, succ) ELSE fail
      ))
    }
    override def toString = {
      "MixUnapply {\n  pats: %s\n  ua: %s\n}".format(
        pats, ua
      )
    }
  }

  /** handle sequence pattern and ArrayValue (but not star patterns)
   */
  sealed class MixSequence(val pats: Patterns, val rest: Rep)(implicit rep: RepFactory) extends RuleApplication(rep) {
    val Patterns(scrut, patterns) = pats
    def compareOp                 = pats.head.tpe member nme.lengthCompare  // symbol for length comparison
        
    final def removeStar(xs: List[Tree]): List[Tree] = 
      xs.init ::: makeBind(xs.last.boundVariables, WILD(scrut.sequenceType)) :: Nil
    
    protected def getSubPatterns(len: Int, x: Tree): Option[List[Tree]] = x match {
      case av @ ArrayValue(_,xs) if (!isRightIgnoring(av) && xs.length == len)   => Some(xs ::: List(EmptyTree))
      case av @ ArrayValue(_,xs) if ( isRightIgnoring(av) && xs.length == len+1) => Some(removeStar(xs)) // (*) 
      case EmptyTree | Ident(nme.WILDCARD)                                       => Some(getDummies(len+1))
      case _                                                                     => None
    }

    protected def makeSuccRep(vs: List[Symbol], tail: Symbol, nrows: List[Row])(implicit theOwner: Symbol) =
      rep.make(vs ::: tail :: rest.temp, nrows)
        
    /** True if x must be checked even if y failed to match after passing its length test
      * (the conditional supplied by getCond)
      */
    protected def mustCheck(x:Tree, y:Tree): Boolean =
      isDefaultPattern(x) || ((x, y) match {
        case (av @ ArrayValue(_,xs), bv @ ArrayValue(_,ys)) => 
          // if they are both right-ignoring, x must be more general if it has fewer literals - bug #889
          if (isRightIgnoring(av) && isRightIgnoring(bv) && xs.length < ys.length) true
          // otherwise, x is more general only if it is y plus a star      
          else isRightIgnoring(av) && !isRightIgnoring(bv) && xs.length == ys.length+1                   // see (*) 
        case _ => 
          false
      })
  
    // context (to be used in IF), success and failure Rep 
    def getTransition(implicit theOwner: Symbol): (Tree => Tree => Tree, Rep, Rep) = { 
      scrut assertIsSubtype pats.head.tpe   // scrut.tpe <:< column.head.tpe confirmed by assertion
      val av @ ArrayValue(_, xs)  = pats.head.tree
      val ys                      = if (isRightIgnoring(av)) xs.init else xs
      val vs                      = ys map (y => newVar(y.stripped.pos, scrut.elementType))
      def scrutineeCopy           = scrut.id.duplicate
      
      lazy val tail               = newVar(scrut.pos, scrut.sequenceType)
      lazy val lastBinding        = typedValDef(tail, scrutineeCopy DROP ys.size)
      def elemAt(i: Int)          = typer typed ((scrutineeCopy DOT (scrutineeCopy.tpe member nme.apply))(LIT(i)))
      
      val bindings =
        (vs.zipWithIndex map { case (v,i) => typedValDef(v, elemAt(i)) }) ::: List(lastBinding)
              
      val (nrows, frows) = List.unzip(
        for ((c, row) <- pats.zip(rest.row)) yield getSubPatterns(ys.size, c) match {
          case Some(ps) => (Some(row insert ps), if (mustCheck(c, av)) Some(row insert c) else None)
          case None     => (None, Some(row insert c))
        })
    
      val succRep = makeSuccRep(vs, tail, nrows.flatMap(x => x))
      val failRep = rep.make(scrut mkList rest.temp, frows.flatMap(x => x))
      def context = { thenp: Tree => { elsep: Tree =>
        IF (getPrecondition(scrutineeCopy, xs.length)) THEN squeezedBlock(bindings, thenp) ELSE elsep
      }}
      (context, succRep, failRep)
    }

    // precondition for matching: sequence is exactly length of arg
    protected def getPrecondition(tree: Tree, lengthArg: Int) =
      typer typed (((tree.duplicate DOT compareOp)(LIT(lengthArg))) ANY_== ZERO)

    final def tree(implicit theOwner: Symbol, failTree: Tree) = this.getTransition match {
      case (cx, succRep, failRep) => cx(succRep.toTree)(failRep.toTree)
    }
  }

  /** handle sequence pattern and ArrayValue with star patterns
   */
  final class MixSequenceStar(pats: Patterns, rest:Rep)(implicit rep:RepFactory) extends MixSequence(pats, rest) {    
    // in principle, we could optimize more, but variable binding gets complicated (@todo use finite state methods instead)
    override def getSubPatterns(minlen:Int, x:Tree) = x match {
      case av @ ArrayValue(_,xs) if (!isRightIgnoring(av) && xs.length   == minlen) =>  // Seq(p1,...,pN)
        Some(xs ::: gen.mkNil :: EmptyTree :: Nil)
      case av @ ArrayValue(_,xs) if ( isRightIgnoring(av) && xs.length-1 == minlen) =>  // Seq(p1,...,pN,_*)
        Some(                                   removeStar(xs) ::: EmptyTree :: Nil)
      case av @ ArrayValue(_,xs) if ( isRightIgnoring(av) && xs.length-1  < minlen) =>  // Seq(p1..,pJ,_*)   J < N
        Some(                                  getDummies(minlen + 1) ::: x :: Nil)
      case EmptyTree | Ident(nme.WILDCARD)      => 
        Some(                                  getDummies(minlen + 1          + 1))
      case _                                    => 
        None

    }

    override protected def makeSuccRep(vs:List[Symbol], tail:Symbol, nrows:List[Row])(implicit theOwner: Symbol) =
      rep.make(scrut.mkList(vs ::: List(tail), rest.temp), nrows)

    // precondition for matching - sequence is at least length of (arg - 1)
    // I believe the "- 1" is to remove the _* term.
    override protected def getPrecondition(tree: Tree, lengthArg: Int) =
      typer typed (((tree.duplicate DOT compareOp)(LIT(lengthArg - 1))) ANY_>= ZERO)
  }

  
  // @todo: equals test for same constant
  class MixEquals(val pats: Patterns, val rest: Rep)(implicit rep: RepFactory) extends RuleApplication(rep) {
    val Patterns(scrut, patterns) = pats
    val head = pats.head
    
    /** condition (to be used in IF), success and failure Rep */
    final def getTransition(implicit theOwner: Symbol): (Tree, Rep, Symbol, Rep) = {
      val vlue = (head.tpe: @unchecked) match {
        case TypeRef(_,_,List(SingleType(pre,sym))) => REF(pre, sym)
        case TypeRef(_,_,List(PseudoType(o)))       => o.duplicate
      }
      assert(vlue.tpe ne null, "value tpe is null")
      val vs        = head.boundVariables
      val nsuccFst  = rest.row.head.insert2(List(EmptyTree), vs, scrut.sym)
      val fLabel    = theOwner.newLabel(scrut.pos, newName(scrut.pos, "failCont%")) // warning, untyped
      val sx        = rep.shortCut(fLabel) // register shortcut
      val nsuccRow  = nsuccFst :: Row(getDummies( 1 /* scrutinee */ + rest.temp.length), NoBinding, NoGuard, sx) :: Nil

      // todo: optimize if no guard, and no further tests
      val nsucc = rep.make(scrut mkList rest.temp, nsuccRow)
      val nfail = repWithoutHead(pats, rest)
      
      (typer.typed(scrut.id ANY_== vlue), nsucc, fLabel, nfail)
    }

    final def tree(implicit theOwner: Symbol, failTree: Tree) = {
      val (cond, srep, fLabel, frep) = this.getTransition
      val cond2 = typer typed (rep handleOuter cond)
      val fail  = typer typed frep.toTree
      fLabel setInfo MethodType(Nil, fail.tpe)

      typer typed { IF (cond2) THEN srep.toTree ELSE LabelDef(fLabel, Nil, fail) }
    }
  }

  /** mixture rule for type tests
  **/
  class MixTypes(val pats: Patterns, val rest: Rep)(implicit rep: RepFactory) extends RuleApplication(rep) {
    val Patterns(scrut, patterns) = pats
  
    private def subpatterns(p: Tree): List[Tree] = p match {
      case Bind(_, p)                                                 => subpatterns(p)
      case app @ Apply(fn, ps) if app.tpe.isCaseClass && fn.isType    => if (pats.isCaseHead) ps else pats.dummies
      case Apply(fn, xs) if !xs.isEmpty || fn.isType                  => abort("strange Apply")
      case _                                                          => pats.dummies
    }

    // moreSpecific: more specific patterns
    //     subsumed: more general patterns (subsuming current), row index and subpatterns
    //    remaining: remaining, row index and pattern
    def join[T](xs: List[Option[T]]): List[T] = xs.flatMap(x => x)
    val (moreSpecific, subsumed, remaining) : (List[Tree], List[(Int, List[Tree])], List[(Int, Tree)]) = unzip3(
      for ((pat @ Stripped(spat), j) <- pats.zip) yield {
        def eqHead(tpe: Type) = pats.headType =:= tpe
        def alts(yes: Tree, no: Tree) = if (eqHead(pat.tpe)) yes else no

        lazy val isDef                = isDefaultPattern(pat)
        lazy val cmp: TypeComparison  = spat.tpe cmp pats.headType  // contains type info about pattern's type vs. head pattern
        lazy val dummy                = (j, pats.dummies)
        lazy val pass                 = (j, pat)
        lazy val subs                 = (j, subpatterns(pat))
        
        import cmp._  // imports xIsaY, etc.
        
        // each pattern will yield a triple of options corresponding to the three lists,
        // which will be flattened down to the values
        implicit def mkOpt[T](x: T): Option[T] = Some(x)    // limits noise from Some(value)
        (spat match {
          case LIT(null) if !eqHead(spat.tpe)               => (None, None, pass)           // special case for constant null
          case _ if pats.isObjectTest(pat)                  => (EmptyTree, dummy, None)     // matching an object
          case Typed(p @ Stripped(_: UnApply), _) if xIsaY  => (p, dummy, None)             // <:< is never <equals>            
          case q @ Typed(pp, _) if xIsaY                    => (alts(pp, q), dummy, None)   // never =:= for <equals>
          // this next line inflicted great suffering upon innocents
          // case z: UnApply                                   => (None, None, pass)
          // XXX note - while removing the above line fixed the abhorrent "wrong answer" behavior
          // illustrated in bug #1697, it then led to "consistency problem in target generation"
          // failures with extractors in the first position (see classifyPat.)
          case z: UnApply                                   => (EmptyTree, dummy, pass)
          case _ if erased.xIsaY || xIsaY && !isDef         => (alts(EmptyTree, pat), subs, None) // never =:= for <equals>          
          case _ if erased.yIsaX || yIsaX || isDef          => (EmptyTree, dummy, pass)     // subsuming (matched *and* remaining pattern)
          case _                                            => (None, None, pass)
        }) : (Option[Tree], Option[(Int, List[Tree])], Option[(Int, Tree)])
      }
    ) match { case (x,y,z) => (join(x), join(y), join(z)) }

    override def toString = {
      "MixTypes(%s: %s) {\n  moreSpecific: %s\n  subsumed: %s\n  remaining: %s\n}".format(
        scrut, scrut.tpe, moreSpecific, subsumed, remaining
      )
    }
    
    /** returns casted symbol, success matrix and optionally fail matrix for type test on the top of this column */
    final def getTransition(implicit theOwner: Symbol): (Scrutinee, Rep, Option[Rep]) = {
      val casted = scrut casted pats.headType
      
      // succeeding => transition to translate(subsumed) (taking into account more specific)
      val nmatrix = {
        val ms = moreSpecific exists (_ != EmptyTree)
        val accessorTemps = 
          if (!pats.isCaseHead) Nil 
          else casted.accessors.map(meth => newVar(scrut.pos, casted.tpe.memberType(meth).resultType, scrut.flags))
        val subtestTemps = if (!ms) Nil else List(casted.sym)
        val subtests =
          if (!ms) subsumed
          else moreSpecific.zip(subsumed) map { case (mspat, (j, pats)) => (j, mspat::pats) }
        val ntriples = for ((j, ps) <- subtests) yield {
          val (vs, thePat) = strip(pats(j))
          rest.row(j).insert2(ps, vs, casted.sym)
        }
        rep.make(subtestTemps ::: accessorTemps ::: rest.temp, ntriples)
      }
      
      // fails      => transition to translate(remaining)
      val nmatrixFail: Option[Rep] = {
        val ntemps   = scrut mkList rest.temp
        val ntriples = for ((j, pat) <- remaining) yield (rest row j) insert pat
        if (ntriples.isEmpty) None else Some(rep.make(ntemps, ntriples))
      }
      
      (casted, nmatrix, nmatrixFail)
    }

    final def tree(implicit theOwner: Symbol, failTree: Tree): Tree = {
      val (casted, srep, frep) = this.getTransition
      val cond = condition(casted.tpe, scrut)
      val succ = srep.toTree
      val fail = frep map (_.toTree) getOrElse (failTree)
      
      // dig out case field accessors that were buried in (***)
      val cfa       = if (!pats.isCaseHead) Nil else casted.accessors
      val caseTemps = srep.temp match { case x :: xs if x == casted.sym => xs ; case x => x }
      def needCast  = if (casted.sym ne scrut.sym) List(VAL(casted.sym) === (scrut.id AS_ANY casted.tpe)) else Nil
      val vdefs     = needCast ::: (
        for ((tmp, accessor) <- caseTemps zip cfa) yield
          typedValDef(tmp, typer typed fn(casted.id, accessor))
      )
      
      typer typed (IF (cond) THEN squeezedBlock(vdefs, succ) ELSE fail)
    }
  }

  case class Row(pat: List[Tree], subst: Bindings, guard: Guard, bx: Int) {
    def insert(h: Tree)                               = copy(pat = h :: pat)
    def insert(hs: List[Tree])                        = copy(pat = hs ::: pat)    // prepends supplied tree
    def replace(hs: List[Tree])                       = copy(pat = hs)            // substitutes for patterns
    def rebind(b: Bindings)                           = copy(subst = b)           // substitutes for bindings
    def insert2(hs: List[Tree], vs: Iterable[Symbol], temp: Symbol) =             // prepends and prepends
      copy(pat = hs ::: pat, subst = subst.add(vs, temp))

    def insert(p: Pattern)                            = copy(pat = p.tree :: pat) // transitioning to patterns
    
    /** returns true if the patterns in this row cover a type symbols "combination" and there is no guard
     *  @param comb pairs of (column index, type symbol)
     */
    def covers(comb: List[Combo]) = {
      lazy val results = for (Combo(i, sym) <- comb ; val p = pat(i).stripped) yield p match {
        case _ if isDefaultPattern(p)   => true
        case _: UnApply | _: ArrayValue => true
        case _                          => p.tpe coversSym sym
      }
      
      guard.isEmpty && (results forall (true ==))
    }    

    // returns this row with alternatives expanded
    def expand(classifyPat: (Tree, Int) => Tree): List[Row] = {
      def isAlternative(p: Tree): Boolean = p.stripped match { 
        case Alternative(ps) => true 
        case _               => false
      }
      def getAlternativeBranches(p: Tree): List[Tree] = {
        def get_BIND(pctx:Tree => Tree, p:Tree): List[Tree] = p match { 
          case b @ Bind(n,p)   => get_BIND((x: Tree) => pctx(treeCopy.Bind(b, n, x) setType x.tpe), p)
          case Alternative(ps) => ps map pctx
        }
        get_BIND(x => x, p)
      }
      
      val indexOfAlternative            = pat findIndexOf isAlternative      
      val pats: List[Tree]              = List.map2(pat, pat.indices.toList)(classifyPat)
      lazy val (prefix, alts :: suffix) = pats splitAt indexOfAlternative
      lazy val alternativeBranches      = getAlternativeBranches(alts) map (x => replace(prefix ::: x :: suffix))

      if (indexOfAlternative == -1) List(replace(pats)) else alternativeBranches
    }
  }

  class RepFactory(val handleOuter: Tree => Tree)(implicit val typer : Typer) {
    var vss: List[List[Symbol]] = _
    val labels = new collection.mutable.HashMap[Int, Symbol]()
    var targets: List[Tree] = _
    var reached: BitSet = _ 
    var shortCuts: List[Symbol] = Nil

    final def make(temp:List[Symbol], row:List[Row], targets: List[Tree], vss:List[List[Symbol]])(implicit theOwner: Symbol): Rep = {
      // ensured that labels(i) eq null for all i, cleanup() has to be called after translation
      this.targets      = targets
      this.vss          = vss
      this.reached      = new BitSet(targets.length)
      make(temp, row)
    }

    final def shortCut(theLabel:Symbol): Int = {
      shortCuts = shortCuts ::: List(theLabel)
      -shortCuts.length
    }

    final def cleanup(tree: Tree)(implicit theOwner: Symbol): Tree = { 
      object lxtt extends Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case blck @ Block(vdefs, ld @ LabelDef(name,params,body)) =>
            val bx = labelIndex(ld.symbol)
            if (bx >= 0 && !isReachedTwice(bx)) squeezedBlock(vdefs,body)
            else blck

          case If(cond, TRUE, FALSE) => 
            super.transform(cond)
          case If(cond1, If(cond2, thenp, elsep1), elsep2) if (elsep1 equalsStructure elsep2) => 
            super.transform( IF (cond1 AND cond2) THEN thenp ELSE elsep1 )              
          case If(cond1, If(cond2, thenp, Apply(jmp, Nil)), ld: LabelDef) if (jmp.symbol eq ld.symbol) => 
            super.transform( IF (cond1 AND cond2) THEN thenp ELSE ld )
          case t => super.transform(t)
        }
      }
      val res = lxtt transform tree
      cleanup()
      res
    }
    
    final def cleanup() {
      labels.clear()
      reached = null
      shortCuts = Nil
    }
    final def isReached(bx: Int)        = labels contains bx
    final def markReachedTwice(bx: Int) { reached += bx }
    
    /** @pre bx < 0 || labelIndex(bx) != -1 */
    final def isReachedTwice(bx: Int) = (bx < 0) || reached(bx)
    
    /* @returns bx such that labels(bx) eq label, -1 if no such bx exists */
    final def labelIndex(label: Symbol): Int = {
      for ((k, v) <- labels ; if v eq label) return k 
      -1
    }

    /** first time bx is requested, a LabelDef is returned. next time, a jump.
     *  the function takes care of binding
     */
    final def requestBody(bx: Int, subst: Bindings)(implicit theOwner: Symbol): Tree = {
      if (bx < 0) { // is shortcut
        val jlabel = shortCuts(-bx-1)
        return Apply(ID(jlabel), Nil)
      }
      if (!isReached(bx)) { // first time this bx is requested
        // might be bound elsewhere
        val (vsyms, vdefs) : (List[Symbol], List[Tree]) = List.unzip(
          for (v <- vss(bx) ; substv <- subst(v)) yield 
            (v, typedValDef(v, substv))
        )
              
        val body    = targets(bx)
        // @bug: typer is not able to digest a body of type Nothing being assigned result type Unit
        val tpe     = if (body.tpe.isNothing) body.tpe else resultType
        val label   = theOwner.newLabel(body.pos, "body%"+bx) setInfo MethodType(vsyms, tpe)
        labels(bx)  = label

        return body match {
          case _: Throw | _: Literal  => squeezedBlock(vdefs, body.duplicate setType tpe)
          case _                      => squeezedBlock(vdefs.reverse, LabelDef(label, vsyms, body setType tpe))
        }
      }
 
      // if some bx is not reached twice, its LabelDef is replaced with body itself
      markReachedTwice(bx)
      
      val args  = vss(bx) map subst flatten
      val label = labels(bx)
      val body  = targets(bx)
      val fmls  = label.tpe.paramTypes

      // same number of arguments as formal parameters
      if (fmls.length != args.length) {
        cunit.error(body.pos, "consistency problem compiling %s!\nCannot jump to %s(%s) with args (%s)".format(
          cunit.source, label, fmls, args)
        )
        throw FatalError("consistency problem")
      }
      // each argument conforms to formal
      for ((f, a) <- fmls zip args ; if !(a.tpe <:< f)) {
        cunit.error(body.pos, "consistency problem! %s <:< %s is false".format(a.tpe, f))
        throw FatalError("consistency problem")
      }
      log("no consistency problem: calling %s(%s) with (%s)".format(label, fmls, args))

      body match {
        case _: Throw | _: Literal =>       // might be bound elsewhere (see `x @ unapply')
          val vdefs = for (v <- vss(bx) ; substv <- subst(v)) yield typedValDef(v, substv)
          squeezedBlock(vdefs, body.duplicate setType resultType)
        case _ =>
          Apply(ID(label), args)
      }
    }

    /** the injection here handles alternatives and unapply type tests */
    final def make(temp: List[Symbol], row1: List[Row])(implicit theOwner: Symbol): Rep = {
      // equals check: call singleType(NoPrefix, o.symbol) `stpe'. Then we could also return 
      // `typeRef(definitions.ScalaPackageClass.tpe, definitions.EqualsPatternClass, List(stpe))'
      // and force an equality check. However, exhaustivity checking would not work anymore.
      // so first, extend exhaustivity check to equalspattern

      // Martin: I am not really sure what stype is doing, but it caused aliases.scala to fail
      // The problem is if a val has a singleType of some other module. Then isModule is true and
      // sType is called. But it ends up mixing the prefix of the other module with the val symbol
      // which then causes erasure to be confused.
      def sType(o: Tree) = o.tpe match {
        case st @ SingleType(pre, sym) => st
        case _ => singleType(o.tpe.prefix, o.symbol)
      }
      def equalsCheck(o: Tree) = if (o.symbol.isValue) singleType(NoPrefix, o.symbol) else sType(o)
      def isModule(o: Tree) = o.symbol.isModule || o.tpe.termSymbol.isModule
      
      def doSelect(o: Tree, path: Tree) = (path, path.tpe) match {
        case (_, t: ThisType)     => singleType(t, o.symbol)            // cases 2/3 are e.g. `case Some(p._2)' in s.c.jcl.Map
        case (_: Apply, _)        => PseudoType(o)                      // outer-matching: test/files/pos/t154.scala
        case _                    => singleType(sType(path), o.symbol)  // old
      }
      def applyType(o: Tree, fn: Tree): Type = fn match { 
        case _ if isModule(o)   => sType(o)
        case Select(path, _)    => doSelect(o, path) // XXX ?
        case o: Ident           => equalsCheck(o)
      }
    
      def classifyPat(opat: Tree, j: Int): Tree = {
        val (vs, strippedPat) = strip(opat) match { case (vset, pat) => (vset.toList, pat) }
        // @todo: rewrite UnApply(Apply(fn, _), _) case, using __UnApply instead of UnApply like so:
        // case  ua @ __UnApply(_,argtpe,_) =>
        //   val ua = prepat
        //   val npat = (if (temp(j).tpe <:< argtpe) ua else Typed(ua,TypeTree(argtpe)).setType(argtpe))
        //   pats = (makeBind(vs, npat) setType argtpe)::pats
        
        def doUnapplyApply(ua: UnApply, fn: Tree) = {
          val MethodType(List(arg, _*), _) = fn.tpe
          val argtpe = arg.tpe
          val npat =
            if (temp(j).tpe <:< argtpe) ua 
            else Typed(ua, TypeTree(argtpe)) setType argtpe
            
          logAndReturn("doUnapplyApply: ", makeBind(vs, npat) setType argtpe)
        }
        def doApplyFunction(o: Tree, fn: Tree) = {
          val stpe = applyType(o, fn)
          val ttst = mkEqualsRef(List(stpe))
          
          logAndReturn("doApplyFunction: ", makeBind(vs, Typed(WILD(ttst), TypeTree(stpe)) setType ttst))
        }
                
        // NOTE - until the extractor issues are completely understood with a testing framework
        // which guarantees right answers, we will be doing our extractions manually.
        strippedPat match {
          case _: Alternative                       => opat
          case Typed(p @ Stripped(_: UnApply), tpt) => if (temp(j).tpe <:< tpt.tpe) makeBind(vs, p)
                                                       else opat
          // case Ident_Or_Empty()                     => opat    // this doesn't work - see notes in PatternNode
          case Ident(nme.WILDCARD) | EmptyTree      => opat
          case _: Literal | _: Typed                => opat
          case o: Ident                             => mkTypedBind(vs, equalsCheck(o))  // Ident(_) != nme.WILDCARD
          case o: Select                            => mkTypedBind(vs, equalsCheck(o))
          case o: This                              => opat
          // @pre for UnApply_TypeApply: is not right-ignoring (no star pattern) ; no exhaustivity check
          case UnApply_TypeApply(tptArg, xs)        => temp(j) setFlag Flags.TRANS_FLAG
                                                       makeBind(vs, normalizedListPattern(xs, tptArg.tpe))
          case ua @ UnApply(Apply(fn, _), _)        => doUnapplyApply(ua, fn)
          case o if Apply_Function.unapply(o).isDefined =>
            doApplyFunction(o, Apply_Function.unapply(o).get)
          // case o @ Apply_Function(fn)               => doApplyFunction(o, fn)
          // case Apply_Value(pre, sym)                => mkEmptyTreeBind(vs, mkEqualsRef(List(singleType(pre, sym))))   
          case x if Apply_Value.unapply(x).isDefined =>
            val Apply_Value(pre, sym) = x
            mkEmptyTreeBind(vs, mkEqualsRef(List(singleType(pre, sym))))
          
          // case Apply_CaseClass(tpe, args)           => if (args.isEmpty) mkEmptyTreeBind(vs, tpe) else opat
          case x if Apply_CaseClass.unapply(x).isDefined =>
            val Apply_CaseClass(tpe, args) = x
            if (args.isEmpty) mkEmptyTreeBind(vs, tpe) else opat
          case _: ArrayValue                        => opat
          case x                                    => throw new Exception("Unexpected pattern: " + x.getClass + " => " + x)
        }
      }
            
      val row = row1 flatMap (_ expand classifyPat)
      if (row.length != row1.length) make(temp, row)  // recursive call if any change
      else Rep(temp, row).init
    }
  }
  
  case class Combo(index: Int, sym: Symbol)

  case class Rep(val temp: List[Symbol], val row: List[Row]) {
    /** converts this to a tree - performs recursive call to translation in the process to get sub reps
     */
    final def toTree(implicit theOwner: Symbol, failTree: Tree, rep: RepFactory): Tree =
      this.applyRule.tree

    private def toUse(s: Symbol) =
       (s hasFlag Flags.MUTABLE) &&                 // indicates that have not yet checked exhaustivity
      !(s hasFlag Flags.TRANS_FLAG) &&              // indicates @unchecked
       (s.tpe.typeSymbol hasFlag Flags.SEALED)
    
    // the superclass is taken if it is not abstract
    private def countSuper(s: Symbol): Set[Symbol]  = if (s hasFlag Flags.ABSTRACT) emptySymbolSet else Set(s)
    private def countSymbol(s: Symbol): Set[Symbol] = candidates(s) ++ countSuper(s)      
    private def candidates(s: Symbol): Set[Symbol]  =
      if (s hasFlag Flags.SEALED) s.children flatMap countSymbol
      else emptySymbolSet
    
    private def setsToCombine: List[(Int, Set[Symbol])] =
      for ((sym, i) <- temp.zipWithIndex ; if toUse(sym)) yield {
        sym resetFlag Flags.MUTABLE
        (i, candidates(sym.tpe.typeSymbol))
      }
     
    // computes cartesian product, keeps indices available    
    private def combine(colcom: List[(Int, Set[Symbol])]): List[List[Combo]] = colcom match {
      case Nil              => Nil
      case (i, syms) :: Nil => syms.toList map (s => List(Combo(i, s)))
      case (i, syms) :: cs  => for (s <- syms.toList; rest <- combine(cs)) yield Combo(i, s) :: rest
    }
                
    private def comboCovers(combo: List[Combo]) = row exists (_ covers combo)

    def init: this.type = {
      val allcomb = combine(setsToCombine)
      if (allcomb forall comboCovers) return this
      
      // if we reach here, patterns were not exhaustive
      def mkPad(xs: List[Combo], i: Int): String = xs match {
        case Nil                    => pad("*")
        case Combo(j, sym) :: rest  => if (j == i) pad(sym.name.toString) else mkPad(rest, i)
      }
      def mkMissingStr(open: List[Combo]) =
        "missing combination " + temp.indices.map(mkPad(open, _)).mkString + "\n"
        
      val missingCombos = (allcomb filter (open => row.forall(!_.covers(open))) map mkMissingStr).mkString
      cunit.warning(temp.head.pos, "match is not exhaustive!\n" + missingCombos)
      this
    }
        
    /*   internal representation is (temp:List[Symbol], row:List[Row])
     *   
     *         tmp1       tmp_m
     */
    final def applyRule(implicit theOwner: Symbol, rep: RepFactory): RuleApplication = row match {
      case Nil                              => ErrorRule()
      case Row(pats, subst, g, bx) :: xs    => 

        var bnd = subst
        for (((rpat, t), px) <- pats zip temp zipWithIndex) {
          val (vs, p) = strip(rpat)
          if (isDefaultPattern(p)) bnd = bnd.add(vs, t)
          else {
            // Row( _  ... _ p_1i  ...  p_1n   g_m  b_m ) :: rows
            // cut out column px that contains the non-default pattern
            val column    = rpat :: (row.tail map (_ pat px))
            val restTemp  = temp dropIndex px
            val restRows  = row map (r => r replace (r.pat dropIndex px))
            val mr        = MixtureRule(new Scrutinee(t), column, rep.make(restTemp, restRows))
            //DBG("\n---\nmixture rule is = " + mr.getClass)
            return mr
          }
        }
        // Row(   _   ...   _     g_1  b_1 ) :: rows     it's all default patterns
        val rest = if (g.isEmpty) null else rep.make(temp, xs)    // TODO - why null?
        VariableRule (bnd, g, rest, bx)
    }

    // a fancy toString method for debugging
    override final def toString = {
      val tempStr = (temp map (t => pad(t.name))).mkString + "\n"
      val rowStr  = (row map (r => (r.pat ::: List(r.subst, r.guard, r.bx))) map pad mkString).mkString + "\n"
      tempStr + rowStr
    }
     
    private val NPAD = 15
    private def pad(s: Any): String = pad(s.toString)
    private def pad(s: String): String = List.fill(NPAD - s.length - 1)(" ").mkString + s
  } 
  
  /** creates initial clause matrix
   */
  final def initRep(roots: List[Symbol], cases: List[Tree], rep: RepFactory)(implicit theOwner: Symbol) = {
    val (rows, targets, vss): (List[Option[Row]], List[Tree], List[List[Symbol]]) = unzip3(
      for ((CaseDef(pat, g, b), bx) <- cases.zipWithIndex) yield {  // stash away pvars and bodies for later
        
        def mkRow(ps: List[Tree]) = Some(Row(ps, NoBinding, Guard(g), bx))
        def rowForPat: Option[Row] = pat match {
          case _ if roots.length <= 1 => mkRow(List(pat))
          case Apply(fn, pargs)       => mkRow(pargs)
          case Ident(nme.WILDCARD)    => mkRow(getDummies(roots.length))
          case _                      => None
        }
        
        (rowForPat, b, definedVars(pat))
      }
    )
    
    // flatMap the list of options yields the list of values
    rep.make(roots, rows.flatMap(x => x), targets, vss)
  }

  final def newVar(pos: Position, name: Name, tpe: Type, flags: List[Long])(implicit theOwner: Symbol): Symbol = {
    assert(tpe ne null, "newVar("+name+", null)")
    val sym = theOwner.newVariable(pos, name) // careful: pos has special meaning
    sym setInfo tpe
    sym setFlag flags.foldLeft(Flags.SYNTHETIC.toLong)(_|_)
  }
  
  final def newVar(pos: Position, tpe: Type, flags: List[Long])(implicit theOwner: Symbol): Symbol =
    newVar(pos, newName(pos, "temp"), tpe, flags)
  
  final def newVar(pos: Position, tpe: Type)(implicit theOwner: Symbol): Symbol =
    newVar(pos, tpe, Nil)
  
  /** returns the condition in "if (cond) k1 else k2" 
   */
  final def condition(tpe: Type, scrut: Scrutinee)(implicit typer: Typer, theOwner: Symbol, rep: RepFactory): Tree = {
    assert(scrut.isDefined)
    val cond = rep handleOuter (typer typed condition(tpe, scrut.id))
    
    if (!needsOuterTest(tpe, scrut.tpe, theOwner)) cond
    else addOuterCondition(cond, tpe, scrut.id, rep.handleOuter)
  }

  final def condition(tpe: Type, scrutTree: Tree)(implicit typer : Typer): Tree = {
    assert((tpe ne NoType) && (scrutTree.tpe ne NoType))
    def equalsRef      = REF(tpe.termSymbol) ANY_== scrutTree
    def isInst         = scrutTree IS tpe

    tpe match {
      case ct: ConstantType => ct.value match {                                         // constant
          case v @ Constant(null) if scrutTree.tpe.isAnyRef     => scrutTree ANY_EQ NULL
          case v                                                => scrutTree ANY_== Literal(v)
        }
      case _: SingletonType =>
        if (tpe.termSymbol.isModule) equalsRef  // object
        else typer typed (if (tpe.prefix ne NoPrefix) isInst else equalsRef)
      case _ =>
        if (scrutTree.tpe <:< tpe && tpe.isAnyRef) typer typed (scrutTree OBJ_!= NULL)
        else isInst
    }
  }

  /** adds a test comparing the dynamic outer to the static outer */
  final def addOuterCondition(cond: Tree, tpe2test: Type, scrut: Tree, handleOuter: Tree=>Tree) = {
    val theRef = handleOuter(tpe2test match {
      case TypeRef(NoPrefix, _, _)          => abort("assertion failed: NoPrefix")
      case TypeRef(ThisType(clazz), _, _)   => THIS(clazz)
      case TypeRef(prefix, _, _)            => REF(prefix.prefix, prefix.termSymbol)
    })
    
    outerAccessor(tpe2test.typeSymbol) match {
      case NoSymbol => if (settings.debug.value) cunit.warning(scrut.pos, "no outer acc for "+tpe2test.typeSymbol) ; cond
      case outerAcc => cond AND (((scrut AS_ANY tpe2test) DOT outerAcc)() ANY_EQ theRef)
    }
  }
}
