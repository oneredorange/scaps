package scaps.evaluation

import com.nicta.rng.Rng
import scaps.settings.Settings
import scaps.settings.QuerySettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.io.FileWriter
import scaps.utils.using
import scaps.evaluation.stats.Stats

object Evaluation extends App {
  import QuerySettings._
  import RngExtensions._

  val outputDir = "evaluation/target/results"

  val evaluationSettings = EvaluationSettings.fromApplicationConf

  val baseSettings = Settings.fromApplicationConf
    .modQuery(_.copy(
      maxResults = 100,
      views = false,
      fingerprintFrequencyCutoff = 0.8))

  val baseRngs = Map[String, Rng[Double]](
    depthBoostWeight -> Rng.oneof(0d))
    .withDefaultValue(Rng.oneof(0d))

  // (name, number of configurations tested, configuration generator)
  val runs: List[(String, Int, Rng[Settings])] = List(
    ("I0: Baseline", 1, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = false))
        .modQuery(_.copy(
          views = false,
          termSpecificity = false)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.oneof(0d),
        distanceBoostWeight -> Rng.oneof(1d), // enable distance to get a non-zero weights sum
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(0d)))),
    ("I1: Penalized", 20, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = false))
        .modQuery(_.copy(
          views = false,
          termSpecificity = false)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.choosedouble(0, 0.5),
        distanceBoostWeight -> Rng.oneof(1d), // enable distance to get a non-zero weights sum
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(0d)))),
    ("I2: ITF", 20, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = false))
        .modQuery(_.copy(
          views = false,
          termSpecificity = false)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.choosedouble(0, 0.5),
        distanceBoostWeight -> Rng.oneof(0d),
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(1d)))),
    ("I3: Polarized", 20, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = true))
        .modQuery(_.copy(
          views = false,
          termSpecificity = false)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.choosedouble(0, 0.5),
        distanceBoostWeight -> Rng.oneof(1d), // enable distance to get a non-zero weights sum
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(0d)))),
    ("I4: Specifities", 20, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = false))
        .modQuery(_.copy(
          views = false,
          termSpecificity = true)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.choosedouble(0, 0.5),
        distanceBoostWeight -> Rng.oneof(1d), // enable distance to get a non-zero weights sum
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(0d)))),
    ("I5: FEM without Views", 20, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = true))
        .modQuery(_.copy(
          views = false,
          termSpecificity = true)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.choosedouble(0, 0.5),
        distanceBoostWeight -> Rng.oneof(0d),
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(1d)))),
    ("I6: FEM", 100, randomize(
      baseSettings
        .modIndex(_.copy(
          polarizedTypes = true))
        .modQuery(_.copy(
          views = true,
          termSpecificity = true)),
      baseRngs ++ Map(
        penaltyWeight -> Rng.choosedouble(0, 0.5),
        distanceBoostWeight -> Rng.choosedouble(0, 2),
        docBoost -> Rng.oneof(0.4d),
        typeFrequencyWeight -> Rng.oneof(1d)))))

  var engine = Common.initSearchEngine(baseSettings, evaluationSettings)

  using(new FileWriter(outputFile)) { writer =>
    val headers = List(
      "rid",
      "run",
      "polarized-types",
      QuerySettings.views,
      QuerySettings.termSpecificity,
      QuerySettings.penaltyWeight,
      QuerySettings.distanceBoostWeight,
      QuerySettings.depthBoostWeight,
      QuerySettings.typeFrequencyWeight,
      QuerySettings.docBoost,
      QuerySettings.fingerprintFrequencyCutoff,
      "MAP",
      "R@10",
      "t")

    println(headers.mkString("", "; ", ";\n"))
    writer.write(headers.mkString("", "; ", ";\n"))

    val queryHeaders = List("rid", "run", "qid", "query", "AP", "R@10")
    using(new FileWriter(queryDetailsOutputFile)) { w => w.write(queryHeaders.mkString("", "; ", ";\n")) }

    runs.zipWithIndex.foreach {
      case ((runName, noConfigurations, settingsGenerator), ridx) =>
        println(s"start '$runName'")
        val allStats = settingsGenerator.fill(noConfigurations).runUnsafe(Math.pow(42, 42).toLong).map { settings =>
          engine = Common.updateSearchEngine(engine, settings)
          Common.runQueries(engine, evaluationSettings.queries).fold(
            errors => {
              println(errors)
              ???
            },
            stats => {
              val cells = List[Any](
                ridx,
                runName,
                settings.index.polarizedTypes,
                settings.query.views,
                settings.query.termSpecificity,
                settings.query.penaltyWeight,
                settings.query.distanceBoostWeight,
                settings.query.depthBoostWeight,
                settings.query.typeFrequencyWeight,
                settings.query.docBoost,
                settings.query.fingerprintFrequencyCutoff,
                stats.meanAveragePrecision,
                stats.meanRecallAt10,
                stats.meanDuration.toMillis)

              println(cells.mkString("", "; ", ";\n"))
              writer.write(cells.mkString("", "; ", ";\n"))

              (stats, cells)
            })
        }

        val run = RunStats(runName, allStats)

        using(new FileWriter(statsOutputFile, true)) { statsWriter =>
          println(run)
          statsWriter.write(run.toString)
        }

        using(new FileWriter(queryDetailsOutputFile, true)) { writer =>
          run.topByMAP._1.queryStats.foreach { qs =>
            val cells = List[Any](
              ridx,
              runName,
              qs.id,
              qs.query,
              qs.averagePrecision,
              qs.recallAt10)

            writer.write(cells.mkString("", "; ", ";\n"))
          }
        }
    }
  }.get

  def randomize(settings: Settings, rngs: Map[String, Rng[Double]]): Rng[Settings] =
    for {
      query <- randomize(settings.query, rngs)
    } yield settings.copy(query = query)

  def randomize(settings: QuerySettings, rngs: Map[String, Rng[Double]]): Rng[QuerySettings] =
    for {
      pw <- rngs(penaltyWeight)
      dist <- rngs(distanceBoostWeight)
      depth <- rngs(depthBoostWeight)
      tf <- rngs(typeFrequencyWeight)
      db <- rngs(docBoost)
    } yield settings.copy(
      penaltyWeight = pw,
      distanceBoostWeight = dist,
      depthBoostWeight = depth,
      typeFrequencyWeight = tf,
      docBoost = db)

  lazy val format = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
  lazy val now = Calendar.getInstance.getTime

  def outputFile() = {
    new File(outputDir).mkdirs()
    new File(s"$outputDir/evaluation-${format.format(now)}.csv")
  }

  def statsOutputFile() = {
    new File(outputDir).mkdirs()
    new File(s"$outputDir/evaluation-stats-${format.format(now)}.txt")
  }

  def queryDetailsOutputFile() = {
    new File(outputDir).mkdirs()
    new File(s"$outputDir/evaluation-queries-${format.format(now)}.csv")
  }
}

case class RunStats(name: String, stats: List[(Stats, List[Any])]) {
  def topByMAP = stats.maxBy(_._1.meanAveragePrecision)
  def topByR10 = stats.maxBy(_._1.meanRecallAt10)
  def avgMAP = stats.map(_._1.meanAveragePrecision).sum / stats.length
  def avgR10 = stats.map(_._1.meanRecallAt10).sum / stats.length
  def avgRuntime = stats.map(_._1.meanDuration).reduce(_ + _) / stats.length

  override def toString =
    s"""
      |${name}
      |  top by MAP: ${topByMAP._2}
      |  top by R@10: ${topByR10._2}
      |  avg. MAP: ${avgMAP}
      |  avg. R@10: ${avgR10}
      |  avg. runtime: ${avgRuntime.toMillis}
      |""".stripMargin
}
