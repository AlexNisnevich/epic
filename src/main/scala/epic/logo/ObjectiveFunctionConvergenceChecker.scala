package epic.logo

import scala.collection.Seq

case class ObjectiveFunctionConvergenceChecker[W](
    objective: ObjectiveFunction[W], maxNumIters: Int,
    callback: IterationCallback[_, W], tol: Double) extends ConvergenceChecker[W] {

  def converged(weights: Weights[W], data: Seq[Instance[_, W]], iter: Int, numNewConstraints: Int): Boolean = {
    iter >= maxNumIters || (objectiveConverged(weights, data, iter) && numNewConstraints == 0)
  }

  private def objectiveConverged(weights: Weights[W], data: Seq[Instance[_, W]], iter: Int) = {
    val (primal, dual) = objective.calculatePrimalAndDual(weights, data)
    callback.objectiveValCheck(primal, dual, iter, weights)
    NumUtils.approxEquals(primal, dual, 1e-10)
  }

}
