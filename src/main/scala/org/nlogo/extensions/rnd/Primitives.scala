// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.extensions.rnd

import scala.collection.immutable.SortedSet

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

  def candidateVector(arg: Argument, rng: MersenneTwisterFast): Vector[AnyRef]

  def candidateSyntaxType: Int
  def reporterSyntaxType: Int
  def inputSyntax: Array[Int]
  def outputSyntax: Int
  override def getSyntax = reporterSyntax(inputSyntax, outputSyntax)

  def getCandidates(minSize: Int, arg: Argument, rng: MersenneTwisterFast): Vector[AnyRef] = {
    val candidates = candidateVector(arg, rng)
    def pluralize(count: Int, word: String) =
      count + " " + word + (if (count != 1) "s" else "")
    if (candidates.size < minSize) throw new ExtensionException(
      "Requested " + pluralize(minSize, "random item") +
        " from " + pluralize(candidates.size, "candidate") + ".")
    candidates
  }

  def reporterFunction(arg: Argument, context: Context): AnyRef => AnyRef

  def getWeightFunction(arg: Argument, context: Context): AnyRef ⇒ Double =
    (obj: AnyRef) ⇒ reporterFunction(arg, context)(obj) match {
      case n: Number if n.doubleValue < 0.0 =>
        throw new ExtensionException("Got " + n + " as a weight but all weights must be >= 0.0.")
      case n: Number =>
        n.doubleValue
      case x =>
        throw new ExtensionException("Got " + Dump.logoObject(x) + " as a weight but all weights must be numbers.")
    }

}

trait AgentSetPrim {

  self: DefaultReporter =>
  override def getAgentClassString = "OT:-TPL"

  def candidateSyntaxType: Int = AgentsetType
  def reporterSyntaxType: Int = NumberBlockType

  def candidateVector(arg: Argument, rng: MersenneTwisterFast): Vector[AnyRef] = {
    val it = arg.getAgentSet.asInstanceOf[agent.AgentSet].shufflerator(rng)
    val b = Vector.newBuilder[AnyRef]
    while (it.hasNext) b += it.next
    b.result
  }

  def reporterFunction(arg: Argument, context: Context): AnyRef => AnyRef = {
    val nvmContext = context.asInstanceOf[nvm.ExtensionContext].nvmContext
    val reporter = arg.asInstanceOf[nvm.Argument].getReporter
    (obj: AnyRef) => {
      val a = obj.asInstanceOf[agent.Agent]
      new nvm.Context(nvmContext, a).evaluateReporter(a, reporter)
    }
  }

}

trait ListPrim {

  def candidateSyntaxType: Int = ListType
  def reporterSyntaxType: Int = ReporterTaskType

  def candidateVector(arg: Argument, rng: MersenneTwisterFast): Vector[AnyRef] =
    arg.getList.toVector

  def reporterFunction(arg: Argument, context: Context): AnyRef => AnyRef = {
    val task = arg.getReporterTask.asInstanceOf[nvm.ReporterTask]
    if (task.formals.size > 1) throw new ExtensionException(
      "Task expected only 1 input but got " + task.formals.size + ".")
    (obj: AnyRef) ⇒ task.report(context, Array(obj))
  }

}

trait WeightedOneOf extends WeightedRndPrim {
  def inputSyntax = Array(candidateSyntaxType, reporterSyntaxType)
  def report(args: Array[Argument], context: Context): AnyRef =
    args(0).get match {
      case agentSet: agent.AgentSet if agentSet.count == 0 ⇒
        org.nlogo.api.Nobody$.MODULE$
      case _ ⇒
        val rng = context.getRNG
        val candidates: Vector[AnyRef] = getCandidates(1, args(0), rng)
        val weightFunction = getWeightFunction(args(1), context)
        val i = Picker.pickIndicesWithRepeats(1, candidates, weightFunction, rng).head
        candidates(i)
    }
}

object WeightedOneOfAgentSet extends WeightedOneOf with AgentSetPrim {
  val name = "WEIGHTED-ONE-OF"
  val outputSyntax = AgentType
}

object WeightedOneOfList extends WeightedOneOf with ListPrim {
  val name = "WEIGHTED-ONE-OF-LIST"
  val outputSyntax = WildcardType
}

trait WeightedNOf extends WeightedRndPrim {

  def inputSyntax = Array(NumberType, candidateSyntaxType, reporterSyntaxType)

  val pickIndices: (Int, Vector[AnyRef], AnyRef => Double, MersenneTwisterFast) => Iterable[Int]
  def minSize(n: Int): Int

  def outputBuilder(candidatesArg: Argument, candidates: Vector[AnyRef], indices: Iterable[Int]): AnyRef

  def report(args: Array[Argument], context: Context): AnyRef = {
    val n = args(0).getIntValue
    if (n < 0) throw new ExtensionException(I18N.errors.getN(
      "org.nlogo.prim.etc.$common.firstInputCantBeNegative", name))
    val rng = context.getRNG
    val candidates: Vector[AnyRef] = getCandidates(minSize(n), args(1), rng)
    val weightFunction = getWeightFunction(args(2), context)
    val indices = pickIndices(n, candidates, weightFunction, rng)
    outputBuilder(args(1), candidates, indices)
  }
}

trait ListBuilder {
  val outputSyntax = ListType
  def outputBuilder(candidatesArg: Argument, candidates: Vector[AnyRef], indices: Iterable[Int]) = {
    val b = new LogoListBuilder
    for (i ← indices) b.add(candidates(i))
    b.toLogoList
  }
}

trait AgentSetBuilder {
  val outputSyntax = AgentsetType
  def outputBuilder(candidatesArg: Argument, candidates: Vector[AnyRef], indices: Iterable[Int]) = {
    val originalAgentSet = candidatesArg.get.asInstanceOf[agent.AgentSet]
    val b = Array.newBuilder[agent.Agent]
    for (i ← indices) b += candidates(i).asInstanceOf[agent.Agent]
    new agent.ArrayAgentSet(originalAgentSet.`type`, b.result, originalAgentSet.world)
  }
}

trait WithRepeats extends WeightedNOf with ListBuilder {
  val pickIndices = Picker.pickIndicesWithRepeats _
  def minSize(n: Int) = math.min(n, 1)
}

trait WithoutRepeats extends WeightedNOf {
  val pickIndices = Picker.pickIndicesWithoutRepeats _
  def minSize(n: Int) = n
}

object WeightedNOfAgentSetWithRepeats extends WithRepeats with AgentSetPrim {
  val name = "WEIGHTED-N-OF-WITH-REPEATS"
}

object WeightedNOfListWithRepeats extends WithRepeats with ListPrim {
  val name = "WEIGHTED-N-OF-LIST-WITH-REPEATS"
}

object WeightedNOfAgentSetWithoutRepeats extends WithoutRepeats with AgentSetPrim with AgentSetBuilder {
  val name = "WEIGHTED-N-OF"
}

object WeightedNOfListWithoutRepeats extends WithoutRepeats with ListPrim with ListBuilder {
  val name = "WEIGHTED-N-OF-LIST"
}
