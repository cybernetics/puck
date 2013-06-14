package puck.parser.gen

import scala.virtualization.lms.internal.{FatExpressions, Effects, Expressions}
import trochee.codegen._
import trochee.basic.{SpireOpsExp, GenSpireOps}
import scala.virtualization.lms.common.{BooleanOpsExp, IfThenElseExp}

trait OpenCLParserGen[L] extends OpenCLKernelCodegen with OpenCLKernelGenVariables with GenSpireOps {
  val IR: Expressions with Effects with FatExpressions with trochee.kernels.KernelOpsExp with AccumulatorOpsExp with BooleanOpsExp  with IfThenElseExp with SpireOpsExp with FloatOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) {
    rhs match {
      case Mad(a,b,c) =>
        emitValDef(sym, s"mad(${quote(a)}, ${quote(b)}, ${quote(c)})")
      case LogAdd(a, b) =>
        emitValDef(sym, s"${quote(a)} == -INFINITY ? ${quote(b)} : (${quote(a)} + log(1.0f + exp(${quote(b)} - ${quote(a)})))")
      case Log(a) =>
        cacheAndEmit(sym, s"log(${quote(a)})")
      case BooleanNegate(b) => emitValDef(sym, "!" + quote(b))
      case BooleanAnd(lhs,rhs) => emitValDef(sym, quote(lhs) + " && " + quote(rhs))
      case BooleanOr(lhs,rhs) => emitValDef(sym, quote(lhs) + " || " + quote(rhs))
      case Printf(str, args) =>
        val call = s"""printf(${(quote(str) +: args.map(quote _)).mkString(", ")});"""
        cacheAndEmit(sym, call)

      case _ => super.emitNode(sym, rhs)
    }

  }
}
