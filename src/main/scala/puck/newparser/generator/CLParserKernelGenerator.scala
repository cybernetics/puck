package puck.newparser
package generator

import trochee.kernels.KernelOpsExp
import scala.virtualization.lms.common._
import trochee.basic.SpireOpsExp
import com.nativelibs4java.opencl._
import puck.parser.gen._

/**
 * TODO
 *
 * @author dlwh
 **/
class CLParserKernelGenerator[C, L](structure: RuleStructure[C, L])(implicit context: CLContext) { self =>
  val IR = new KernelOpsExp with RangeOpsExp with IfThenElseExp with SpireOpsExp
    with FloatOpsExp with RuleMultiply[L] with LogSpaceFloatOpsExp with AccumulatorOpsExp with BooleanOpsExp {

  }
  val gen = new ParserGen[L] {
    val IR : self.IR.type = self.IR
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
  val insideGen = new CLInside(structure, gen)

}
