package updown.preproc

import org.clapper.argot.{ArgotUsageException, ArgotParser, ArgotConverters}
import ArgotConverters._
import updown.data.SentimentLabel
import updown.util.{TokenizationPipes, Twokenize}
import java.util.Collections
import com.weiglewilczek.slf4s.Logging

abstract class GenericPreprocessor extends Logging {
  // this is here to make ArgotConverters appear used to IDEA.
  convertString _

  def getInstanceIterator(fileName: String, polarity: String): Iterator[(String, String, SentimentLabel.Type, String)]

  def getInputIterator(inputOption: Option[String]): Iterator[(String, String, SentimentLabel.Type, String)] = {
    logger.debug("entering getInputIterator")
    inputOption match {
      case Some(fileNameList) =>
        (for ((name, polarity) <- fileNameList.split("\\s*,\\s*").map((pair) => {
          val plist = pair.split("\\s*->\\s*")
          (plist(0) -> plist(1))
        }
        ).toMap) yield {
          getInstanceIterator(name, polarity)
        }).iterator.flatten

      case None =>
        (for (line <- scala.io.Source.stdin.getLines()) yield {
          line.split("|") match {
            case Array(id, reviewer, polarityString, text) =>
              (id, reviewer, SentimentLabel.figureItOut(polarityString), text)
            case _ =>
              logger.error("Input must be of the form id|reviewer|polarity|text.")
              ("", "", SentimentLabel.Neutral, "")
          }
        })
    }
  }

  def main(args: Array[String]) {
    logger.debug(args.toList.toString)
    // don't forget that this is linked to the pipeStages dict below
    val availablePipes = Set("lowerCase", "addBiGrams", "twokenize", "twokenizeSkipGtOneGrams", "removeStopwords", "splitSpace", "filterAlpha", "filterAlphaQuote")

    // PARSE ARGS
    val parser = new ArgotParser("updown run updown.preproc.PreprocStanfordTweets", preUsage = Some("Updown"))
    val inputFile = parser.option[String](List("i", "input"), "input", "path to stanford data file")
    val stopListFile = parser.option[String](List("s", "stoplist"), "stoplist", "path to stoplist file")
    val startId = parser.option[Int](List("start-id"), "ID", "id at which to start numbering lines")
    val textPipeline = parser.option[String](List("textPipeline"), "PIPELINE",
      ("specify the desired pipe stages seperated by |: \"addBiGrams|twokenize\". " +
        "Available options are in %s.").format(availablePipes))
    try {
      parser.parse(args)

      // SET UP IO
      var lineCount =
        startId.value match {
          case Some(id) => id
          case None => 0
        }

      logger.debug("Inputfile: %s".format(inputFile.value))
      val inputLines: Iterator[(String, String, SentimentLabel.Type, String)] =
        getInputIterator(inputFile.value)


      val stopSet: Set[String] =
        stopListFile.value match {
          case Some(fileName) =>
            scala.io.Source.fromFile(fileName).getLines.toSet
          case None => Set("a", "the", ".")
        }


      val pipeStages: Map[String, (List[String]) => List[String]] =
        Map[String, (List[String]) => List[String]](
          ("lowerCase" -> TokenizationPipes.toLowercase),
          ("addBiGrams" -> TokenizationPipes.addNGrams(2)),
          ("twokenize" -> TokenizationPipes.twokenize),
          ("twokenizeSkipGtOneGrams" -> TokenizationPipes.twokenizeSkipGtOneGrams),
          ("removeStopwords" -> TokenizationPipes.filterOnStopset(stopSet)),
          ("filterAlpha") -> TokenizationPipes.filterOnRegex("\\p{Alpha}+"),
          ("filterAlphaQuote") -> TokenizationPipes.filterOnRegex("(\\p{Alpha}|')+"),
          ("splitSpace" -> TokenizationPipes.splitOnDelimiter(" "))
        )
      // had to predefine the available pipes so they could be printed in the usage string, before the stopset can be parsed.
      assert(pipeStages.keySet == availablePipes)

      logger.debug("Pipeline option: %s".format(textPipeline.value))
      val pipeline: List[(List[String]) => List[String]] =
        if (textPipeline.value.isDefined) {
          val arg: String = textPipeline.value.get
          (for (pipeStage <- arg.split("\\|")) yield {
            if (pipeStages.keySet.contains(pipeStage)) {
              pipeStages(pipeStage)
            } else {
              parser.usage("invalid pipeStage: %s".format(pipeStage))
            }
          }).toList
        } else {
          List(pipeStages("twokenize"), pipeStages("removeStopwords"))
        }
      logger.debug("Pipeline: %s".format(pipeline))


      // RUN
      for ((id, reviewer, polarity, text) <- inputLines) {
        println(
          "%s|%s|%s|%s".format(
            if (id == "") lineCount else id,
            reviewer,
            runThroughPipeLine(text.replaceAll(",", ""), pipeline).mkString(","),
            polarity))
        lineCount += 1
      }
      logger.debug("Done!")
    }
    catch {
      case e: ArgotUsageException =>
        println(e.message)
        System.exit(1)
    }
  }

  def runThroughPipeLine(text: String, pipeLine: List[(List[String]) => List[String]]): List[String] = {
    var res = List(text)
    for (pipeStage <- pipeLine) {
      res = pipeStage(res)
    }
    res
  }
}