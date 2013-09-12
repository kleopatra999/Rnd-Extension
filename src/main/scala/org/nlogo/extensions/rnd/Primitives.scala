// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.extensions.rnd

import scala.Array.canBuildFrom
import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.collection.immutable.SortedSet
import scala.collection.mutable.ListBuffer

import org.nlogo.agent
import org.nlogo.api.Argument
import org.nlogo.api.Context
import org.nlogo.api.DefaultReporter
import org.nlogo.api.Dump
import org.nlogo.api.ExtensionException
import org.nlogo.api.I18N
import org.nlogo.api.LogoList
import org.nlogo.api.LogoListBuilder
import org.nlogo.api.Syntax._
import org.nlogo.nvm
import org.nlogo.util.MersenneTwisterFast

trait WeightedRndPrim extends DefaultReporter {
  val name: String

  def getCandidates(n: Int, arg: Argument): Vector[AnyRef] = {
    val candidates =
      arg.get match {
        case list: LogoList           ⇒ list.toVector
        case agentSet: agent.AgentSet ⇒ Vector() ++ agentSet.agents.asScala
      }
    if (candidates.size < n) throw new ExtensionException(
      "Requested " + pluralize(n, "random item") +
        " from " + pluralize(candidates.size, "candidate") + ".")
    candidates
  }

  def getWeightFunction(arg: Argument, context: Context): (AnyRef) ⇒ Double = {
    val task = arg.getReporterTask.asInstanceOf[nvm.ReporterTask]
    if (task.formals.size > 1) throw new ExtensionException(
      "Task expected only 1 input but got " + task.formals.size + ".")
    (obj: AnyRef) ⇒ {
      val res = task.report(context, Array(obj))
      val w = try
        res.asInstanceOf[Number].doubleValue
      catch {
        case e: ClassCastException ⇒ throw new ExtensionException(
          "Got " + Dump.logoObject(res) + " as a weight but all weights must be numbers.")
      }
      if (w < 0.0) throw new ExtensionException(
        "Got " + w + " as a weight but all weights must be >= 0.0.")
      w
    }
  }

  def pickIndices(n: Int, candidates: Vector[AnyRef],
    weightFunction: (AnyRef) ⇒ Double, rng: MersenneTwisterFast): SortedSet[Int] = {

    if (n == candidates.size)
      SortedSet[Int](candidates.indices: _*)
    else {
      val weights: Array[Double] = candidates.map(weightFunction)(breakOut)
      var unselectedIndices = ListBuffer[Int](weights.indices: _*)
      (1 to n).map { _ ⇒
        val originalIndices = unselectedIndices.toArray
        val ws = unselectedIndices.map(weights)
        val sum = ws.sum
        val i = originalIndices(
          if (sum == 0.0) { // if we only have 0s left, just pick one at random
            rng.nextInt(unselectedIndices.length)
          } else {
            val probs = ws.map(w => Double.box(w / sum)).asJava
            new AliasMethod(probs, rng).next
          })
        unselectedIndices -= i
        i
      }(breakOut)
    }
  }

  def pluralize(count: Int, word: String) =
    count + " " + word + (if (count != 1) "s" else "")
}

object WeightedOneOfPrim extends WeightedRndPrim {

  override val name = "WEIGHTED-ONE-OF"

  override def getSyntax = reporterSyntax(
    Array(ListType | AgentsetType, ReporterTaskType),
    WildcardType)

  def report(args: Array[Argument], context: Context): AnyRef =
    args(0).get match {
      case agentSet: agent.AgentSet if agentSet.count == 0 ⇒
        org.nlogo.api.Nobody$.MODULE$
      case _ ⇒
        val candidates: Vector[AnyRef] = getCandidates(1, args(0))
        val weightFunction = getWeightFunction(args(1), context)
        val i = pickIndices(1, candidates, weightFunction, context.getRNG).head
        candidates(i)
    }
}

object WeightedNOfPrim extends WeightedRndPrim {

  override val name = "WEIGHTED-N-OF"

  override def getSyntax = reporterSyntax(
    Array(NumberType, ListType | AgentsetType, ReporterTaskType),
    ListType | AgentsetType)

  def report(args: Array[Argument], context: Context): AnyRef = {
    val n = args(0).getIntValue
    if (n < 0) throw new ExtensionException(I18N.errors.getN(
      "org.nlogo.prim.etc.$common.firstInputCantBeNegative", name))
    val candidates: Vector[AnyRef] = getCandidates(n, args(1))
    if (n == candidates.size) return args(1).get // short-circuit everything...
    val weightFunction = getWeightFunction(args(2), context)
    val indices = pickIndices(n, candidates, weightFunction, context.getRNG)
    args(1).get match {
      case list: LogoList ⇒
        val b = new LogoListBuilder
        for (i ← indices) b.add(candidates(i))
        b.toLogoList
      case agentSet: agent.AgentSet ⇒
        val b = Array.newBuilder[agent.Agent]
        for (i ← indices) b += candidates(i).asInstanceOf[agent.Agent]
        new agent.ArrayAgentSet(agentSet.`type`, b.result, agentSet.world)
    }
  }
}
