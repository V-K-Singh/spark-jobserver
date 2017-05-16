package ooyala.common.akka.metrics

import collection.JavaConverters._
import com.codahale.metrics._
import java.util.concurrent.TimeUnit
import org.coursera.metrics.datadog.DatadogReporter
import org.coursera.metrics.datadog.DatadogReporter.Expansion
import org.coursera.metrics.datadog.transport.{HttpTransport, Transport, UdpTransport}
import org.slf4j.LoggerFactory
import scala.util.Try

object MetricsWrapper {
  private val logger = LoggerFactory.getLogger(getClass)
  val registry: MetricRegistry = new MetricRegistry
  private var shutdownHook: Thread = null

  // Registers JVM metrics for monitoring
  JvmMetricsWrapper.registerJvmMetrics(registry)

  def startDatadogReporter(config: DatadogConfig): Unit = {
    val transportOpt: Option[Transport] = config.agentPort.map {
      port =>
        logger.debug("Datadog reporter: datadog agent port - " + port)
        new UdpTransport.Builder().withPort(port).build
    } orElse config.apiKey.map {
      apiKey => new HttpTransport.Builder().withApiKey(apiKey).build
    }

    transportOpt match {
      case Some(transport) =>
        val datadogReporterBuilder = DatadogReporter.forRegistry(registry)

        // Adds host name
        config.hostName match {
          case Some(hostName) =>
            logger.debug("Datadog reporter: hostname - " + hostName)
            datadogReporterBuilder.withHost(hostName)
          case _ =>
            logger.info("No host name provided, won't report host name to datadog.")
        }

        // Adds tags if provided
        config.tags.foreach {
          tags =>
            logger.debug("Datadog reporter: tags - " + tags)
            datadogReporterBuilder.withTags(tags.asJava)
        }

        val datadogReporter = datadogReporterBuilder
          .withTransport(transport)
          .withExpansions(Expansion.ALL)
          .build

        shutdownHook = new Thread {
          override def run {
            datadogReporter.stop
          }
        }

        // Start the reporter and set up shutdown hooks
        datadogReporter.start(config.durationInSeconds, TimeUnit.SECONDS)
        Runtime.getRuntime.addShutdownHook(shutdownHook)

        logger.info("Datadog reporter started.")
      case _ =>
        logger.info("No transport available, Datadog reporting not started.")
    }
  }

  def newGauge[T](klass: Class[_], name: String, metric: => T): Gauge[T] = {
    val metricName = MetricRegistry.name(klass, name)
    val gauge = Try(registry.register(metricName, new Gauge[T] {
      override def getValue(): T = metric
    })).map { g => g } recover { case _ =>
      registry.getGauges.get(metricName)
    }
    gauge.get.asInstanceOf[Gauge[T]]
  }

  def newCounter(klass: Class[_], name: String): Counter =
    registry.counter(MetricRegistry.name(klass, name))

  def newTimer(klass: Class[_], name: String): Timer =
    registry.timer(MetricRegistry.name(klass, name))

  def newHistogram(klass: Class[_], name: String): Histogram =
    registry.histogram(MetricRegistry.name(klass, name))

  def newMeter(klass: Class[_], name: String): Meter =
    registry.meter(MetricRegistry.name(klass, name))

  def getRegistry: MetricRegistry = {
    return registry
  }

  def shutdown = {
    if (shutdownHook != null) {
      Runtime.getRuntime.removeShutdownHook(shutdownHook)
      shutdownHook.run
    }
  }
}