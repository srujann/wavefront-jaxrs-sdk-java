package com.wavefront.sdk.jaxrs.reporter;

import com.wavefront.internal.reporter.SdkReporter;
import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.Utils;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.common.application.HeartbeaterService;
import com.wavefront.sdk.entities.metrics.WavefrontMetricSender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import static com.wavefront.sdk.common.Constants.SDK_METRIC_PREFIX;
import static com.wavefront.sdk.jaxrs.Constants.JAXRS_SERVER_COMPONENT;

/**
 * Wavefront reporter for your JAX-RS based application responsible for reporting metrics and
 * histograms out of the box for you.
 *
 * @author Hao Song (songhao@vmware.com).
 */
public class WavefrontJaxrsReporter implements SdkReporter {

  private final WavefrontInternalReporter wfReporter;
  private final WavefrontInternalReporter sdkMetricsReporter;
  private final int reportingIntervalSeconds;
  private final HeartbeaterService heartbeaterService;

  @Deprecated
  private WavefrontJaxrsReporter(WavefrontInternalReporter wfReporter,
                                 int reportingIntervalSeconds,
                                 WavefrontMetricSender wavefrontMetricSender,
                                 ApplicationTags applicationTags,
                                 String source) {
    this(wfReporter, reportingIntervalSeconds, wavefrontMetricSender, applicationTags, source,
        null);
  }

  private WavefrontJaxrsReporter(WavefrontInternalReporter wfReporter,
                                 int reportingIntervalSeconds,
                                 WavefrontMetricSender wavefrontMetricSender,
                                 ApplicationTags applicationTags,
                                 String source,
                                 WavefrontInternalReporter sdkMetricsReporter) {
    if (wfReporter == null)
      throw new NullPointerException("Invalid wfReporter");
    if (wavefrontMetricSender == null)
      throw new NullPointerException("Invalid wavefrontSender");
    if (applicationTags == null)
      throw new NullPointerException("Invalid ApplicationTags");
    this.wfReporter = wfReporter;
    this.reportingIntervalSeconds = reportingIntervalSeconds;
    this.sdkMetricsReporter = sdkMetricsReporter;
    heartbeaterService = new HeartbeaterService(wavefrontMetricSender, applicationTags,
        Collections.singletonList(JAXRS_SERVER_COMPONENT), source);
  }

  @Override
  public void incrementCounter(MetricName metricName) {
    wfReporter.newCounter(metricName).inc();
  }

  @Override
  public void incrementCounter(MetricName metricName, long l) {
    wfReporter.newCounter(metricName).inc(l);
  }

  @Override
  public void incrementDeltaCounter(MetricName metricName) {
    wfReporter.newDeltaCounter(metricName).inc();
  }

  @Override
  public void registerGauge(MetricName metricName, AtomicInteger value) {
    wfReporter.newGauge(metricName, () ->  (() -> (double) value.get()));
  }

  @Override
  public void updateHistogram(MetricName metricName, long latencyMillis) {
    wfReporter.newWavefrontHistogram(metricName).update(latencyMillis);
  }

  public static class Builder {
    // Required parameters
    private final ApplicationTags applicationTags;
    private final String prefix = "jaxrs.server";

    // Optional parameters
    private int reportingIntervalSeconds = 60;

    @Nullable
    private String source;

    /**
     * Builder to build WavefrontJaxrsReporter.
     *
     * @param applicationTags metadata about your application that you want to be propagated as tags
     *                        when metrics/histograms are sent to Wavefront.
     */
    public Builder(ApplicationTags applicationTags) {
      this.applicationTags = applicationTags;
    }

    /**
     * Set reporting interval i.e. how often you want to report the metrics/histograms to
     * Wavefront.
     *
     * @param reportingIntervalSeconds reporting interval in seconds.
     * @return {@code this}.
     */
    public Builder reportingIntervalSeconds(int reportingIntervalSeconds) {
      this.reportingIntervalSeconds = reportingIntervalSeconds;
      return this;
    }

    /**
     * Set the source tag for your metric and histograms.
     *
     * @param source Name of the source/host where your application is running.
     * @return {@code this}.
     */
    public Builder withSource(String source) {
      this.source = source;
      return this;
    }

    /**
     * Build WavefrontJaxrsReporter.
     *
     * @param wavefrontSender send data to Wavefront via proxy or direct ingestion.
     * @return An instance of {@link WavefrontJaxrsReporter}.
     */
    public WavefrontJaxrsReporter build(WavefrontSender wavefrontSender) {
      if (source == null) {
        try {
          source = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
          // Should never happen
          source = "unknown";
        }
      }

      Map<String, String> pointTags = new HashMap<>();
      pointTags.put("application", applicationTags.getApplication());
      if (applicationTags.getCustomTags() != null) {
        pointTags.putAll(applicationTags.getCustomTags());
      }

      WavefrontInternalReporter wfReporter = new WavefrontInternalReporter.Builder().
          prefixedWith(prefix).withSource(source).withReporterPointTags(pointTags).
          reportMinuteDistribution().build(wavefrontSender);

      WavefrontInternalReporter sdkMetricsReporter = new WavefrontInternalReporter.Builder().
          prefixedWith(SDK_METRIC_PREFIX + ".jaxrs").withSource(source).
          withReporterPointTags(pointTags).build(wavefrontSender);

      double sdkVersion = Utils.getSemVerGauge("wavefront-jaxrs-sdk-java");
      sdkMetricsReporter.newGauge(new MetricName("version", Collections.emptyMap()),
          () -> (() -> sdkVersion));

      return new WavefrontJaxrsReporter(wfReporter, reportingIntervalSeconds, wavefrontSender,
          applicationTags, source, sdkMetricsReporter);
    }
  }

  @Override
  public void start() {
    wfReporter.start(reportingIntervalSeconds, TimeUnit.SECONDS);
    if (sdkMetricsReporter != null) {
      sdkMetricsReporter.start(1, TimeUnit.MINUTES);
    }
  }

  @Override
  public void stop() {
    heartbeaterService.close();
    wfReporter.stop();
    if (sdkMetricsReporter != null) {
      sdkMetricsReporter.stop();
    }
  }
}
