package spark.jobserver

import akka.actor.ActorSystem
import akka.actor.Props
import com.typesafe.config.{Config, ConfigFactory}
import java.io.File
import ooyala.common.akka.metrics.{DatadogConfigParser, MetricsLevel, MetricsWrapper}
import org.slf4j.LoggerFactory
import scala.util.Try
import spark.jobserver.io.{JobDAO, JobDAOMetricsMonitor}

/**
 * The Spark Job Server is a web service that allows users to submit and run Spark jobs, check status,
 * and view results.
 * It may offer other goodies in the future.
 * It only takes in one optional command line arg, a config file to override the default (and you can still
 * use -Dsetting=value to override)
 * -- Configuration --
 * {{{
 *   spark {
 *     master = "local"
 *     jobserver {
 *       port = 8090
 *     }
 *   }
 * }}}
 */
object JobServer {
  val logger = LoggerFactory.getLogger(getClass)

  // Allow custom function to create ActorSystem.  An example of why this is useful:
  // we can have something that stores the ActorSystem so it could be shut down easily later.
  def start(args: Array[String], makeSystem: Config => ActorSystem) {
    val defaultConfig = ConfigFactory.load()
    val config = if (args.length > 0) {
      val configFile = new File(args(0))
      if (!configFile.exists()) {
        println("Could not find configuration file " + configFile)
        sys.exit(1)
      }
      ConfigFactory.parseFile(configFile).withFallback(defaultConfig)
    } else {
      defaultConfig
    }
    logger.info("Starting JobServer with config {}", config.getConfig("spark").root.render())
    val port = config.getInt("spark.jobserver.port")

    // TODO: Hardcode for now to get going. Make it configurable later.
    val system = makeSystem(config)
    val clazz = Class.forName(config.getString("spark.jobserver.jobdao"))
    val ctor = clazz.getDeclaredConstructor(Class.forName("com.typesafe.config.Config"))
    val backingJobDao = ctor.newInstance(config).asInstanceOf[JobDAO]
    // Monitors JobDAO metrics if metrics level is appropriate.
    val metricsLevel = Try(MetricsLevel.valueOf(config.getInt("spark.jobserver.metrics.level")))
      .getOrElse(MetricsLevel.NONE)
    val jobDAO = metricsLevel match {
      case MetricsLevel.NONE => backingJobDao
      case level => JobDAOMetricsMonitor.newInstance(backingJobDao, level)
    }

    val jarManager = system.actorOf(Props(classOf[JarManager], jobDAO), "jar-manager")
    val supervisor = system.actorOf(Props(classOf[LocalContextSupervisorActor], jobDAO), "context-supervisor")
    val jobInfo = system.actorOf(Props(classOf[JobInfoActor], jobDAO, supervisor), "job-info")
    val sparkWebUi = system.actorOf(Props(classOf[SparkWebUiActor]), "spark-web-ui")
    // Create initial contexts
    supervisor ! ContextSupervisor.AddContextsFromConfig
    new WebApi(system, config, port, jarManager, supervisor, jobInfo, sparkWebUi).start()

    // Setups and starts datadog reporting
    val datadogConfig = DatadogConfigParser.parse(config)
    MetricsWrapper.startDatadogReporter(datadogConfig)
  }

  def main(args: Array[String]) {
    start(args, config => ActorSystem("JobServer", config))
  }
}
