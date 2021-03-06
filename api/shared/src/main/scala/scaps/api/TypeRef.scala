/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.api

case class FingerprintTerm(variance: Variance, tpe: String) {
  override def toString = s"${variance.prefix}$tpe"
}

case class TypeRef(name: String, variance: Variance, args: List[TypeRef], isTypeParam: Boolean = false) {
  import TypeRef._

  def shortName: String =
    EntityName.splitName(name).last

  def apply(paramName: String, arg: TypeRef): TypeRef = {
    if (isTypeParam && name == paramName)
      arg.withVariance(variance)
    else
      copy(args = args.map(_.apply(paramName, arg)))
  }

  override def toString() = {
    val argStr = args match {
      case Nil => ""
      case as  => as.mkString("[", ", ", "]")
    }
    s"${variance.prefix}$name$argStr"
  }

  def signature: String = signature(false)

  def signature(withImplicits: Boolean): String =
    this match {
      case Implicit(t, _) if !withImplicits =>
        t.signature(withImplicits)
      case _ =>
        val argStr = args match {
          case Nil => ""
          case as  => as.map(_.signature(withImplicits)).mkString("[", ", ", "]")
        }
        s"$name$argStr"
    }

  def annotatedSignature: String =
    this match {
      case Implicit(t, _) =>
        t.annotatedSignature
      case _ =>
        val argStr = args match {
          case Nil => ""
          case as  => as.map(_.annotatedSignature).mkString("[", ", ", "]")
        }
        s"${variance.prefix}$name$argStr"
    }

  def term: FingerprintTerm = FingerprintTerm(variance, name)

  def toList: List[TypeRef] = this :: args.flatMap(_.toList)

  def withVariance(v: Variance): TypeRef =
    if (variance == v) this
    else if (v == Invariant)
      copy(variance = v, args = args.map(arg => arg.withVariance(arg.variance * v)))
    else
      copy(variance = v, args = args.map(arg => arg.withVariance(arg.variance.flip)))

  def transform(f: TypeRef => TypeRef): TypeRef =
    f(this.copy(args = args.map(_.transform(f))))

  def renameTypeParams(getName: String => String): TypeRef =
    transform { tpe =>
      if (tpe.isTypeParam) tpe.copy(name = getName(name))
      else tpe
    }

  def withArgsAsParams: TypeRef =
    copy(args = args.zipWithIndex.map {
      case (arg, idx) =>
        TypeRef(s"$$$idx", arg.variance, Nil, isTypeParam = true)
    })

  def normalize(typeParams: List[TypeParameter] = Nil): TypeRef = {
    def loop(tpe: TypeRef): TypeRef =
      tpe match {
        case MemberAccess(owner, member) =>
          loop(Function(owner :: Nil, loop(member)))
        case MethodInvocation(args, res, _) =>
          loop(Function(args, loop(res)))
        case Function(a :: (as @ (_ :: _)), res, v) =>
          // curry function
          loop(Function(a :: Nil, Function(as, res, v), v))
        case Refinement(args, v) =>
          val newArgs = args.flatMap {
            case AnyRef(_) => None
            case arg       => Some(arg.copy(args = arg.args.map(loop)))
          }

          newArgs match {
            case arg :: Nil => arg
            case args       => Refinement(args, v)
          }
        case ByName(arg, _) =>
          loop(arg)
        case tpe: TypeRef =>
          val normalizedArgs = tpe.args.map(loop)
          typeParams.find(_.name == tpe.name).fold {
            tpe.copy(args = normalizedArgs)
          } { param =>
            if (tpe.variance == Contravariant)
              param.upperBound.copy(args = normalizedArgs)
            else if (tpe.variance == Covariant)
              param.lowerBound.copy(args = normalizedArgs)
            else
              Unknown(tpe.variance)
          }
      }

    def parametrizedTopAndBottom(tpe: TypeRef): TypeRef = {
      val mappedArgs = tpe.args.map(parametrizedTopAndBottom)
      tpe match {
        case TypeRef(Any.name, Contravariant, as, false) if as.length > 0 =>
          Top(mappedArgs, Contravariant)
        case TypeRef(Nothing.name, Covariant, as, false) if as.length > 0 =>
          Bottom(mappedArgs, Covariant)
        case t => t.copy(args = mappedArgs)
      }
    }

    def paramsAndReturnTpes(tpe: TypeRef): List[TypeRef] = tpe match {
      case Function(args, res, v) =>
        args ++ paramsAndReturnTpes(res)
      case tpe => List(tpe)
    }

    // outermost function applications are ignored
    Ignored((loop _ andThen parametrizedTopAndBottom andThen paramsAndReturnTpes)(this))
  }

  def withoutImplicitParams: TypeRef = this match {
    case TypeRef.MethodInvocation(a :: as, res, _) if a.name == TypeRef.Implicit.name =>
      res.withoutImplicitParams
    case TypeRef.MethodInvocation(args, res, v) =>
      TypeRef.MethodInvocation(args, res.withoutImplicitParams, v)
    case t =>
      t
  }

  def etaExpanded: TypeRef = this match {
    case TypeRef.MethodInvocation(args, res, v) =>
      TypeRef.Function(args, res.etaExpanded, v)
    case TypeRef.MemberAccess(owner, member) =>
      TypeRef.Function(List(owner), member.etaExpanded, Covariant)
    case t =>
      t
  }

  def curried: TypeRef = etaExpanded match {
    case TypeRef.Function(Nil, res, v)      => res
    case TypeRef.Function(a :: Nil, res, v) => TypeRef.Function(List(a.curried), res.curried, v)
    case TypeRef.Function(a :: as, res, v)  => TypeRef.Function(List(a.curried), TypeRef.Function(as, res, v).curried, v)
    case t                                  => t.copy(args = args.map(_.curried))
  }

  def structure: TypeRef = {
    def inner(t: TypeRef): TypeRef = t match {
      case TypeRef.Implicit(a, v) => TypeRef.Implicit(inner(a), v)
      case TypeRef.Repeated(a, v) => TypeRef.Repeated(inner(a), v)
      case _                      => TypeRef("_", Invariant, Nil, true)
    }

    def outer(t: TypeRef): TypeRef = t match {
      case TypeRef.Function(as, r, _) => copy(args = as.map(inner) :+ outer(r))
      case _                          => inner(t)
    }

    outer(curried)
  }
}

object TypeRef {
  object Any extends PrimitiveType("scala.Any")
  object AnyVal extends PrimitiveType("scala.AnyVal")
  object AnyRef extends PrimitiveType("java.lang.Object")
  object Int extends PrimitiveType("scala.Int")
  object Long extends PrimitiveType("scala.Long")
  object Float extends PrimitiveType("scala.Float")
  object Char extends PrimitiveType("scala.Char")
  object String extends PrimitiveType("java.lang.String")
  object Nothing extends PrimitiveType("scala.Nothing") {
    val cls = TypeDef(name, Nil)
  }
  object Unit extends PrimitiveType("scala.Unit")

  object Unknown extends PrimitiveType("<unknown>")

  class PrimitiveType(val name: String) {
    def apply(variance: Variance = Covariant) = TypeRef(name, variance, Nil)

    def unapply(tpe: TypeRef): Option[Variance] =
      if (tpe.name == name && tpe.args.isEmpty)
        Some(tpe.variance)
      else
        None
  }

  object ByName extends GenericType("<byname>")
  object Repeated extends GenericType("scala.<repeated>")
  object Implicit extends GenericType("<implicit>")
  object Option extends GenericType("scala.Option")
  object Seq extends GenericType("scala.collection.Seq")
  object SList extends GenericType("scala.collection.immutable.List")

  class GenericType(val name: String) {
    def apply(arg: TypeRef, variance: Variance = Covariant) =
      TypeRef(name, variance, arg :: Nil)

    def unapply(tpe: TypeRef): Option[(TypeRef, Variance)] =
      if (tpe.name == name && tpe.args.length == 1)
        Some((tpe.args.head, tpe.variance))
      else
        None

    def matches(tpe: TypeRef): Boolean =
      unapply(tpe).isDefined
  }

  object SMap extends GenericType2("scala.collection.immutable.Map")

  class GenericType2(val name: String) {
    def apply(arg1: TypeRef, arg2: TypeRef, variance: Variance = Covariant) =
      TypeRef(name, variance, arg1 :: arg2 :: Nil)

    def unapply(tpe: TypeRef): Option[(TypeRef, TypeRef, Variance)] =
      if (tpe.name == name && tpe.args.length == 2)
        Some((tpe.args(0), tpe.args(1), tpe.variance))
      else
        None

    def matches(tpe: TypeRef): Boolean =
      unapply(tpe).isDefined
  }

  object Tuple extends VariantType("scala.Tuple")
  object Refinement extends VariantType("<refinement", ">")

  object Top extends VariantType("<top", ">")
  object Bottom extends VariantType("<bottom", ">")

  class VariantType(val tpePrefix: String, val tpeSuffix: String = "") {
    def name(n: Int) = s"$tpePrefix$n$tpeSuffix"

    def apply(args: List[TypeRef], variance: Variance = Covariant) =
      TypeRef(name(args.size), variance, args)

    def unapply(tpe: TypeRef): Option[(List[TypeRef], Variance)] =
      if (!tpe.args.isEmpty && tpe.name == name(tpe.args.size))
        Some((tpe.args, tpe.variance))
      else
        None
  }

  object Function extends FunctionLikeType("scala.Function")
  object MethodInvocation extends FunctionLikeType("<methodInvocation", ">")

  class FunctionLikeType(val tpePrefix: String, val tpeSuffix: String = "") {
    def name(n: Int) = s"$tpePrefix$n$tpeSuffix"

    def apply(paramTypes: List[TypeRef], resultType: TypeRef, variance: Variance = Covariant) = {
      val typeArgs = paramTypes.map(pt => pt.copy(variance = variance.flip)) :+ resultType.copy(variance = variance)
      TypeRef(name(paramTypes.length), variance, typeArgs)
    }

    def unapply(tpe: TypeRef) =
      if (!tpe.args.isEmpty && tpe.name == name(tpe.args.size - 1))
        Some((tpe.args.init, tpe.args.last, tpe.variance))
      else
        None
  }

  object MemberAccess {
    val name = "<memberAccess>"

    def apply(owner: TypeRef, member: TypeRef): TypeRef =
      TypeRef(name, Covariant, owner.copy(variance = Contravariant) :: member :: Nil)

    def unapply(tpe: TypeRef) =
      if (tpe.args.size == 2 && tpe.name == name)
        Some((tpe.args.head, tpe.args.tail.head))
      else
        None
  }

  object Ignored {
    def name(n: Int) = s"<ignored$n>"

    def apply(typeArgs: List[TypeRef], variance: Variance = Covariant) =
      TypeRef(name(typeArgs.length), variance, typeArgs)

    def unapply(tpe: TypeRef) =
      if (tpe.name == name(tpe.args.length))
        Some((tpe.args, tpe.variance))
      else
        None
  }
}
