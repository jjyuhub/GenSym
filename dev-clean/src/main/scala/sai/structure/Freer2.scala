package sai.structure

import scala.language.{higherKinds, implicitConversions}

//Track effects as type-level lists of type constructors
object Eff {
  sealed trait Eff
  trait ∅ extends Eff
  trait ⊗[A[_], TL <: Eff] extends Eff

  type Lone[T[_]] = T ⊗ ∅
}

//union type as in the freer monad paper, scala-style
object OpenUnion {
  import Eff._
  sealed trait U[R <: Eff, X] {
    def weaken[E[_]]: U[E ⊗ R,X]
  }
  case class Union[R <: Eff,T[_],X] private(index: Int, value: T[X]) extends U[R,X] {
    def weaken[E[_]]: Union[E ⊗ R, T, X] = Union(index+1,value)
  }

  //type-safe pointers into tlists
  case class Ptr[T[_], R <: Eff] private(pos: Int)
  implicit def pz[T[_], R <: Eff]: Ptr[T, T ⊗ R] = Ptr(0)
  implicit def ps[T[_],R <: Eff,U[_]](implicit pred: Ptr[T,R]): Ptr[T, U ⊗ R] = Ptr(pred.pos + 1)

  trait ∈[T[_], R <: Eff] {
    def inj[X](sub: T[X]): U[R,X]
    def prj[X](u: U[R,X]): Option[T[X]]
  }

  implicit def member[T[_], R <: Eff](implicit ptr: Ptr[T,R]): T ∈ R =
    new (T ∈ R) {
      override def inj[X](sub: T[X]): U[R, X] = Union(ptr.pos, sub)

      override def prj[X](u: U[R, X]): Option[T[X]] = u match {
        case Union(i,v) if i == ptr.pos => Some(v.asInstanceOf[T[X]])
        case _ => None
      }
    }

  //TODO can this be made more flexible? i.e. have decomp at arbitrary positions in the list R?
  def decomp[T[_], R <: Eff, X](u: U[T ⊗ R,X]): Either[U[R,X], T[X]] = u match {
    case Union(0, v) => Right(v.asInstanceOf[T[X]])
    case Union(n, v) => Left(Union(n-1, v))
  }

  implicit def weaken[T[_], R <: Eff, X](u: U[R,X]): U[T ⊗ R, X] = u match {
    case Union(n, v) => Union(n+1, v)
  }
}

object Freer {
  import Eff._
  import OpenUnion._

  abstract class Comp[R <: Eff, +A] {
    def flatMap[B](f: A => Comp[R, B]): Comp[R, B]
    def >>=[B](f: A => Comp[R, B]): Comp[R, B] = flatMap(f)
    def map[B](f: A => B): Comp[R, B]
  }

  case class Return[R <: Eff, A](a: A) extends Comp[R, A] {
    override def flatMap[B](f: A => Comp[R, B]): Comp[R, B] = f(a)

    override def map[B](f: A => B): Comp[R, B] = Return(f(a))
  }

  case class Op[R <: Eff, A, X](op: U[R,X], k: X => Comp[R, A]) extends Comp[R, A] { //TODO use the construction from sec. 3.1 for the continuations
    override def flatMap[B](f: A => Comp[R, B]): Comp[R, B] =
      Op(op, { x: X => k(x) flatMap f })

    override def map[B](f: A => B): Comp[R, B] =
      Op(op, { x: X => k(x) map f })
  }
  object Op {
    //This'll behave better with type inference, since we usually do not know the
    //actual name of the abstract continuation parameter X in a usage context. We can avoid
    //explicitly annotating the argument of the continuation k.
    def apply[R <: Eff, A, X](op: U[R,X])(k: X => Comp[R, A]): Comp[R,A] = Op(op,k)
  }

  def perform[T[_], R <: Eff, X](op: T[X])(implicit I: T ∈ R): Comp[R,X] = //TODO naming
    Op(I.inj(op)) {x => Return(x)}

  def ret[R <: Eff, A](x:A): Return[R,A] = Return(x)

  implicit def extract[A](c: Comp[∅, A]): A = c match {
    case Return(a) => a
  }
}

object Handlers {
  import Eff._
  import OpenUnion._
  import Freer._

  //represents the handler clauses for effect ops, and is polymorphic in the continuation type
  trait FFold[F[_],A,B] {
    def apply[X]: (F[X], X => A) => B
    final def curried[X](fx: F[X])(k: X => A): B = apply(fx,k)
  }
  type DeepH[F[_], R <: Eff, A] = FFold[F,Comp[R,A],Comp[R,A]]
  type ShallowH[F[_], R <: Eff, A, B] = FFold[F,Comp[F ⊗ R,A],Comp[R,B]]

  /** deep handler combinator */
  def handler[E[_], R <: Eff, A, B]
             (ret: Return[E ⊗ R, A] => Comp[R, B]) //that's a stylistic choice, could as well make it A => Comp[R,B]
             (h: DeepH[E, R, B]): Comp[E ⊗ R, A] => Comp[R, B] = {
    case Return(x) => ret(Return(x))
    case Op(u, k) => decomp(u) match {
      case Right(ex) =>
        h.curried(ex) { x => handler(ret)(h)(k(x))}
      case Left(op) =>
        Op(op) { x => handler(ret)(h)(k(x)) }
    }
  }

  /** shallow handler combinator */
  def shallow_handler[E[_], R <: Eff, A, B]
                     (ret: Return[E ⊗ R, A] => Comp[R, B])
                     (h: ShallowH[E, R, A, B]): Comp[E ⊗ R, A] => Comp[R, B] = {
    case Return(x) => ret(Return(x))
    case Op(u, k) => decomp(u) match {
      case Right(ex) =>
        h.apply(ex, k)
      case Left(op) =>
        Op(op) { x => shallow_handler(ret)(h)(k(x)) }
    }
  }

  //open handlers a la handlers in action paper, i.e., handling an effect E by inducing other effects F
  //with more powerful effect row calculations, we could just have this kind of deep handler
  abstract class DeepHO[F[_],G[_],R <: Eff,A] {
    //to allow inducing other effects in the handler body passed by the programmer, we'll need an implicit context with the capability
    //we could probably have a leaner overall design with dotty's implicit function types
    //TODO: weak point: what if we require G to consist of multiple effects?
    implicit val canG: G ∈ (G ⊗ R) = member
    def apply[X]: (F[X], X => Comp[G ⊗ R, A]) => Comp[G ⊗ R, A]
    final def curried[X](fx: F[X])(k: X => Comp[G ⊗ R, A]): Comp[G ⊗ R, A] = apply(fx,k)
  }

  def ohandler[E[_], F[_], R <: Eff, A, B]
              (ret: Return[E ⊗ R, A] => Comp[F ⊗ R, B])
              (h: DeepHO[E, F, R, B]): Comp[E ⊗ R, A] => Comp[F ⊗ R, B] = {
    case Return(x) => ret(Return(x))
    case Op(u, k) => decomp(u) match {
      case Right(ex) =>
        h.curried(ex) { x => ohandler(ret)(h)(k(x))}
      case Left(op) =>
        Op(op.weaken[F]) { x => ohandler(ret)(h)(k(x)) }
    }
  }

  //TODO: koka-style parameterized handlers?
}

object State {
  import Eff._
  import OpenUnion._
  import Freer._

  sealed trait State[S,K]
  case class Put[S](x: S) extends State[S,Unit] //Put: S ~> Unit
  case class Get[S]() extends State[S,S] //Get: Unit ~> S

  def put[S, R <: Eff](x: S)(implicit I: State[S,*] ∈ R): Comp[R,Unit] =
    perform[State[S,*],R,Unit](Put(x))
  def get[S, A, R <: Eff]()(implicit I: State[S,*] ∈ R): Comp[R,S] =
    perform[State[S,*],R,S](Get())

  //Scala's type checker struggles with GADTs having more than one type parameter (e.g. State[S,K]) in pattern
  //matching clauses. We define custom extractors to relieve programmers from manual type casts.
  object Get$ {
    def unapply[S,X,R](p: (State[S,X],X => R)): Option[(Unit, S => R)] = p match {
      case (Get(), k) => Some(((),k.asInstanceOf[S => R])) //the compiler cannot infer that X = S
      case _ => None
    }
  }
  object Put$ {
    def unapply[S,X,R](p: (State[S,X],X => R)): Option[(S, Unit => R)] = p match {
      case (Put(s), k) => Some((s,k))
      case _ => None
    }
  }

  import Handlers._

  def stateref[S, E <: Eff, A](init: S) = {
    var state: S = init
    handler[State[S, *], E, A, A] {
      case Return(x) => Return(x)
    }(new DeepH[State[S, *], E, A] {
      def apply[X] = { (_,_) match {
          case Get$((), k) => k(state)
          case Put$(s, k) =>
            state = s
            k(())
        }
      }
    })
  }

  //a little less noisy
  def stateref2[S, E <: Eff, A](init: S) = {
    var state: S = init
    handler[State[S, *], E, A, A] {
      case Return(x) => Return(x)
    }{ν[DeepH[State[S, *], E, A]] {
      case Get$((), k) => k(state)
      case Put$(s, k) =>
          state = s
          k(())
      }
    }
  }

  def statefun[S, A] = handler[State[S,*], ∅, A, S => Comp[∅,A]] {
    case Return(x)  => ret { _: S => ret(x) }
  } (ν[DeepH[State[S, *], ∅, S => Comp[∅,A]]]{
    case Get$((), k) => ret { s: S =>  k(s)(s)  }
    case Put$(s, k)  => ret { _: S => k(())(s) }
  })

  //example of intercepting and forwarding effects with open handler
  def state_square[E <: Eff, A] = ohandler[State[Int,*], State[Int,*], E, A, A] {
    case Return(x)  => ret(x)
  } (ν[DeepHO[State[Int, *], State[Int, *], E, A]]{
    case Get$((), k) =>
      get() >>= { x => k(x * x)}
    case Put$(s, k)  =>
      put(s) >>= k
  })

  def prog[R <: Eff](implicit I: State[Int,*] ∈ R): Comp[R, Int] = for {
    x <- get()
    y <- get()
    _ <- put(x * y)
    z <- get()
  } yield z

  def prog_ref: Comp[∅,Int] = stateref(2)(prog)
  def prog_ref2: Comp[∅,Int] = stateref2(2)(prog)
  def prog_fun: Comp[∅,Int] =
    for {
      f <- statefun[Int,Int](prog)
    } yield f(2)
  def prog_fun2 = statefun[Int,Int](prog)(2)
  def prog_ref_square: Comp[∅,Int] = stateref(2)(state_square(prog))
  def prog_ref2_square: Comp[∅,Int] = stateref2(2)(state_square(prog))
  def prog_fun_square: Comp[∅,Int] = statefun[Int,Int](state_square(prog))(2)


  def main(args: Array[String]): Unit = {
    println(f"prog_ref: $prog_ref%d")
    println(f"prog_ref2: $prog_ref2%d")
    println(f"prog_fun: $prog_fun%d")
    println(f"prog_fun2: $prog_fun2%d")
    println(f"prog_ref_square: $prog_ref_square%d")
    println(f"prog_ref2_square: $prog_ref2_square%d")
    println(f"prog_fun_square: $prog_fun_square%d")
  }
}

object Nondet {
  import Eff._
  import OpenUnion._
  import Freer._

  sealed trait Nondet[K]
  case object Fail extends Nondet[Nothing]   //Fail ()   ~> Nothing
  case object Choice extends Nondet[Boolean] //Choice () ~> Boolean

  object Fail$ {
    def unapply[K,R](n: (Nondet[K], K => R)): Boolean = n match {
      case (Fail,_) => true
      case _ => false
    }
  }

  object Choice$ {
    def unapply[K,R](n: (Nondet[K], K => R)): Option[(Unit, Boolean => R)] = n match {
      case (Choice, k) => Some(((), k))
      case _ => None
    }
  }

  def fail[A, R <: Eff](implicit I: Nondet ∈ R): Comp[R,A] = perform(Fail)
  def choice[A, R <: Eff](a: Comp[R,A], b: Comp[R,A])(implicit I: Nondet ∈ R): Comp[R,A] =
    perform(Choice) >>= {
      case true  => a
      case false => b
    }


}
