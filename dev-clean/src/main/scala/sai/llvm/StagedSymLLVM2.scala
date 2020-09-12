package sai.llvm.se

import sai.lang.llvm._
import sai.lang.llvm.IR._

import org.antlr.v4.runtime._
import scala.collection.JavaConverters._

import sai.structure.freer3._
import Eff._
import Freer._
import Handlers._
import OpenUnion._
import Nondet._
import State._

import lms.core._
import lms.core.Backend._
import lms.core.virtualize
import lms.macros.SourceContext
import lms.core.stub.{While => _, _}

import sai.lmsx._
import sai.structure.lattices._
import sai.structure.lattices.Lattices._

import sai.imp.{RepNondet}

import scala.collection.immutable.{List => SList}
import scala.collection.immutable.{Map => SMap}

@virtualize
trait StagedSymExecEff extends SAIOps with RepNondet {
  // FIXME: concrete representation of Mem and Expr
  trait Mem
  trait Addr
  trait Value
  type SMTExpr = Value // Temp changes

  // TODO: if there is no dyanmic heap allocation, the object
  //       Heap can be a Map[Rep[Addr], Rep[Value]] (or maybe Array[Rep[Value]])
  type Heap = Mem
  type Frame = (String, Mem)
  type PC = Set[SMTExpr]
  type Stack = List[Frame]
  type SS = (Heap, Stack, PC)
  type E = State[Rep[SS], *] ⊗ (Nondet ⊗ ∅)

  lazy val emptyMem: Rep[Mem] = Wrap[Mem](Adapter.g.reflect("mt-mem"))

  // TODO: can be Comp[E, Unit]?
  def putState(s: Rep[SS]): Comp[E, Rep[Unit]] = for { _ <- put[Rep[SS], E](s) } yield ()
  def getState: Comp[E, Rep[SS]] = get[Rep[SS], E]

  def getHeap: Comp[E, Rep[Heap]] = for { s <- get[Rep[SS], E] } yield s._1
  def putHeap(h: Rep[Heap]) = for {
    s <- get[Rep[SS], E]
    _ <- put[Rep[SS], E]((h, s._2, s._3))
  } yield ()

  def getPC: Comp[E, Rep[PC]] = for { s <- get[Rep[SS], E] } yield s._3
  def updatePC(x: Rep[SMTExpr]): Comp[E, Rep[Unit]] = for { 
    s <- getState
    _ <- putState((s._1, s._2, s._3 ++ Set(x)))
  } yield ()

  def getStack: Comp[E, Rep[Stack]] = for { s <- get[Rep[SS], E] } yield s._2
  def curFrame: Comp[E, Rep[Frame]] = for { fs <- getStack } yield fs.head
  def pushFrame(f: String): Comp[E, Rep[Unit]] = pushFrame((f, emptyMem))
  def pushFrame(f: Rep[Frame]): Comp[E, Rep[Unit]] =
    for {
      s <- getState
      _ <- putState((s._1, f :: s._2, s._3))
    } yield ()
  def popFrame: Comp[E, Rep[Unit]] =
    for {
      s <- getState
      _ <- putState((s._1, s._2.tail, s._3))
    } yield ()
  def curFrameName: Comp[E, Rep[String]] = for {
    f <- curFrame
  } yield f._1 //counit(f._1)
  def replaceCurrentFrame(f: Rep[Frame]): Comp[E, Rep[Unit]] = for {
    _ <- popFrame
    _ <- pushFrame(f)
  } yield ()

  def counit[A](x: Rep[A]): A =
    Unwrap(x) match {
      case Backend.Const(x) => x.asInstanceOf[A]
    }

  object Mem {
    import Addr._

    // TODO: be careful of overwriting
    def envLookup(f: Rep[String], x: Rep[String]): Rep[Addr] =
      // FIXME: seems can be using a current stage map
      Wrap[Addr](Adapter.g.reflect("env-lookup", Unwrap(f), Unwrap(x)))

    // it seems that heapEnv can be static
    // def heapEnv(s: Rep[String]): Comp[E, Rep[Addr]] = ???
    def frameEnv(s: String): Comp[E, Rep[Addr]] = for {
      f <- curFrameName
    } yield envLookup(f, s)

    def lookup(σ: Rep[Mem], a: Rep[Addr]): Rep[Value] =
      Wrap[Value](Adapter.g.reflect("mem-lookup", Unwrap(σ), Unwrap(a)))
    def frameLookup(f: Rep[Frame], a: Rep[Addr]): Rep[Value] = lookup(f._2, a)

    def alloc(σ: Rep[Mem], size: Int): (Rep[Mem], Rep[Addr]) = {
      // FIXME: 
      val m = Wrap[Mem](Adapter.g.reflect("alloc", Unwrap(σ), Backend.Const(size)))
      val a = Wrap[Addr](Adapter.g.reflect("alloc", Unwrap(σ), Backend.Const(size)))
      (m, a)
    }

    def frameAlloc(f: Rep[Frame], size: Int): (Rep[Frame], Rep[Addr]) = {
      val (σ, a) = alloc(f._2, size)
      ((f._1, σ), a)
    }
    def frameAlloc(size: Int): Comp[E, Rep[Addr]] = {
      /* 
       for {
         f <- curFrame
         val (f_, a) = frameAlloc(f, size)
         _ <- replaceCurrentFrame(f_)
       } yield a
       */
      // Note: using val keyword in monadic style seems having some trouble
      curFrame.flatMap { f =>
        val (f_, a) = frameAlloc(f, size)
        replaceCurrentFrame(f_).map { _ => a }
      }
    }

    def update(σ: Rep[Mem], k: Rep[Addr], v: Rep[Value]): Rep[Mem] =
      Wrap[Mem](Adapter.g.reflect("mem-update", Unwrap(σ), Unwrap(k), Unwrap(v)))
    def updateL(σ: Rep[Mem], k: Rep[Addr], v: Rep[List[Value]]): Rep[Mem] =
      Wrap[Mem](Adapter.g.reflect("mem-updateL", Unwrap(σ), Unwrap(k), Unwrap(v)))
    def frameUpdate(k: Rep[Addr], v: Rep[Value]): Comp[E, Rep[Unit]] =
      for {
        f <- curFrame
        _ <- replaceCurrentFrame((f._1, update(f._2, k, v)))
      } yield ()
    def frameUpdate(x: String, v: Rep[Value]): Comp[E, Rep[Unit]] = for {
      addr <- frameEnv(x)
      _ <- frameUpdate(addr, v)
    } yield ()
    def frameUpdate(xs: List[String], vs: Rep[List[Value]]): Comp[E, Rep[Unit]] = {
      // TODO: improve this
      if (xs.isEmpty) ret(())
      else {
        val x = xs.head
        val v = vs.head
        for {
          _ <- frameUpdate(x, v)
          _ <- frameUpdate(xs.tail, vs.tail)
        } yield ()
      }
    }

    def selectMem(v: Rep[Value]): Comp[E, Rep[Mem]] = {
      // if v is a HeapAddr, return heap, otherwise return its frame memory
      ret(Wrap[Mem](Adapter.g.reflect("select-mem", Unwrap(v))))
    }
    def updateMem(k: Rep[Value], v: Rep[Value]): Comp[E, Rep[Unit]] = {
      // if v is a HeapAddr, update heap, otherwise update its frame memory
      // v should be a LocV and wrap an actual location
      // FIXME:
      ret(())
    }
  }

  object Addr {
    def localAddr(f: Rep[Frame], x: String): Rep[Addr] = {
      Wrap[Addr](Adapter.g.reflect("local-addr", Unwrap(f), Backend.Const(x)))
    }
    def heapAddr(x: String): Rep[Addr] = {
      Wrap[Addr](Adapter.g.reflect("heap-addr", Backend.Const(x)))
    }
    
    implicit class AddrOP(a: Rep[Addr]) {
      def +(x: Int): Rep[Addr] =
        Wrap[Addr](Adapter.g.reflect("addr-+", Unwrap(a), Backend.Const(x)))
    }
  }

  object Value {
    def IntV(i: Rep[Int]): Rep[Value] = IntV(i, 32)
    def IntV(i: Rep[Int], bw: Int): Rep[Value] =
      Wrap[Value](Adapter.g.reflect("IntV", Unwrap(i), Backend.Const(bw)))
    def LocV(l: Rep[Addr]): Rep[Value] = 
      Wrap[Value](Adapter.g.reflect("LocV", Unwrap(l)))
    def FunV(f: Rep[(SS, List[Value]) => List[(SS, Value)]]): Rep[Value] = Wrap[Value](Unwrap(f))
      // Wrap[Value](Adapter.g.reflect("FunV", Unwrap(f)))

    def projLocV(v: Rep[Value]): Rep[Addr] =
      Wrap[Addr](Adapter.g.reflect("proj-LocV", Unwrap(v)))
    def projIntV(v: Rep[Value]): Rep[Int] =
      Wrap[Int](Adapter.g.reflect("proj-IntV", Unwrap(v)))
    def projFunV(v: Rep[Value]): Rep[(SS, List[Value]) => List[(SS, Value)]] =
      Wrap[(SS, List[Value]) => List[(SS, Value)]](Unwrap(v))
      // Wrap[(SS, List[Value]) => List[(SS, Value)]](Adapter.g.reflect("proj-FunV", Unwrap(v)))
  }

  object CompileTimeRuntime {
    var funMap: collection.immutable.Map[String, FunctionDef] = SMap()
    var funDeclMap: collection.immutable.Map[String, FunctionDecl] = SMap()
    var globalDefMap: collection.immutable.Map[String, GlobalDef] = SMap()
    var heapEnv: collection.immutable.Map[String, Rep[Addr]] = SMap()

    val BBFuns: collection.mutable.HashMap[BB, Rep[SS => List[(SS, Value)]]] =
      new collection.mutable.HashMap[BB, Rep[SS => List[(SS, Value)]]]
    val FunFuns: collection.mutable.HashMap[String, Rep[SS] => Rep[List[(SS, Value)]]] =
      new collection.mutable.HashMap[String, Rep[SS] => Rep[List[(SS, Value)]]]

    def getTySize(vt: LLVMType, align: Int = 1): Int = vt match {
      case ArrayType(size, ety) =>
        val rawSize = size * getTySize(ety, align)
        if (rawSize % align == 0) rawSize
        else (rawSize / align + 1) * align
      case _ => 1
    }

    def calculateOffset(ty: LLVMType, index: List[Int]): Int = {
      if (index.isEmpty) 0 else ty match {
        case PtrType(ety, addrSpace) =>
          index.head * getTySize(ety) + calculateOffset(ety, index.tail)
        case ArrayType(size, ety) =>
          index.head * getTySize(ety) + calculateOffset(ety, index.tail)
        case _ => ???
      }
    }

    def flattenArray(cst: Constant): List[Constant] = cst match {
      case ArrayConst(xs) =>
        xs.map(typC => typC.const).foldRight(SList[Constant]())((con, ls) => flattenArray(con) ++ ls)
      case _ => SList(cst)
    }

    def findBlock(fname: String, lab: String): Option[BB] = {
      funMap.get(fname).get.lookupBlock(lab)
    }
    def findFirstBlock(fname: String): BB = {
      findFundef(fname).body.blocks(0)
    }
    def findFundef(fname: String) = funMap.get(fname).get
  }

  object Primitives {
    import Value._
    def __printf(s: Rep[SS], args: Rep[List[Value]]): Rep[List[(SS, Value)]] = {
      // generate printf
      ???
    }
    def printf: Rep[Value] = FunV(fun(__printf))

    def __read(s: Rep[SS], args: Rep[List[Value]]): Rep[List[(SS, Value)]] = {
      ???
    }
    def read: Rep[Value] = FunV(fun(__read))
  }

  object Magic {
    def reify[T: Manifest](s: Rep[SS])(comp: Comp[E, Rep[T]]): Rep[List[(SS, T)]] = {
      val p1: Comp[Nondet ⊗ ∅, (Rep[SS], Rep[T])] =
        State.run2[Nondet ⊗ ∅, Rep[SS], Rep[T]](s)(comp)
      val p2: Comp[Nondet ⊗ ∅, Rep[(SS, T)]] = p1.map(a => a)
      val p3: Comp[∅, Rep[List[(SS, T)]]] = runRepNondet(p2)
      p3
    }

    def reflect[T: Manifest](res: Rep[List[(SS, T)]]): Comp[E, Rep[T]] = {
      for {
        ssu <- select[E, (SS, T)](res)
        _ <- put[Rep[SS], E](ssu._1)
      } yield ssu._2
    }

    def mapM[A, B](xs: List[A])(f: A => Comp[E, B]): Comp[E, List[B]] = xs match {
      case Nil => ret(SList())
      case x::xs =>
        for {
          b <- f(x)
          bs <- mapM(xs)(f)
        } yield b::bs
    }
  }

  // TODO:
  // eval ArrayConst
  // ICmpInst
  // PhiInst, record branches in SS
  // SwitchTerm
  import Mem._
  import Addr._
  import Value._
  import CompileTimeRuntime._
  import Magic._

  def eval(v: LLVMValue): Comp[E, Rep[Value]] = {
    v match {
      case LocalId(x) => 
        for { f <- curFrame } yield { frameLookup(f, localAddr(f, x)) }
      case IntConst(n) => ret(IntV(n))
      // case ArrayConst(cs) => 
      case BitCastExpr(from, const, to) =>
        eval(const)
      case BoolConst(b) => b match {
        case true => ret(IntV(1))
        case false => ret(IntV(0))
      }
      // case CharArrayConst(s) => 
      case GlobalId(id) if funMap.contains(id) =>
        val funDef = funMap(id)
        val params: List[String] = funDef.header.params.map {
          case TypedParam(ty, attrs, localId) => localId.get
        }
        if (!CompileTimeRuntime.FunFuns.contains(id)) {
          precompileFunctions(SList(funMap(id)))
        }
        val f: Rep[SS] => Rep[List[(SS, Value)]] = CompileTimeRuntime.FunFuns(id)
        def repf(s: Rep[SS], args: Rep[List[Value]]): Rep[List[(SS, Value)]] = {
          val m: Comp[E, Rep[Value]] = for {
            _ <- frameUpdate(params, args)
            s <- getState
            v <- reflect(f(s))
          } yield v
          reify(s)(m)
        }
        ret(FunV(topFun(repf)))
        // ret(FunV(fun(repf)))
      case GlobalId(id) if funDeclMap.contains(id) => 
        val v = id match {
          case "@printf" => Primitives.printf
          case "@read" => Primitives.read
          case "@exit" => ??? // returns nondet fail? this should be something like a break
          case "@sleep" => ??? //noop
        }
        ret(v)
      case GlobalId(id) if globalDefMap.contains(id) =>
        for { h <- getHeap } yield lookup(h, heapAddr(id))
      case GetElemPtrExpr(_, baseType, ptrType, const, typedConsts) => 
        val indexValue: List[Int] = typedConsts.map(tv => tv.const.asInstanceOf[IntConst].n)
        val offset = calculateOffset(ptrType, indexValue)
        const match {
          case GlobalId(id) => ret(LocV(heapEnv(id) + offset))
          case _ => for {
            lV <- eval(const)
          } yield LocV(projLocV(lV) + offset)
        }
      case ZeroInitializerConst => ret(IntV(0))
    }
  }

  def execValueInst(inst: ValueInstruction): Comp[E, Rep[Value]] = {
    inst match {
      case AllocaInst(ty, align) =>
        for {
          f <- curFrame
          a <- frameAlloc(getTySize(ty, align.n))
        } yield LocV(a)
      case LoadInst(valTy, ptrTy, value, align) =>
        for {
          v <- eval(value)
          σ <- selectMem(v)
        } yield lookup(σ, projLocV(v))
      case AddInst(ty, lhs, rhs, _) =>
        for {
          v1 <- eval(lhs)
          v2 <- eval(rhs)
        } yield IntV(projIntV(v1) + projIntV(v2))
      case SubInst(ty, lhs, rhs, _) =>
        for {
          v1 <- eval(lhs)
          v2 <- eval(rhs)
        } yield IntV(projIntV(v1) - projIntV(v2))
      case ICmpInst(pred, ty, lhs, rhs) =>
        for {
          val1 <- eval(lhs)
          val2 <- eval(rhs)
        } yield {
          val v1 = projIntV(val1)
          val v2 = projIntV(val2)
          pred match {
            case EQ => IntV(if (v1 == v2) 1 else 0)
            case NE => IntV(if (v1 != v2) 1 else 0)
            case SLT => IntV(if (v1 < v2) 1 else 0)
            case SLE => IntV(if (v1 <= v2) 1 else 0)
            case SGT => IntV(if (v1 > v2) 1 else 0)
            case SGE => IntV(if (v1 >= v2) 1 else 0)
            case ULT => IntV(if (v1 < v2) 1 else 0)
            case ULE => IntV(if (v1 <= v2) 1 else 0)
            case UGT => IntV(if (v1 > v2) 1 else 0)
            case UGE => IntV(if (v1 >= v2) 1 else 0)
          }
        }
      case ZExtInst(from, value, to) => for {
        v <- eval(value)
      } yield IntV(projIntV(v), to.asInstanceOf[IntType].size)
      case SExtInst(from, value, to) =>  for {
        v <- eval(value)
      } yield IntV(projIntV(v), to.asInstanceOf[IntType].size)
      case CallInst(ty, f, args) => 
        val argValues: List[LLVMValue] = args.map {
          case TypedArg(ty, attrs, value) => value
        }
        for {
          fv <- eval(f)
          vs <- mapM(argValues)(eval)
          // FIXME: potentially problematic: 
          // f could be bitCast as well
          // GW: yes, f could be bitCast, but after the evaluation, fv should be a function, right?
          _ <- pushFrame(f.asInstanceOf[GlobalId].id)
          s <- getState
          v <- reflect(projFunV(fv)(s, List(vs:_*)))
          _ <- popFrame
        } yield v
      case GetElemPtrInst(_, baseType, ptrType, ptrValue, typedValues) =>
        // it seems that typedValues must be IntConst
        val indexValue: List[Int] = typedValues.map(tv => tv.value.asInstanceOf[IntConst].n)
        val offset = calculateOffset(ptrType, indexValue)
        ptrValue match {
          case GlobalId(id) => ret(LocV(heapEnv(id) + offset))
          case _ => for {
            lV <- eval(ptrValue)
          } yield LocV(projLocV(lV) + offset)
        }
      case PhiInst(ty, incs) => ???
      case SelectInst(cndTy, cndVal, thnTy, thnVal, elsTy, elsVal) =>
        for {
          cnd <- eval(cndVal)
          v <- choice(
            for {
              _ <- updatePC(cnd)
              v <- eval(thnVal)
            } yield v,
            for {
              _ <- updatePC(/* not */cnd)
              v <- eval(elsVal)
            } yield v
          )
        } yield v
    }
  }

  // Note: Comp[E, Rep[Value]] vs Comp[E, Rep[Option[Value]]]?
  def execTerm(funName: String, inst: Terminator): Comp[E, Rep[Value]] = {
    inst match {
      case RetTerm(ty, Some(value)) => eval(value)
      case RetTerm(ty, None) => ret(IntV(0))
      case BrTerm(lab) =>
        execBlock(funName, lab)
        // branches = lab :: branches
      case CondBrTerm(ty, cnd, thnLab, elsLab) =>
        // TODO: needs to consider the case wehre cnd is a concrete value
        // val cndM: Rep[SMTExpr] = ???
        for {
          cndVal <- eval(cnd)
          v <- choice(
            for {
              _ <- updatePC(cndVal)
              v <- execBlock(funName, thnLab)
            } yield v,
            for {
              _ <- updatePC(/* not */cndVal)
              // update branches
              v <- execBlock(funName, elsLab)
            } yield v)
        } yield v
      case SwitchTerm(cndTy, cndVal, default, table) =>
        // TODO: cndVal can be either concrete or symbolic
        // TODO: if symbolic, update PC here, for default, take the negation of all other conditions
        def switchFun(v: Rep[Int], s: Rep[SS], table: List[LLVMCase]): Rep[List[(SS, Value)]] = {
          if (table.isEmpty) execBlock(funName, default, s)
          else {
            if (v == table.head.n) execBlock(funName, table.head.label, s)
            else switchFun(v, s, table.tail)
          }
        }
        for {
          v <- eval(cndVal)
          s <- getState
          r <- reflect(switchFun(projIntV(v), s, table))
        } yield r
    }
  }

  def execInst(fun: String, inst: Instruction): Comp[E, Rep[Unit]] = {
    inst match {
      case AssignInst(x, valInst) =>
        for {
          v <- execValueInst(valInst)
          _ <- frameUpdate(x, v)
        } yield ()
      case StoreInst(ty1, val1, ty2, val2, align) =>
        for {
          v1 <- eval(val1)
          v2 <- eval(val2)
          _ <- updateMem(v2, v1)
        } yield ()
      case CallInst(ty, f, args) =>
        val argValues: List[LLVMValue] = args.map {
          case TypedArg(ty, attrs, value) => value
        }
        for {
          fv <- eval(f)
          vs <- mapM(argValues)(eval)
          // FIXME: potentially problematic: 
          // f could be bitCast as well
          _ <- pushFrame(f.asInstanceOf[GlobalId].id)
          s <- getState
          v <- reflect(projFunV(fv)(s, List(vs:_*)))
          _ <- popFrame
        } yield ()
    }
  }

  def execBlock(funName: String, label: String, s: Rep[SS]): Rep[List[(SS, Value)]] = {
    val Some(block) = findBlock(funName, label)
    execBlock(funName, block, s)
  }

  def execBlock(funName: String, bb: BB, s: Rep[SS]): Rep[List[(SS, Value)]] = {
    if (!CompileTimeRuntime.BBFuns.contains(bb)) {
      precompileBlocks(funName, SList(bb))
    }
    val f = CompileTimeRuntime.BBFuns(bb)
    f(s)
  }

  def execBlock(funName: String, label: String): Comp[E, Rep[Value]] = {
    val Some(block) = findBlock(funName, label)
    execBlock(funName, block)
  }

  def execBlock(funName: String, bb: BB): Comp[E, Rep[Value]] = {
    for {
      s <- getState
      v <- reflect(execBlock(funName, bb, s))
    } yield v
  }

  def precompileHeap(heap: Rep[Heap]): Rep[Heap] = {
    def evalConst(v: Constant): List[Rep[Value]] = v match {
      case BoolConst(b) =>
        SList(IntV(if (b) 1 else 0, 1))
      case IntConst(n) =>
        SList(IntV(n))
      case ZeroInitializerConst =>
        SList(IntV(0))
      case ArrayConst(cs) =>
        flattenArray(v).flatMap(c => evalConst(c))
      case CharArrayConst(s) =>
        s.map(c => IntV(c.toInt, 8)).toList
    }

    CompileTimeRuntime.globalDefMap.foldRight(heap) {case ((k, v), h) =>
      val (allocH, addr) = alloc(h, getTySize(v.typ))
      CompileTimeRuntime.heapEnv = CompileTimeRuntime.heapEnv + (k -> addr)
      updateL(allocH, addr, List(evalConst(v.const):_*))
    }
  }

  def precompileHeapM: Comp[E, Rep[Unit]] = {
    for {
      h <- getHeap
      _ <- putHeap(precompileHeap(h))
    } yield ()
  }

  def precompileBlocks(funName: String, blocks: List[BB]): Unit = {
    def runInstList(is: List[Instruction], term: Terminator): Comp[E, Rep[Value]] = {
      for {
        _ <- mapM(is)(execInst(funName, _))
        v <- execTerm(funName, term)
      } yield v
    }
    def runBlock(b: BB)(ss: Rep[SS]): Rep[List[(SS, Value)]] = {
      reify[Value](ss)(runInstList(b.ins, b.term))
    }

    for (b <- blocks) {
      // FIXME: topFun or fun?
      if (CompileTimeRuntime.BBFuns.contains(b)) {
        System.err.println("Already compiled " + b)
      } else {
        val repRunBlock: Rep[SS => List[(SS, Value)]] = fun(runBlock(b))
        CompileTimeRuntime.BBFuns(b) = repRunBlock
      }
    }
  }

  def precompileFunctions(funs: List[FunctionDef]): Unit = {
    def runFunction(f: FunctionDef): Comp[E, Rep[Value]] = {
      precompileBlocks(f.id, f.blocks)
      execBlock(f.id, f.blocks(0))
    }
    def repRunFun(f: FunctionDef)(ss: Rep[SS]): Rep[List[(SS, Value)]] = {
      reify[Value](ss)(runFunction(f))
    }

    for (f <- funs) {
      if (CompileTimeRuntime.FunFuns.contains(f.id)) {
        System.err.println("Already compiled " + f)
      } else {
        // FIXME: topFun or fun?
        CompileTimeRuntime.FunFuns(f.id) = repRunFun(f)
      }
    }
  }

  def exec(m: Module, fname: String): Rep[List[(SS, Value)]] = {
    CompileTimeRuntime.funMap = m.funcDefMap
    CompileTimeRuntime.funDeclMap = m.funcDeclMap
    CompileTimeRuntime.globalDefMap = m.globalDefMap

    val Some(f) = m.lookupFuncDef(fname)
    precompileFunctions(SList(f))
    val repf: Rep[SS] => Rep[List[(SS, Value)]] = CompileTimeRuntime.FunFuns(fname)

    val heap0 = precompileHeap(emptyMem)
    // TODO: put the initial frame mem
    val comp = for {
      _ <- pushFrame(fname)
      s <- getState
      v <- reflect(repf(s))
      _ <- popFrame
    } yield v
    val initState: Rep[SS] = (emptyMem, List[Frame](), Set[SMTExpr]())
    reify[Value](initState)(comp)
  }
}

trait SymStagedLLVMGen extends CppSAICodeGenBase {
  registerHeader("./headers", "<sai_llvm_sym2.hpp>")

  override def mayInline(n: Node): Boolean = n match {
    case Node(_, name, _, _) if name.startsWith("IntV") => false
    case Node(_, name, _, _) if name.startsWith("LocV") => false
    case _ => super.mayInline(n)
  }

  override def quote(s: Def): String = s match {
    case Const(()) => "std::monostate{}";
    case _ => super.quote(s)
  }

  // Note: depends on the concrete representation of Mem, we can emit different code
  override def shallow(n: Node): Unit = n match {
    // case Node(s, "mem-lookup", List(σ, a), _) =>
    case _ => super.shallow(n)
  }
}

trait CppSymStagedLLVMDriver[A, B] extends CppSAIDriver[A, B] with StagedSymExecEff { q =>
  override val codegen = new CGenBase with SymStagedLLVMGen {
    val IR: q.type = q
    import IR._

    override def primitive(t: String): String = t match {
      case "Unit" => "std::monostate"
      case _ => super.primitive(t)
    }

    override def remap(m: Manifest[_]): String = {
      if (m.toString == "java.lang.String") "String"
      else if (m.toString.endsWith("$Value")) "Ptr<Value>"
      else if (m.toString.endsWith("$Addr")) "Addr"
      else if (m.toString.endsWith("$Mem")) "Mem"
      else super.remap(m)
    }
  }
}

object TestStagedLLVM {
  def parse(file: String): Module = {
    val input = scala.io.Source.fromFile(file).mkString
    sai.llvm.LLVMTest.parse(input)
  }

  val add = parse("llvm/benchmarks/add.ll")
  val power = parse("llvm/benchmarks/power.ll")
  val singlepath = parse("llvm/benchmarks/single_path5.ll")
  val branch = parse("llvm/benchmarks/branch2.ll")
  val multipath= parse("llvm/benchmarks/multipath.ll")

  @virtualize
  def specialize(m: Module, fname: String): CppSAIDriver[Int, Unit] =
    new CppSymStagedLLVMDriver[Int, Unit] {
      def snippet(u: Rep[Int]) = {
        //def exec(m: Module, fname: String, s0: Rep[Map[Loc, Value]]): Rep[List[(SS, Value)]]
        //val s = Map(FrameLoc("f_%x") -> IntV(5), FrameLoc("f_%y") -> IntV(2))
        //val s = Map(FrameLoc("%x") -> IntV(5))
        // val s = Map(FrameLoc("f_%a") -> IntV(5),
        // FrameLoc("f_%b") -> IntV(6),
        //FrameLoc("f_%c") -> IntV(7))
        val s = Map()
        val res = exec(m, fname)
        println(res.size)
      }
    }

  def main(args: Array[String]): Unit = {
    val code = specialize(add, "@main")
    //val code = specialize(singlepath, "@singlepath")
    //val code = specialize(branch, "@f")
    // val code = specialize(multipath, "@f")
    //println(code.code)
    //code.eval(5)
    code.save("add.cpp")
    println(code.code)
    println("Done")
  }
}
