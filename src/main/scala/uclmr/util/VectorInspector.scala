package uclmr.util

import cc.factorie.la._
import ml.wolfe.FactorieVector
import uclmr.io.LoadNAACL
import uclmr.{TensorKB, Impl, TensorDB}
import ml.wolfe.util.Conf
import ml.wolfe.util.RichCollections._

import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom

/**
 * @author rockt
 */
object VectorInspector extends App {
  def calculateLengthsAndAngle(v1: FactorieVector, v2: FactorieVector): (Double, Double, Double) = {
    val length1 = math.sqrt(v1 dot v1)
    val length2 = math.sqrt(v2 dot v2)

    val angle = math.acos((v1 dot v2) / (length1 * length2)) * (180 / math.Pi)

    (length1, length2, angle)
  }

  val pathToDB = args.lift(0).getOrElse("./data/out/F-Joint")

  Conf.add(args.lift(1).getOrElse("./conf/mf.conf"))

  implicit val db = new TensorKB(100)
  println("Deserializing DB...")
  db.deserialize(pathToDB + "/serialized/")

  val db2 = LoadNAACL()

  val formulae = db2.formulae
  //val formulae = Seq(Impl("path#appos|->appos->capital->prep->of->pobj->|pobj", "REL$/location/location/containedby"))


  def analyzeLengthsAndAngles() = {
    println("Analyzing vectors...")
    val pathToAnnotatedFormulae = args.lift(1).getOrElse("./data/formulae/1000.txt")
    val pathToAllFormulae = args.lift(2).getOrElse("./data/formulae/10000.txt")

    def printStats(premise: String, consequent: String): (Double, Double, Double) = {
      val premiseVector = db.vector1(premise).get
      val consequentVector = db.vector1(consequent).get

      val (premiseLength, consequentLength, angle) = calculateLengthsAndAngle(premiseVector, consequentVector)

      val correctLength = if (premiseLength < consequentLength) "true" else "false"

      //println("%4.2f°".format(angle) + s"\t$premiseLength\t$consequentLength\t$correctLength")
      println("%4.2f°".format(angle) + s"\t$premiseLength\t$consequentLength")

      (premiseLength, consequentLength, angle)
    }

    val debug = false
    val dropFormulae = 0
    val numSamples = 10

    val pairsOfRelations =
      if (debug) Seq("path#poss|<-poss<-executive->appos->|appos:INV" -> "REL$/business/person/company")
      else {
        val annotatedFormulae = io.Source.fromFile(pathToAnnotatedFormulae).getLines().toList.drop(dropFormulae * 3)
                                .filterNot(l => l.startsWith("//") || l.isEmpty)
                                .map(_.split(" => ")).map(a => (a(0), a(1))).filterNot(_._2.startsWith("!"))

        val allFormulae = io.Source.fromFile(pathToAllFormulae).getLines().toList
                          .filterNot(l => l.startsWith("//") || l.isEmpty)
                          .map(_.split(" => ")).map(a => (a(0), a(1))).filterNot(_._2.startsWith("!"))

        annotatedFormulae.distinctBy(_._2).take(numSamples) ++ allFormulae.take(10000).distinctBy(_._2).takeRight(numSamples)
      }

    println(pairsOfRelations.take(numSamples).mkString("\n"))
    println()

    println(s"Top $numSamples implications")
    val top10Stats = pairsOfRelations.take(numSamples).map(t => printStats(t._1, t._2))
    val top10AvgLengthDiff = top10Stats.map(t => t._2 - t._1).sum / numSamples.toDouble
    val top10AvgAngle = top10Stats.map(_._3).sum / numSamples.toDouble
    println("Average length difference: " + top10AvgLengthDiff)
    println("Average angle: " + top10AvgAngle)
    println()
    println(s"Bottom $numSamples implications")
    val least10Stats = pairsOfRelations.takeRight(numSamples).map(t => printStats(t._1, t._2))
    val least10AvgLengthDiff = least10Stats.map(t => t._2 - t._1).sum / numSamples.toDouble
    val least10AvgAngle = least10Stats.map(_._3).sum / numSamples.toDouble
    println("Average length difference: " + least10AvgLengthDiff)
    println("Average angle: " + least10AvgAngle)
  }

  val entityPairs = db.trainCells.map(_.key2).distinct.map(List(_))

  def analyzeAsymmetry() = {
    val tmp = formulae.map {
      case Impl(p1, p2, _) =>
        val p1Vector = db.vector1(p1).get
        val p2Vector = db.vector1(p2).get
        val (p1Length, p2Length, angle) = VectorInspector.calculateLengthsAndAngle(p1Vector, p2Vector)
        val lengthDiff = p2Length - p1Length
        //val (mfScore, numPremises) = FormulaeExtractor.formulaScoreMF(Impl(p1, p2), entityPairs)
        //val (mfScoreInv, numPremisesInv) = FormulaeExtractor.formulaScoreMF(Impl(p2, p1), entityPairs)
        val (mfScore, numPremises) = FormulaeExtractor.formulaScoreMFPredicted(Impl(p1, p2), entityPairs)
        val (mfScoreInv, numPremisesInv) = FormulaeExtractor.formulaScoreMFPredicted(Impl(p2, p1), entityPairs)

        //println(p1 + "\t" + p1Vector.toArray.mkString("\t"))
        //println(p2 + "\t" + p2Vector.toArray.mkString("\t"))

        val maxPremise = ArgMaxSigmoid(p1Vector)
        val maxConsequent = ArgMaxSigmoid(p2Vector)

        /*
        println("permise(p-premise):\t" + ArgMaxSigmoid.sig(maxPremise dot p1Vector))
        println("consequent(p-premise):\t" + ArgMaxSigmoid.sig(maxPremise dot p2Vector))
        println("premise(p-consequent):\t" + ArgMaxSigmoid.sig(maxConsequent dot p1Vector))
        println("consequent(p-consequent):\t" + ArgMaxSigmoid.sig(maxConsequent dot p2Vector))
        */

        //println(f"%%4.2f [%%d]\t%%4.2f [%%d]\t%%6.4f\t%%6.4f\t%%6.4f\t%%4.2f°\t$p1\t$p2".format(
        println(f"%%4.2f [%%d]\t%%4.2f [%%d]\t%%6.4f\t%%6.4f\t%%6.4f\t%%4.2f°\t${ArgMaxSigmoid.sig(maxPremise dot p2Vector)}\t${ArgMaxSigmoid.sig(maxConsequent dot p1Vector)}\t$p1\t$p2".format(
          mfScore, numPremises, mfScoreInv, numPremisesInv, p1Length, p2Length, lengthDiff, angle
        ))

        (mfScore, mfScoreInv, angle, lengthDiff)
    }


    println("Avg A=>B score:\t" + (tmp.map(_._1).sum / tmp.length.toDouble))
    println("Avg B=>A score:\t" + (tmp.map(_._2).sum / tmp.length.toDouble))
    println("Avg angle:\t" + (tmp.map(_._3).sum / tmp.length.toDouble))
    println("Avg length diff:\t" + (tmp.map(_._4).sum / tmp.length.toDouble))
  }

  //analyzeLengthsAndAngles()
  analyzeAsymmetry()
}

object VectorInspectorSpec extends App {
  println(VectorInspector.calculateLengthsAndAngle(new DenseTensor1(Array(3.0, 0.0)), new DenseTensor1(Array(5.0, 5.0)))._3)
  println(VectorInspector.calculateLengthsAndAngle(new DenseTensor1(Array(3.0, 4.0)), new DenseTensor1(Array(-8.0, 6.0)))._3)
  println(VectorInspector.calculateLengthsAndAngle(new DenseTensor1(Array(5.0, 6.0)), new DenseTensor1(Array(-1.0, 4.0)))._3)
  println(VectorInspector.calculateLengthsAndAngle(new DenseTensor1(Array(3.0, 5.0)), new DenseTensor1(Array(-1.0, 6.0)))._3)
}