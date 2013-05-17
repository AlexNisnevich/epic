package epic
package features

import breeze.collection.mutable.TriangularArray
import breeze.util.Index
import com.google.common.collect.MapMaker
import epic.framework.Feature
import epic.features.FeaturizationLevel.FullFeatures
import java.io.IOException
import java.util.Collections
import scala.collection.mutable
import epic.constraints.SpanConstraints
import epic.util.Cache

/**
 *
 * @author dlwh
 */
trait IndexedSurfaceFeaturizer[W] extends IndexedWordFeaturizer[W] {
  def spanFeatureIndex: Index[Feature]
  def featurizer: SurfaceFeaturizer[W]
  def anchor(datum: IndexedSeq[W]):IndexedSurfaceAnchoring[W]
}

trait IndexedSurfaceAnchoring[W] extends IndexedWordAnchoring[W] {
  def featuresForSpan(begin: Int, end: Int, level: FeaturizationLevel = FullFeatures):Array[Int]
}

object IndexedSurfaceFeaturizer {
  def fromData[W](feat: SurfaceFeaturizer[W],
                  data: IndexedSeq[IndexedSeq[W]],
                  constraintFactory: SpanConstraints.Factory[W],
                  cache: Boolean = true): IndexedSurfaceFeaturizer[W]  = {
    val spanIndex = Index[Feature]()
    val wordIndex = Index[Feature]()
    for(words <- data) {
      val cons = constraintFactory.get(words)
      val anch = feat.anchor(words)
      for(i <- 0 until words.length) {
        anch.featuresForWord(i) foreach {wordIndex.index _}
        for(j <- (i+1) to (i + cons.maxSpanLengthStartingAt(i)) if cons(i, j)) {
          anch.featuresForSpan(i,j) foreach {spanIndex.index _}
        }
      }
    }

    val ww = wordIndex
    val ss = spanIndex

    val f = new MySurfaceFeaturizer[W](feat, constraintFactory, ww, ss)
    if(cache) new CachedFeaturizer(f)
    else f
  }

  @SerialVersionUID(1L)
  class CachedFeaturizer[W](val base: IndexedSurfaceFeaturizer[W]) extends IndexedSurfaceFeaturizer[W] with Serializable {
    def spanFeatureIndex: Index[Feature] = base.spanFeatureIndex

    def featurizer: SurfaceFeaturizer[W] = base.featurizer

    def wordFeatureIndex: Index[Feature] = base.wordFeatureIndex


    val cache = new Cache[IndexedSeq[W], IndexedSurfaceAnchoring[W]]
    def anchor(words: IndexedSeq[W]): IndexedSurfaceAnchoring[W] = {
      cache.getOrElseUpdate(words, base.anchor(words))
    }

  }

  @SerialVersionUID(2L)
  private class MySurfaceFeaturizer[W](val featurizer: SurfaceFeaturizer[W],
                                       constraintsFactory: SpanConstraints.Factory[W],
                                       val wordFeatureIndex: Index[Feature],
                                       val spanFeatureIndex: Index[Feature])
                                              extends IndexedSurfaceFeaturizer[W] with Serializable {
    def anchor(words: IndexedSeq[W]):IndexedSurfaceAnchoring[W]  = {
      val cons = constraintsFactory.constraints(words)
      val anch = featurizer.anchor(words)
      val wordFeatures = Array.tabulate(words.length, FeaturizationLevel.numLevels) { (i,l) =>
        stripEncode(wordFeatureIndex, anch.featuresForWord(i, l))
      }
      val spanFeatures = TriangularArray.tabulate(words.length+1){ (i, j) =>
        if(cons(i,j) && i < j) {
         Array.tabulate(FeaturizationLevel.numLevels){l => stripEncode(spanFeatureIndex, anch.featuresForSpan(i, j, l))}
        } else {
          null
        }
      }

      new TabulatedIndexedSurfaceAnchoring[W](words, wordFeatures, spanFeatures)

    }
  }

  def stripEncode(ind: Index[Feature], features: Array[Feature]) = {
    val result = mutable.ArrayBuilder.make[Int]()
    result.sizeHint(features)
    var i = 0
    while(i < features.length) {
      val fi = ind(features(i))
      if(fi >= 0)
        result += fi
      i += 1
    }
    result.result()
  }
}

class TabulatedIndexedSurfaceAnchoring[W](val words: IndexedSeq[W],
                                          wordFeatures: Array[Array[Array[Int]]],
                                          spanFeatures: TriangularArray[Array[Array[Int]]]) extends IndexedSurfaceAnchoring[W] {
  def featuresForSpan(begin: Int, end: Int, level: FeaturizationLevel = FullFeatures):Array[Int] = {
    val f = spanFeatures(begin, end)
    if(f eq null) null
    else f(level.level)
  }

  def featuresForWord(pos: Int, level: FeaturizationLevel): Array[Int] = wordFeatures(pos)(level.level)
}