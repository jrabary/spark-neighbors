package com.github.karlhigley.spark.neighbors

import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.mllib.rdd.MLPairRDDFunctions._
import org.apache.spark.storage.StorageLevel

import com.github.karlhigley.spark.neighbors.candidates.CandidateStrategy
import com.github.karlhigley.spark.neighbors.linalg.DistanceMeasure
import com.github.karlhigley.spark.neighbors.lsh.{ HashTableEntry, LSHFunction, Signature }

/**
 * Model containing hash tables produced by computing signatures
 * for each supplied vector.
 */
class ANNModel private[neighbors] (
    private[neighbors] val hashTables: RDD[_ <: HashTableEntry[_]],
    private[neighbors] val candidateStrategy: CandidateStrategy,
    private[neighbors] val measure: DistanceMeasure
) extends Serializable {

  type Point = (Int, SparseVector)
  type CandidateGroup = (Iterable[Point], Iterable[Point])

  /**
   * Identify pairs of nearest neighbors by applying a
   * candidate strategy to the hash tables and then computing
   * the actual distance between candidate pairs.
   */
  def neighbors(quantity: Int): RDD[(Int, Array[(Int, Double)])] = {
    val candidates = candidateStrategy.identify(hashTables).repartition(hashTables.getNumPartitions)
    val neighbors = computeDistances(candidates)
    neighbors.topByKey(quantity)(ANNModel.ordering)
  }

  /**
   * Compute the actual distance between candidate pairs
   * using the supplied distance measure.
   */
  private def computeDistances(candidates: RDD[CandidateGroup]): RDD[(Int, (Int, Double))] = {
    candidates
      .flatMap {
        case (listA, listB) => {
          for (
            (id1, vector1) <- listA.iterator;
            (id2, vector2) <- listB.iterator;
            if id1 < id2
          ) yield ((id1, id2), measure.compute(vector1, vector2))
        }
      }
      .reduceByKey((a, b) => a)
      .flatMap {
        case ((id1, id2), dist) => Array((id1, (id2, dist)), (id2, (id1, dist)))
      }
  }
}

object ANNModel {
  private val ordering = Ordering[Double].on[(Int, Double)](_._2).reverse

  /**
   * Train a model by computing signatures for the supplied
   * points
   */
  def train(
    points: RDD[(Int, SparseVector)],
    hashFunctions: Array[_ <: LSHFunction[_]],
    CandidateStrategy: CandidateStrategy,
    measure: DistanceMeasure,
    persistenceLevel: StorageLevel
  ): ANNModel = {

    val indHashFunctions: Array[(_ <: LSHFunction[_], Int)] = hashFunctions.zipWithIndex
    val hashTables: RDD[_ <: HashTableEntry[_]] = points.flatMap {
      case (id, vector) =>
        indHashFunctions.map {
          case (hashFunc, table) =>
            hashFunc.hashTableEntry(id, table, vector)
        }
    }
    hashTables.persist(persistenceLevel)
    new ANNModel(
      hashTables,
      CandidateStrategy,
      measure
    )
  }
}
