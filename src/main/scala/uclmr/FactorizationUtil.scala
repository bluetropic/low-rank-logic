package uclmr

import java.io._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Random

/**
 * @author Sebastian Riedel
 */
object FactorizationUtil {

  case class Row(arg1: Any, arg2: Any, relations: Seq[(String, Double)], hidden: Set[String] = Set.empty) {
    def rowName = s"($arg1,$arg2)"
    def observedTrue = relations.filter(_._2 > 0.5).map(_._1)
  }

  def sampleRows(rows: Int, rels: Int, density: Double = 0.1)(implicit random: Random) = {
    for (pair <- 0 until rows) yield {
      val cells = for (rel <- 0 until rels; if random.nextDouble() <= density) yield ("r" + rel, 1.0)
      Row(pair.toString, pair.toString, cells)
    }
  }

  def loadLiminFile(file: File,
                    relationFilter: String => Boolean = _ => true,
                    freebaseLabels: Seq[String] = Seq(), minObsCount: Int = 2, skipUnlabeled: Boolean = false): Iterator[Row] = {
    val source = Source.fromFile(file, "ISO-8859-1")
    for (line <- source.getLines();
         split = line.split("\t");
         arg1 = split(1);
         arg2 = split(2);
         filteredRelations = split.drop(3).filter(relationFilter)
         if filteredRelations.size >= minObsCount && (!skipUnlabeled || split(0) != "UNLABELED")
    ) yield {

      val asSet = filteredRelations.toSet
      //POSITIVE: entity pair in freebase, and one relation was seen
      //NEGATIVE: entity pair in freebase, but no relation was observed, this means that we can
      // more confidently label them negative
      //UNLABELLED: entity pair not in freebase, in some sense
      val cells = split(0) match {
        case "POSITIVE" => filteredRelations.map((_, 1.0)) ++ freebaseLabels.filterNot(asSet).map((_, 0.0))
        case "NEGATIVE" => filteredRelations.map((_, 1.0)) ++ freebaseLabels.map((_, 0.0))
        case "UNLABELED" => filteredRelations.map((_, 1.0))
      }
      Row(arg1, arg2, cells)
    }
  }

  def filterRows(rows: Seq[Row], minRowCount: Int = 10, minColCount: Int = 2, relFilter: String => Boolean = _ => true): Seq[Row] = {
    //rule: every row should have at least minColCount active cells, and each column needs minRowCount.
    val counts = new mutable.HashMap[String, Double]() withDefaultValue 0.0
    for (row <- rows; (rel, value) <- row.relations if relFilter(rel)) counts(rel) += value

    for (row <- rows;
         cells = row.relations.filter(c => counts(c._1) >= minRowCount)
         if cells.size >= minColCount) yield {
      row.copy(relations = cells)
    }
  }

  def filterRowsPairwise(rows: Seq[Row], minPairCount: Int = 3): Seq[Row] = {
    //alternative: each relation should have at least one other relation with minPair
    val counts = new mutable.HashMap[(String, String), Double]() withDefaultValue 0.0
    for (row <- rows;
         (rel1, value1) <- row.relations;
         (rel2, value2) <- row.relations if rel1 != rel2) counts(rel2 -> rel2) += value1 * value2
    val maxCounts = counts.toSeq.groupBy(_._1._1).mapValues(_.view.map(_._2).max)
    for (row <- rows;
         cells = row.relations.filter(c => maxCounts(c._1) >= minPairCount)) yield row.copy(relations = cells)
  }


  case class PredictedFact(row: Row, relation: String, score: Double) {
    override def toString = s"$score\t$relation\t${ row.rowName }\t${ row.observedTrue.mkString(" ") }"
    def toUSchemaString = s"$score\t${ row.arg1 }\t${ row.arg2 }\tREL${ "$NA" }\t$relation"
  }

  def toRankedFacts(predictions: Seq[(Row, Row)]): Seq[PredictedFact] = {
    val facts = for ((obs, guess) <- predictions; (rel, value) <- guess.relations) yield PredictedFact(obs, rel, value)
    val sorted = facts.sortBy(-_.score)
    sorted
  }

  def saveToFile(content: String, file: File): Unit = {
    val out = new PrintStream(file)
    out.println(content)
    out.close()
  }

  def saveToFile[T](content: Iterable[T], file: File): Unit = {
    val out = new PrintWriter(new BufferedWriter(new FileWriter(file)))
    for (line <- content)
      out.println(line.toString)
    out.close()
  }


  def saveForUSchemaEval(facts: Seq[PredictedFact], file: File): Unit = {
    val out = new PrintStream(file)
    for (fact <- facts) {
      out.println(fact.toUSchemaString)
    }
    out.close()
  }

  def renderPredictions(prediction: Seq[Row], truth: Seq[Row] = Seq.empty) = {
    import ml.wolfe.util.ANSIFormatter._
    val relations =
      (prediction.flatMap(_.relations.map(_._1)) ++ truth.flatMap(_.relations.map(_._1))).distinct.sorted
    val colWidth = math.max(relations.map(_.toString.length).max + 1, 5)
    val firstColWidth = prediction.map(_.rowName.length).max + 1

    val colFormat = "%" + colWidth + "s"
    val firstColFormat = "%" + firstColWidth + "s"
    val cellFormat = "%" + (colWidth - 1) + "s "
    val pFormat = "%4.2f"

    val sb = new mutable.StringBuilder()
    sb ++= " " * firstColWidth
    relations.foreach(col => sb ++= colFormat.format(col))
    sb ++= "\n"

    val truthMap = truth.map(r => (r.arg1, r.arg2) -> r).toMap

    for (row <- prediction) {
      val trueRow = truthMap.get((row.arg1, row.arg2))
      sb ++= firstColFormat.format(row.rowName) + " "
      val col2value = row.relations.toMap withDefaultValue 0.0
      val col2trueValue = trueRow.map(_.relations.toMap).getOrElse(Map.empty)
      for (col <- relations) {
        val score = col2value(col)
        val pString = cellFormat.format(pFormat.format(score))
        val actualString = col2trueValue.get(col) match {
          case Some(value) => if (value > 0.5) pString.onGreen() else pString
          case None => pString
        }
        sb ++= actualString
      }
      sb ++= "\n"

    }
    sb.toString()

  }

  def filterRankedFile(dest: String, filterTuple: String, source: String) {
    val allowed = new mutable.HashSet[Seq[Any]]()

    val out = new PrintStream(dest)

    for (line <- Source.fromFile(filterTuple).getLines(); if line.trim != "") {
      val split = line.split("\t")
      val tuple = if (split.size == 2) Seq(split(0), split(1)) else Seq(split(1), split(2))
      allowed += tuple
    }
    println(allowed.size)

    def norm(label: String) = if (label.contains("/") && !label.startsWith("REL$")) "REL$" + label else label

    for (line <- Source.fromFile(source).getLines()) {
      val split = line.split("[\t]")
      if (split(1).contains("|")) {
        val tuple = split(1).split("\\|").toSeq
        if (allowed(tuple)) out.println(split(0) + "\t" + tuple.mkString("\t") + "\t" + split.drop(2).map(norm).mkString("\t"))
      } else {
        val tuple = Seq(split(1), split(2))
        if (allowed(tuple)) out.println(split.take(3).mkString("\t") + "\t" + split.drop(3).map(norm).mkString("\t"))
      }
    }

    out.close()
  }

  def main(args: Array[String]) {
    filterRankedFile(
      "/tmp/ple-subsample.txt",
      "/Users/sriedel/projects/spdb/naacl2013/nyt-freebase.test.subsample-10000.tuples.txt",
      "/tmp/ple.txt"
    )
  }


}
