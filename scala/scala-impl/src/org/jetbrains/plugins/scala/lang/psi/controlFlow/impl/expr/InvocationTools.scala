package org.jetbrains.plugins.scala.lang.psi.controlFlow.impl.expr

import com.intellij.psi.{PsiElement, PsiNamedElement}
import org.jetbrains.plugins.scala.dfa.{DfConcreteLambdaRef, DfEntity, DfLocalVariable, DfRegister, DfVariable}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScBlockExpr, ScExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.controlFlow.{CfgBuilder, ControlFlowGraph}
import org.jetbrains.plugins.scala.lang.psi.controlFlow.cfg.{ExprResult, RequireResult, ResultRequirement}
import org.jetbrains.plugins.scala.lang.psi.types.ScType
import org.jetbrains.plugins.scala.lang.psi.types.nonvalue.Parameter

object InvocationTools {
  /*
    TODO: We have the following cases when we encounter a method invocation
    - [x] calls to functions
    - [x] calls to methods (=> need thisref)
    - [x] calls to function objects
    - [x] calls to apply
    - [x] calls to update
    - [~] calls with assignment (i.e. +=, -=, ::=)
    - [ ] property updates (a.prop = 33 -> a.prop_=(33))
    - [x] calls that have changed precedence (::, everything with : as last char)
    - [ ] imported member methods
    - [ ] with or without implicits
    - [ ] with or without generics
    - [x] multiple parameter clauses
    - [ ] auto tupeling
    - [x] default parameter
    - [x] named arguments (potentially reordered)
    - [ ] varargs
    - [ ] dynamics
   */

  case class InvocationInfo(thisExpr: Option[ScExpression],
                            funcRef: Option[PsiElement],
                            params: Seq[(ScExpression, Parameter)]) {
    def build(rreq: ResultRequirement)(implicit builder: CfgBuilder): ExprResult =
      buildWithoutThis(rreq, buildThisRef())

    def buildWithoutThis(rreq: ResultRequirement, thisRef: Option[DfEntity])(implicit builder: CfgBuilder): ExprResult = {
      val (ret, result) = rreq.tryPin()
      val paramRegs = params.sortBy(ArgumentSorting.exprPosition).map {
        case (LambdaFromCaseClauseBlock(lambda), param) =>
          param -> lambda
        case (expr, param) =>
          val reg = expr.buildExprControlFlow(RequireResult).pin
          param -> reg
      }

      val args = paramRegs.sortBy(ArgumentSorting.paramPosition).map(_._2)

      builder.call(thisRef, funcRef, ret, args)

      result
    }

    def buildRightAssoc(rreq: ResultRequirement)(implicit builder: CfgBuilder): ExprResult = {
      // For 'a :: b'
      // 1. evaluate a
      // 2. evaluate b
      // 3. evaluate default and implicit parameters
      val (leftExpr, _) +: defaultOrImplicitParams = this.params.sortBy(ArgumentSorting.exprPosition)

      val (ret, result) = rreq.tryPin()
      val actualArgRef = leftExpr.buildExprControlFlow(RequireResult).pin
      val thisRef = buildThisRef()
      val defaultOrImplicitArgRefs = defaultOrImplicitParams.map {
        case (expr, _) =>
          expr.buildExprControlFlow(RequireResult).pin
      }

      builder.call(thisRef, funcRef, ret, actualArgRef +: defaultOrImplicitArgRefs)
      result
    }

    def buildThisRef()(implicit builder: CfgBuilder): Option[DfEntity] =
      this.thisExpr.map(_.buildExprControlFlow(RequireResult).pin)
  }

  object LambdaFromCaseClauseBlock {
    def unapply(caseBlock: ScBlockExpr with ScBlockExprCfgBuildingImpl)
               (implicit builder: CfgBuilder): Option[DfConcreteLambdaRef] = {
      if (!caseBlock.isAnonymousFunction) return None

      val caseBlockParamVar = DfLocalVariable(caseBlock, "block$param")
      val incomingType = caseBlock.caseClauseIncomingType(caseBlock.elementScope)
        .getOrElse(builder.projectContext.stdTypes.Any)
      val caseBlockParam = new DfConcreteLambdaRef.Parameter(caseBlockParamVar, incomingType)

      val caseBlockCfg =
        buildCaseClausesControlFlow(caseBlock, caseBlockParamVar, builder.createSubBuilder())

      val lambda = new DfConcreteLambdaRef(caseBlock, Seq(caseBlockParam), caseBlockCfg)

      Some(lambda)
    }

    private def buildCaseClausesControlFlow(caseBlock: ScBlockExpr with ScBlockExprCfgBuildingImpl,
                                            caseBlockParamVar: DfLocalVariable,
                                            builder: CfgBuilder): ControlFlowGraph = {
      implicit val _builder: CfgBuilder = builder

      val caseBlockParamReg = builder.pinToNewRegister(caseBlockParamVar)
      val result = caseBlock.buildCaseClausesControlFlow(caseBlockParamReg, RequireResult).pin
      builder.ret(result)
      builder.build()
    }
  }

  object ArgumentSorting {
    def exprPosition(t: (ScExpression, Parameter)): (Int, Int) = {
      val (expr, param) = t
      // actually supplied arguments have to be evaluated before default parameters
      val notDefault = expr.parent.exists(!_.isInstanceOf[ScParameter])
      if (notDefault) 0 -> expr.getTextOffset
      else 1 -> param.index
    }
    def paramPosition(t: (Parameter, DfEntity)): Int = t._1.index
  }
}