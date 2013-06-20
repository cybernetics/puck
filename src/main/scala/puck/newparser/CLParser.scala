package puck
package newparser

import breeze.linalg._
import breeze.config._
import breeze.collection.mutable.TriangularArray
import com.nativelibs4java.opencl._
import com.typesafe.scalalogging.log4j._
import epic.lexicon.{TagScorer, Lexicon}
import epic.parser._
import epic.parser.projections.GrammarRefinements
import epic.trees._
import epic.trees.annotations._
import java.io._
import java.nio.FloatBuffer
import java.{lang=>jl}
import puck.linalg.CLMatrix
import puck.parser.gen.SemiringFloatOpsExp
import puck.util._
import scala.virtualization.lms.common.ArrayOpsExp
import trochee.kernels.KernelOpsExp
import puck.newparser.generator._
import collection.mutable.ArrayBuffer
import java.util

/**
 * TODO
 *
 * @author dlwh
 **/
class CLParser[C, L, W](grammar: SimpleRefinedGrammar[C, L, W],
                        maxAllocSize: Long = 1<<30,
                        profile: Boolean = true)(implicit val context: CLContext) extends Logging {

  /*
  def parse(sentences: IndexedSeq[IndexedSeq[W]]):IndexedSeq[BinarizedTree[C]] = synchronized {
    {for {
      batch <- getBatches(sentences, masks).iterator
      //    _ = getMarginals(batch)
      t <- doParse(batch)
    } yield {
      t
    }}.toIndexedSeq
  }
  */


  def partitions(sentences: IndexedSeq[IndexedSeq[W]]):IndexedSeq[Float] = synchronized {
    {for {
      batch <- getBatches(sentences).iterator
      _ = inside(batch).foreach(_.waitFor())
      i <- 0 until batch.numSentences
    } yield {
      batch.gpuCharts(i).top(0,batch.gpuCharts(i).length, structure.root)
    }}.toIndexedSeq
  }

  val structure = RuleStructure[C, L](grammar.refinements, grammar.refinedGrammar)
  println(structure.nontermIndex.zipWithIndex)
  println(structure.termIndex.zipWithIndex)

  private implicit val queue = if(profile) context.createDefaultProfilingQueue() else context.createDefaultOutOfOrderQueueIfPossible()
  private val hdTransferEvents  = new CLProfiler("Host2Dev Transfer")
  private val transferEvents  = new CLProfiler("Transfer")
  private val binaryEvents  = new CLProfiler("Binary")
  private val unaryEvents  = new CLProfiler("Unary")
  private val sumToChartsEvents  = new CLProfiler("SumToCharts")
  private val sumEvents  = new CLProfiler("Sum")
  val allProfilers =  IndexedSeq(transferEvents, binaryEvents, unaryEvents, sumToChartsEvents, sumEvents)


  val nrules = grammar.index.size
  // TODO: reinstate this difference if numTerms is really big.
  val cellSize = structure.numNonTerms max structure.numTerms

  val gen = new CLParserKernelGenerator[C, L](structure)
  import gen.insideGen

  val ruleScores = Array.tabulate(grammar.refinedGrammar.index.size){r =>
    val score = grammar.ruleScoreArray(grammar.refinements.rules.project(r))(grammar.refinements.rules.localize(r))
    gen.IR.fromLogSpace(score.toFloat)
  }

  val (numGPUCells:Int, numGPUChartCells: Int) = {
    val sizeOfFloat = 4
    val fractionOfMemoryToUse = 0.8 // slack!
    val amountOfMemory = ((context.getMaxMemAllocSize min maxAllocSize) * fractionOfMemoryToUse).toInt - ruleScores.length * 4
    val maxPossibleNumberOfCells = (amountOfMemory/sizeOfFloat) / cellSize
    // We want numGPUCells and numGPUAccCells to be divisible by 16, so that we get aligned strided access:
    //       On devices of compute capability 1.0 or 1.1, the k-th thread in a half warp must access the
    //       k-th word in a segment aligned to 16 times the size of the elements being accessed; however,
    //       not all threads need to participate... If sequential threads in a half warp access memory that is
    //       sequential but not aligned with the segments, then a separate transaction results for each element
    //       requested on a device with compute capability 1.1 or lower.
    val numberOfUnitsOf16 = maxPossibleNumberOfCells / 16
    // average sentence length of sentence, let's say n.
    // for the gpu charts, we'll need (n choose 2) * 2 = n^2 - n cells
    // for the "P/L/R" parts, the maximum number of relaxations (P = L * R * rules) for a fixed span
    // in a fixed sentence is (n/2)^2= n^2/4.
    // Take n = 32, then we want our P/L/R arrays to be of the ratio (3 * 256):992 \approx 3/4 (3/4 exaclty if we exclude n)
    //
    val baseSize = numberOfUnitsOf16 / 7
    val extra = numberOfUnitsOf16 % 7
    val plrSize = baseSize
    // TODO, can probably do a better job of these calculations?
    (plrSize * 16, (baseSize * 4 + extra) * 16)
  }


  // On the Device side we have 4 Matrices:
  // One is where we calculate P = L * R * rules, for fixed spans and split points (the "bot")
  // One is the L part of the above
  // Another is the R part.
  // finally, we have the array of parse charts, which is insanely large. It's also where
  // we do rescaling, etc.
  private val devParent, devLeft, devRight = new CLMatrix[Float](numGPUCells, cellSize)
  private val devCharts = new CLMatrix[Float](numGPUChartCells, cellSize)

  // also the rules
  private val ruleDev = context.createFloatBuffer(CLMem.Usage.Input, FloatBuffer.wrap(ruleScores), false)

  def _zero: Float = gen.IR._zero

  // other stuff
  private val zmk = new ZeroMemoryKernel()

  private case class Batch(lengthTotals: Array[Int],
                           cellTotals: Array[Int],
                           sentences: IndexedSeq[IndexedSeq[W]]) {
    def totalLength = lengthTotals.last
    def numSentences = sentences.length
    val maxLength = sentences.map(_.length).max


    val _workArrayOffsetsForSpan = Array.tabulate(maxLength+1)(span => sentences.scanLeft(0)((off, sent) => off + math.max(0,sent.length-span+1)))
    def workArrayOffsetsForSpan(sent: Int, span: Int) = Range(_workArrayOffsetsForSpan(span)(sent), _workArrayOffsetsForSpan(span)(sent+1)) 

    def totalLengthForSpan(span: Int) = _workArrayOffsetsForSpan(span).last

    lazy val gpuCharts = for(i <- 0 until numSentences) yield {
      val numCells = (cellTotals(i+1)-cellTotals(i))/2
      assert(numCells == TriangularArray.arraySize(sentences(i).length+1))
      val chart = new ParseChart(sentences(i).length, devCharts(cellTotals(i) until (cellTotals(i) + numCells),::), devCharts(cellTotals(i) + numCells until cellTotals(i+1), ::))
      chart
    }

    def initializeTagScores() = {
      for(i <- 0 until numSentences) yield {
        gpuCharts(i).bot.spanSlice(1).writeFrom(tagScoresFor(sentences(i)), blocking=false)
      }
    }.flatten

    def tagScoresFor(sent: IndexedSeq[W]) = {
      val anch = grammar.tagScorer.anchor(sent)
      val lexAnch = grammar.lexicon.anchor(sent)
      val tags = new DenseMatrix[Float](sent.length, cellSize)
      tags := _zero
      for(pos <- 0 until sent.length; t <- lexAnch.allowedTags(pos); ref <- grammar.refinements.labels.refinementsOf(t)) {
        val index = ref
        val score = anch.scoreTag(pos, grammar.refinedGrammar.labelIndex.get(index))
        val gpuIndex = structure.labelIndexToTerminal(index)
        tags(pos, gpuIndex) = gen.IR.fromLogSpace(score.toFloat)
      }
      tags

    }
  }

  private def getBatches(sentences: IndexedSeq[IndexedSeq[W]]): IndexedSeq[Batch] = {
    val result = ArrayBuffer[Batch]()
    var current = ArrayBuffer[IndexedSeq[W]]()
    var currentLengthTotal = 0
    var currentCellTotal = 0
    for( (s, i) <- sentences.zipWithIndex) {
      currentLengthTotal += s.length
      currentCellTotal += TriangularArray.arraySize(s.length+1) * 2
      if(currentLengthTotal > numGPUCells || currentCellTotal > numGPUChartCells) {
        assert(current.nonEmpty)
        result += createBatch(current)
        currentLengthTotal = s.length
        currentCellTotal = TriangularArray.arraySize(s.length+1) * 2
        current = ArrayBuffer()
      }
      current += s
    }

    if(current.nonEmpty) result += createBatch(current)
    result
  }

  private def createBatch(sentences: IndexedSeq[IndexedSeq[W]]): Batch = {
    val lengthTotals = sentences.scanLeft(0)((acc, sent) => acc + sent.length)
    val cellTotals = sentences.scanLeft(0)((acc, sent) => acc + TriangularArray.arraySize(sent.length+1) * 2)
    Batch(lengthTotals.toArray, cellTotals.toArray, sentences)
  }


  private def inside(batch: Batch):Seq[CLEvent] = synchronized {
    hdTransferEvents.clear()
    hdTransferEvents.tick()

    devCharts := _zero
    val init = batch.initializeTagScores()
    hdTransferEvents ++= init
    CLEvent.invokeUponCompletion(new Runnable {
      def run() {
        hdTransferEvents.tock()
        println("Inside " + hdTransferEvents)
      }
    }, init:_*)
    var eZp = zmk.fillMemory(devParent.data, _zero)
    allProfilers.foreach(_.clear())
    allProfilers.foreach(_.tick())


    var events:Seq[CLEvent] = doUnaryUpdates(batch, 1, eZp +: init :_*)
    events = sumBackToCharts(batch, _.top, 1, events :_*)
    events = IndexedSeq(zmk.fillMemory(devParent.data, _zero, events:_*))

    events = doTTUpdates(batch, events:_*)
    events = sumBackToCharts(batch, _.bot, 2, events :_*)
    events = IndexedSeq(zmk.fillMemory(devParent.data, _zero, events:_*))

    for(span <- 2 to batch.maxLength) {
      println(span)
      // TODO: there's got to be a better way. implicits?
      events = Seq[Seq[CLEvent]=>Seq[CLEvent]](
        doNTUpdates(batch, span, _ :_*),
        doTNUpdates(batch, span, _ :_*),
        doNNUpdates(batch, span, batch.maxLength, _ :_*),
        sumBackToCharts(batch, _.bot, span, _ :_*),
        {(x: Seq[CLEvent]) => Seq(zmk.fillMemory(devParent.data, _zero, x:_*))},
        doUnaryUpdates(batch, span, _ : _*),
        sumBackToCharts(batch, _.top, span, _ :_*),
        {(x: Seq[CLEvent]) => Seq(zmk.fillMemory(devParent.data, _zero, x:_*))}
      ).foldLeft(events)((a,b) => b apply a)
    }
    queue.finish()
    allProfilers.foreach(_.tock())
    allProfilers.foreach(p => println(s"Inside $p"))

    events
  }

  private def doBinaryRules(batch: Batch,
    kernels: IndexedSeq[CLKernel],
    span: Int,
    splitRange: Range,
    leftChart: ParseChart=>ChartHalf,
    rightChart: ParseChart=>ChartHalf,
    events: CLEvent*) = {
    var ev = events

    var offset = 0 // number of cells used so far.

    val usedPerSplit = batch.totalLengthForSpan(span)
    var maxOffset = 0 // maximum number of cells used in one kernel execution.
                      // will be a multiple of usedPerSplit
    assert(splitRange.last < span, splitRange + " " + span)

    for(leftChildLength <- splitRange) {
      println(span,leftChildLength)
      // if we fill up the buffer, run a pass.
      if(offset + usedPerSplit >= numGPUCells)  {
        assert(offset != 0)
        ev = kernels.map{ kernel =>
          kernel.setArgs(devParent, devLeft, devRight, ruleDev, Integer.valueOf(numGPUCells), Integer.valueOf(offset))
          kernel.enqueueNDRange(queue, Array(offset), ev:_*)
        } 
        binaryEvents ++= ev
        maxOffset = maxOffset max offset
        offset = 0
      }
      // add next split point
      val evWrite = new ArrayBuffer[CLEvent]()
      evWrite.sizeHint(batch.numSentences * 2)
      for(sent <- 0 until batch.numSentences) {
        if(span <= batch.sentences(sent).length) {
          val lslice = leftChart(batch.gpuCharts(sent)).spanSlice(leftChildLength, 0, batch.gpuCharts(sent).length-span+1)
          val rslice = rightChart(batch.gpuCharts(sent)).spanSlice(span-leftChildLength, leftChildLength)

          val offsets = batch.workArrayOffsetsForSpan(sent, span) 
          assert(lslice.rows == rslice.rows, lslice.rows + " " + rslice.rows + " " + offsets.length)
          val mappedRange = Range(offsets.start + offset, offsets.end + offset)

          val wl = devLeft(mappedRange, ::).writeFrom(lslice,false,ev:_*)
          val wr = devRight(mappedRange, ::).writeFrom(rslice,false,ev:_*)
          evWrite += wl
          evWrite += wr
        }
      }
      transferEvents ++= evWrite

      ev = evWrite
      offset += usedPerSplit
    }



    if(offset > 0) {
      maxOffset = maxOffset max offset
      ev = kernels.map{ kernel =>
        kernel.setArgs(devParent.data, devLeft.data, devRight.data, ruleDev, Integer.valueOf(numGPUCells), Integer.valueOf(offset))
        kernel.enqueueNDRange(queue, Array(offset), ev:_*)
      }
      binaryEvents ++= ev

    } 

    if(maxOffset > usedPerSplit)
      ev = sumEvents.adding(sumSplitBlocks(usedPerSplit, maxOffset, ev:_*))

    ev
  }

  def doTTUpdates(batch: Batch, events: CLEvent*) = {
    // layout:
    // Parents for each span of length 2 == n -1
    // [Sent0Span2Pos0, Sent0Span2Pos1, ..., Sent0Span2PosN-2, Sent1Span2Pos0, ...]
    // [Sent0Term0    , Sent0Term1    , ..., Sent0TermN-1    , Sent1Term0    , ...]
    // [Sent0Term1    , Sent0Term2    , ..., Sent0TermN      , Sent1Term1    , ...]
    doBinaryRules(batch, insideGen.insideTTKernels, 2, 1 to 1, _.bot, _.bot, events:_*)
  }


  def doTNUpdates(batch: Batch, span: Int, events: CLEvent*) = {
    doBinaryRules(batch, insideGen.insideTNKernels, span, 1 to 1, _.bot, _.top, events:_*)
  }

  def doNTUpdates(batch: Batch, span: Int, events: CLEvent*) = {
    doBinaryRules(batch, insideGen.insideNTKernels, span, (span-1) to (span-1), _.top, _.bot, events:_*)
  }

  def doUnaryUpdates(batch: Batch, span: Int, events: CLEvent*): IndexedSeq[CLEvent] = {
    import batch._
    val writeEvents = for(sent <- 0 until batch.numSentences if batch.sentences(sent).length >= span) yield {
      val lslice = batch.gpuCharts(sent).bot.spanSlice(span)
      devLeft(workArrayOffsetsForSpan(sent, span), ::).writeFrom(lslice, false, events: _*)
    }
    transferEvents ++= writeEvents


    val kernels = if(span == 1) insideGen.insideTUKernels else insideGen.insideNUKernels
    val endEvents = kernels.map{(kernel) =>
      kernel.setArgs(devParent.data, devLeft.data, ruleDev, Integer.valueOf(numGPUCells), Integer.valueOf(numGPUCells), Integer.valueOf(totalLengthForSpan(span)))
      kernel.enqueueNDRange(queue, Array(totalLengthForSpan(span)), writeEvents:_*)
    }
    unaryEvents ++= endEvents
    endEvents
  }

  def doNNUpdates(batch: Batch, span: Int, maxLength: Int, events: CLEvent*) = {
    doBinaryRules(batch, insideGen.insideNNKernels, span, (1 to span-1), _.top, _.top, events:_*)
  }

  private def sumSplitBlocks(targetSize: Int, currentSize: Int, events: CLEvent*):IndexedSeq[CLEvent] = {
    assert(currentSize % targetSize == 0)
    val multiple: Int = currentSize / targetSize
    val log2 = BitHacks.log2(multiple)
    if(log2 == 0) return events.toIndexedSeq

    var currentMultiple = 1 << log2

    var ev:IndexedSeq[CLEvent] = events.toIndexedSeq
    if(currentMultiple != multiple) {
      val difference = multiple - currentMultiple
      ev = IndexedSeq(sumGrammarCells(devParent(0 until difference * targetSize, ::), devParent(currentSize - difference*targetSize until currentSize, ::), ev:_*))
    }

    while (currentMultiple > 1) {
      currentMultiple /= 2
      ev = IndexedSeq(sumGrammarCells(devParent(0 until currentMultiple * targetSize, ::), devParent(currentMultiple * targetSize until 2 * currentMultiple * targetSize, ::), ev:_*))
    }
    assert(currentMultiple == 1, currentMultiple + " " + targetSize + " " + currentSize)
    
    ev
  }

  def sumGrammarCells(dest: CLMatrix[Float], src: CLMatrix[Float], events: CLEvent*) = {
    assert(dest.rows == src.rows)
    assert(dest.size == src.size)
    insideGen.sumGrammarCellsKernel.setArgs(dest.data, Integer.valueOf(dest.offset), Integer.valueOf(dest.majorStride),
                                             src.data, Integer.valueOf(src.offset),  Integer.valueOf(src.majorStride),
                                             Integer.valueOf(dest.cols), Integer.valueOf(dest.size))
    insideGen.sumGrammarCellsKernel.enqueueNDRange(queue, Array(dest.rows, dest.cols), /*Array(32, 1),*/ events:_*)
  }

  private def sumBackToCharts(batch: Batch, level: ParseChart=>ChartHalf, span: Int, events: CLEvent*) = {
    val evs = for(sent <- 0 until batch.numSentences if batch.sentences(sent).length >= span) yield {
      val offsets = batch.workArrayOffsetsForSpan(sent, span)
      val lslice = level(batch.gpuCharts(sent)).spanSlice(span)
      val ev = sumGrammarCells(lslice, devParent(offsets, ::), events:_*)
      //queue.finish()
      ev
    }
    sumToChartsEvents ++= evs
    evs
  }
}

object CLParser extends Logging {

  case class Params(annotator: TreeAnnotator[AnnotatedLabel, String, AnnotatedLabel] = FilterAnnotations(),
                    useGPU: Boolean = true, numToParse: Int = 1000)

  def main(args: Array[String]) = {
    import ParserParams.JointParams

    val params = CommandLineParser.readIn[JointParams[Params]](args)
    import params.trainer._
    println("Training Parser...")
    println(params)
    val transformed = params.treebank.trainTrees.par.map { ti => annotator(ti) }.seq.toIndexedSeq
    val grammar: SimpleRefinedGrammar[AnnotatedLabel, AnnotatedLabel, String] = GenerativeParser.extractGrammar(AnnotatedLabel.TOP, transformed)


    implicit val context = if(useGPU) {
      val gpu = JavaCL.listPlatforms.flatMap(_.listGPUDevices(true)).head
      JavaCL.createContext(new java.util.HashMap(), gpu)
    } else {
      val cpuPlatform:CLPlatform = JavaCL.listPlatforms().filter(_.listCPUDevices(true).nonEmpty).head
      cpuPlatform.createContext(new java.util.HashMap(), cpuPlatform.listCPUDevices(true):_*)
    }
    println(context)

    val kern = fromSimpleGrammar[AnnotatedLabel, AnnotatedLabel, String](grammar)
    val train = transformed.slice(0,numToParse).map(_.words)
   //val train = IndexedSeq(IndexedSeq("Ms.","Haag"))
    

    println(kern.partitions(train))
    val margs = train.map(w => ChartMarginal(AugmentedGrammar.fromRefined(grammar), w))
    for(i <- 0 until margs.length) {
      for(span <- 1 to margs(i).length; begin <- 0 until (margs(i).length-span+1)) {
      //  println((begin,begin+span) + " TOP " + margs(i).inside.top.decodedLabelScores(begin,begin+span))
      //  println((begin,begin+span) + " BOT " + margs(i).inside.bot.decodedLabelScores(begin,begin+span))
      }
    }

    println(margs.map(_.logPartition))
  }

  def fromSimpleGrammar[L, L2, W](grammar: SimpleRefinedGrammar[L, L2, W])(implicit context: CLContext) = {
    val kern = new CLParser(grammar)
    kern
  }
}
