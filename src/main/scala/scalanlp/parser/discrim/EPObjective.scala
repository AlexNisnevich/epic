package scalanlp.parser
package discrim

import scalala.tensor.dense.DenseVector
import scalanlp.parser.projections._
import scalanlp.optimize._

import scalanlp.trees._
import scalanlp.config.Configuration
import scalanlp.util.{ConsoleLogging, Log}
import splitting.StateSplitting
import scalala.tensor.counters.LogCounters
import scalanlp.parser.UnaryRuleClosure.UnaryClosureException
import InsideOutside._
import java.io._;
import ParseChart.LogProbabilityParseChart;
import scalanlp.concurrent.ParallelOps._;
import scalanlp.concurrent.ThreadLocal;
import scalala.Scalala._;
import scalala.tensor.counters.Counters._
import scalanlp.util._;

/**
 * 
 * @author dlwh
 */
class EPObjective[L,L2,W](featurizers: Seq[Featurizer[L2,W]],
                          unsplitRoot: L, // self explanatory
                          trees: IndexedSeq[(BinarizedTree[L],Seq[W],SpanScorer)],
                          coarseParser: ChartBuilder[LogProbabilityParseChart, L, W],
                          openTags: Set[L],
                          closedWords: Set[W],
                          numModels: Int,
                          splitLabel: L=>Seq[L2],
                          unsplit: (L2=>L),
                          maxEPIterations: Int = 1) extends BatchDiffFunction[Int,DenseVector] {

  val root = {
    val splitRoot = splitLabel(unsplitRoot);
    require(splitRoot.length == 1, "Split of root must be length 1");
    splitRoot.head;
  }

  val indexedFeatures: Seq[FeatureIndexer[L2,W]] = featurizers.map { featurizer =>
    val initGrammar = coarseParser.grammar;
    val initLex = coarseParser.lexicon;
    FeatureIndexer[L,L2,W](featurizer, initGrammar, initLex, splitLabel);
  }

  private val offsets = Array.fill(numModels+1)(0);
  {
    var acc = 0;
    for(m <- 0 to numModels) {
      offsets(m) = acc;
      if(m < numModels)
        acc += indexedFeatures(m).index.size;
    }
  }
  def totalModelSize = offsets.last;

  val indexedProjections = indexedFeatures.map { index =>
    new ProjectionIndexer(coarseParser.grammar.index, index.labelIndex, unsplit)
  };

  val fullRange = (0 until trees.length);

  // TODO: fix
  def extractViterbiParser(weights: DenseVector) = {
    val parsers = Array.tabulate(numModels)(extractLogProbParser(weights,_));
    val epBuilder = new EPParser(parsers,coarseParser, indexedProjections,maxEPIterations);
    epBuilder;
  }

  type LogProbBuilder = CKYChartBuilder[LogProbabilityParseChart,L2,W]

  def extractLogProbParser(weights: DenseVector, model: Int)= {
    val grammar = weightsToGrammar(weights, model);
    val lexicon = weightsToLexicon(weights, model);
    val parser = new LogProbBuilder(root, lexicon, grammar, ParseChart.logProb);
    parser
  }

  var numFailures = 0;
  def calculate(weights: DenseVector, sample: IndexedSeq[Int]) = {
    assert(weights.forall(!_._2.isNaN),"wtf weights!");

    try {
      val parsers = Array.tabulate(numModels)(extractLogProbParser(weights,_));
      val epBuilder = new EPParser(parsers,coarseParser, indexedProjections,maxEPIterations);
      import epBuilder._;
      val myTrees = sample.map(trees);
      val startTime = System.currentTimeMillis();
      def initialExpectedCounts = parsers.map( p => new ExpectedCounts[W](p.grammar))
      val ecounts = myTrees.par(8).fold(initialExpectedCounts) { (counts, treeWordsScorer) =>
        val localIn = System.currentTimeMillis();
        val (tree,words,spanScorer) = treeWordsScorer;
        try {
          val charts = epBuilder.buildAllCharts(words,spanScorer,tree);

          val expectedCounts = for( (p,ParsedSentenceData(inside,outside,outsidePostUnaries,z,f0)) <- parsers zip charts) yield {
            val treeCounts = treeToExpectedCounts(p.grammar,p.lexicon,tree,words, f0)
            val wordCounts = wordsToExpectedCounts(p,words,inside,outside,outsidePostUnaries, z, f0);
            treeCounts -= wordCounts;
          }

          Array.tabulate(numModels)(m => counts(m) += expectedCounts(m));
        } catch {
          case e => println("Error in parsing: " + words + e); e.printStackTrace(); throw new RuntimeException("Error parsing " + words,e);
        }
      } { (ecounts1, ecounts2) =>
        Array.tabulate(numModels)(m => ecounts1(m) += ecounts2(m))
      }
      val finishTime = System.currentTimeMillis() - startTime;

      println("Parsing took: " + finishTime / 1000.0)
      val weightVectors = for { (e,f) <- ecounts zip indexedFeatures} yield expectedCountsToFeatureVector(e,f);
      val grad = -tileWeightVectors( weightVectors) value;

      val logProb = ecounts.map(_.logProb);

      println((norm(grad,2), logProb.mkString("(",",",")")));
      assert(grad.forall(!_._2.isInfinite), "wtf grad");
      (-logProb.last,  grad);
    }  catch {
      case ex: UnaryClosureException =>
        numFailures += 1;
        if(numFailures > 10) throw ex;
        ex.printStackTrace();
        (Double.PositiveInfinity,null);
    }

  }

  val splitOpenTags = {
    for(t <- openTags; s <- splitLabel(t))  yield s;
  }

  def weightsToLexicon(weights: DenseVector, modelIndex: Int) = {
    val grammar = new FeaturizedLexicon(splitOpenTags, closedWords,
      projectWeights(weights,modelIndex),
      indexedFeatures(modelIndex));
    grammar;
  }

  def weightsToGrammar(weights: DenseVector, modelIndex: Int):Grammar[L2] = {
    val myWeights = projectWeights(weights,modelIndex);
    val grammar =  new FeaturizedGrammar(myWeights,indexedFeatures(modelIndex))
    grammar;
  }

  def wordsToExpectedCounts(parser: LogProbBuilder, words: Seq[W],
                            inside: LogProbabilityParseChart[L2],
                            outside: LogProbabilityParseChart[L2],
                            outsidePostUnaries: LogProbabilityParseChart[L2],
                            totalProb: Double,
                            spanScorer: SpanScorer) = {
    val ecounts = new InsideOutside(parser).expectedCounts(words, inside, outside, outsidePostUnaries, totalProb, spanScorer);
    ecounts
  }

  // these expected counts are in normal space, not log space.
  private def treeToExpectedCounts(g: Grammar[L2],
                                   lexicon: Lexicon[L2,W],
                                   t: BinarizedTree[L],
                                   words: Seq[W],
                                   spanScorer: SpanScorer = SpanScorer.identity):ExpectedCounts[W] = {
    StateSplitting.expectedCounts(g,lexicon,t.map(splitLabel),words,spanScorer);
  }

  def expectedCountsToFeatureVector(ecounts: ExpectedCounts[W], indexedFeatures: FeatureIndexer[L2,W]):DenseVector = {
    val result = indexedFeatures.mkDenseVector(0.0);

    // binaries
    for( (a,bvec) <- ecounts.binaryRuleCounts;
         (b,cvec) <- bvec;
         (c,v) <- cvec.activeElements) {
      result += (indexedFeatures.featuresFor(a,b,c) * v);
    }

    // unaries
    for( (a,bvec) <- ecounts.unaryRuleCounts;
         (b,v) <- bvec.activeElements) {
      result += (indexedFeatures.featuresFor(a,b) * v);
    }

    // lex
    for( (a,ctr) <- ecounts.wordCounts;
         (w,v) <- ctr) {
      result += (indexedFeatures.featuresFor(a,w) * v);
    }

    result;
  }

  def projectWeights(weights: DenseVector, modelIndex: Int) = {
    val result = indexedFeatures(modelIndex).mkDenseVector(0.0);
    for(i <- 0 until result.size) {
      result(i) = weights(i + offsets(modelIndex));
    }
    result;
  }

  def partitionWeights(weights: DenseVector) = {
    Array.tabulate(numModels)(m => projectWeights(weights, m));
  }

  def tileWeightVectors(modelWeights: Array[DenseVector]) = {
    // TODO: not require same number of features per model
    val weights = new DenseVector(totalModelSize);
    var i = 0;
    for(w <- modelWeights) {
      var mi = 0;
      while(mi < w.size) {
        weights(i) = w(mi);
        i += 1;
        mi += 1;
      }
    }
    weights;
  }


  def initialWeightVector = {
    val result = new DenseVector(totalModelSize);
    var m = 0;
    for(f <- 0 until result.size) {
      if(f >= offsets(m+1)) {
        m += 1;
      }
      result(f) = indexedFeatures(m).initialValueFor(f-offsets(m));
    }
    result;
  }


}

object EPTrainer extends ParserTrainer {
  def split(x: String, numStates: Int) = {
    if(x.isEmpty) Seq((x,0))
    else for(i <- 0 until numStates) yield (x,i);
  }

  def unsplit(x: (String,Int)) = x._1;

  override def loadTrainSpans(config: Configuration):Iterable[SpanScorer] = {
    val spanDir = config.readIn[File]("spans.labeled",null);
    if(spanDir eq null) super.loadTrainSpans(config);
    else {
      val spanFile = new File(spanDir,ProjectTreebankToLabeledSpans.TRAIN_SPANS_NAME)
      ProjectTreebankToLabeledSpans.loadSpansFile(spanFile);
    }
  }
  override def loadDevSpans(config: Configuration):Iterable[SpanScorer] = {
    val spanDir = config.readIn[File]("spans.labeled",null);
    if(spanDir eq null) super.loadDevSpans(config);
    else {
      val spanFile = new File(spanDir,ProjectTreebankToLabeledSpans.DEV_SPANS_NAME)
      ProjectTreebankToLabeledSpans.loadSpansFile(spanFile);
    }
  }

  override def loadTestSpans(config: Configuration):Iterable[SpanScorer] = {
    val spanDir = config.readIn[File]("spans.labeled",null);
    if(spanDir eq null) super.loadTestSpans(config);
    else {
      val spanFile = new File(spanDir,ProjectTreebankToLabeledSpans.TEST_SPANS_NAME)
      ProjectTreebankToLabeledSpans.loadSpansFile(spanFile)
    }
  }

  def loadCoarseIndex(config: Configuration, index: Index[String]):Index[String] = {
    val spanDir = config.readIn[File]("spans.labeled",null);
    if(spanDir eq null) index
    else {
      val spanFile = new File(spanDir,ProjectTreebankToLabeledSpans.SPAN_INDEX_NAME)
      ProjectTreebankToLabeledSpans.loadSpanIndex(spanFile)
    }
  }

  def loadParser(config: Configuration) = {
    val spanDir = config.readIn[File]("parser.base",null);
    if(spanDir eq null) None
    else {
      Some(ProjectTreebankToLabeledSpans.loadParser(spanDir).builder.withCharts(ParseChart.logProb))
    }
  }

  var obj: EPObjective[String,(String,Int),String] = null;

  def quickEval(devTrees: Seq[(BinarizedTree[String],Seq[String],SpanScorer)], weights: DenseVector, iter: Int, iterPerValidate:Int) {
    if(iter % iterPerValidate == 0) {
      val modelWeights = obj.partitionWeights(weights).zip(obj.indexedFeatures).map { case (w,ind) => (w,ind.decode(w))};
      ProjectTreebankToLabeledSpans.writeObject(modelWeights,new java.io.File("weights-" +iter +".ser"));
      println("Validating...");
      val parser = obj.extractViterbiParser(weights);
      val fixedTrees = devTrees.take(400).toIndexedSeq;
      val results = ParseEval.evaluate(fixedTrees, parser, unaryReplacer);
      println("Validation : " + results)
    }
  }

  def readObject[T](loc: File) = {
    val oin = new ObjectInputStream(new BufferedInputStream(new FileInputStream(loc)));
    val parser = oin.readObject().asInstanceOf[T]
    oin.close();
    parser;
  }

  def getFeaturizer(config: Configuration,
                    initLexicon: PairedDoubleCounter[String, String],
                    initProductions: PairedDoubleCounter[String, Rule[String]],
                    numStates: Int, numModels: Int): IndexedSeq[Featurizer[(String, Int), String]] = {
    val factory = config.readIn[FeaturizerFactory[String, String]]("discrim.featurizerFactory", new PlainFeaturizerFactory[String]);
    val featurizer = factory.getFeaturizer(config, initLexicon, initProductions);
    val latentFactory = config.readIn[LatentFeaturizerFactory]("discrim.latentFactory", new SlavLatentFeaturizerFactory());
    val latentFeaturizer = latentFactory.getFeaturizer(featurizer, numStates);
    val weightsPath = config.readIn[File]("discrim.oldweights",null);
    if(weightsPath == null) {
      Array.fill(numModels)(latentFeaturizer)
    } else {
      println("Using awesome weights...");
      val weightSeq = readObject[Array[(DenseVector,DoubleCounter[Feature[(String,Int),String]])]](weightsPath).map(_._2);
      val splitStates = config.readIn[Boolean]("discrim.splitOldWeights",false);
      def identity(x: Feature[(String,Int),String]) = x;
      val proj: Feature[(String,Int),String]=>Feature[(String,Int),String] = if(splitStates) FeatureProjectors.split[String,String] _ else identity _

      Array.tabulate(numModels){ m =>
        if(m < weightSeq.length)
          new CachedWeightsFeaturizer(latentFeaturizer, weightSeq(m), proj)
        else latentFeaturizer;
      }
    }
  }

  def trainParser(trainTrees: Seq[(BinarizedTree[String],Seq[String],SpanScorer)],
                  devTrees: Seq[(BinarizedTree[String],Seq[String],SpanScorer)],
                  config: Configuration) = {

    val (initLexicon,initProductions) = GenerativeParser.extractCounts(trainTrees.iterator.map(tuple => (tuple._1,tuple._2)));
    val numStates = config.readIn[Int]("discrim.numStates",2);

    val xbarParser = loadParser(config) getOrElse {
      val grammar = new GenerativeGrammar(LogCounters.logNormalizeRows(initProductions));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }

    val maxEPIterations = config.readIn[Int]("ep.iterations",1);
    val epModels = config.readIn[Int]("ep.models",2);

    val latentFeaturizer = getFeaturizer(config, initLexicon, initProductions, numStates, epModels)

    val openTags = Set.empty ++ {
      for(t <- initLexicon.activeKeys.map(_._1) if initLexicon(t).size > 50) yield t;
    }

    val closedWords = Set.empty ++ {
      val wordCounts = DoubleCounter[String]();
      initLexicon.rows.foreach ( wordCounts += _._2 )
      wordCounts.iterator.filter(_._2 > 10).map(_._1);
    }

    obj = new EPObjective(latentFeaturizer, "",
      trainTrees.toIndexedSeq,
      xbarParser,
      openTags,
      closedWords,
      epModels,
      split(_:String,numStates),
      unsplit (_:(String,Int)),
      maxEPIterations);


    val iterationsPerEval = config.readIn("iterations.eval",25);
    val maxIterations = config.readIn("iterations.max",300);
    val batchSize = config.readIn("opt.batchsize",1000);
    val regularization = config.readIn("objective.regularization",0.01) * batchSize / trainTrees.length;
    val alpha = config.readIn("opt.stepsize",20.0);
    val useL1 = config.readIn("opt.useL1",false);
    val opt = if(!useL1) {
      new StochasticGradientDescent[Int,DenseVector](alpha,maxIterations,batchSize)
              with AdaptiveGradientDescent.L2Regularization[Int,DenseVector]
              with ConsoleLogging {
        override val lambda = regularization;
      }
    } else {
      new StochasticGradientDescent[Int,DenseVector](alpha,maxIterations,batchSize)
              with AdaptiveGradientDescent.L1Regularization[Int,DenseVector]
              with ConsoleLogging {
        override val lambda = regularization;
      }
    }

    val init = obj.initialWeightVector;
    val iterPerValidate = config.readIn("iterations.validate",10);

    val log = Log.globalLog;
    for( (state,iter) <- opt.iterations(obj,init).take(maxIterations).zipWithIndex;
         () = quickEval(devTrees,state.x, iter, iterPerValidate)
         if iter != 0 && iter % iterationsPerEval == 0) yield try {
      val parser = obj.extractViterbiParser(state.x)
      ("LatentDiscrim-" + iter.toString,parser)
    } catch {
      case e => println(e);e.printStackTrace(); throw e;
    }


  }
}