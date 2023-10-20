package com.google.bos.iot.core.proxy;

import static com.google.bos.iot.core.proxy.ProxyTarget.STATE_TOPIC;
import static com.google.udmi.util.GeneralUtils.catchOrElse;
import static com.google.udmi.util.GeneralUtils.catchToNull;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.daq.mqtt.util.MessagePublisher;
import com.google.daq.mqtt.validator.Validator;
import com.google.udmi.util.Common;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import udmi.schema.ExecutionConfiguration;
import udmi.schema.IotAccess.IotProvider;

/**
 * Handle publishing sensor data to a Cloud IoT MQTT endpoint.
 */
class MqttPublisher implements MessagePublisher {

  static final String DEFAULT_CLEARBLADE_HOSTNAME = "us-central1-mqtt.clearblade.com";
  static final String DEFAULT_GBOS_HOSTNAME = "mqtt.bos.goog";
  static final String BRIDGE_PORT = "8883";
  private static final Logger LOG = LoggerFactory.getLogger(MqttPublisher.class);
  private static final boolean MQTT_SHOULD_RETAIN = false;
  private static final long STATE_RATE_LIMIT_MS = 1000 * 2;
  private static final int MQTT_QOS = 1;
  private static final String CONFIG_UPDATE_TOPIC_FMT = "/devices/%s/config";
  private static final String ERROR_TOPIC_FMT = "/devices/%s/errors";
  private static final String COMMAND_TOPIC_FMT = "/devices/%s/commands/#";
  private static final int COMMANDS_QOS = 0;
  private static final String UNUSED_ACCOUNT_NAME = "unused";
  private static final int INITIALIZE_TIME_MS = 20000;
  private static final String MESSAGE_TOPIC_FORMAT = "/devices/%s/%s";
  private static final String BROKER_URL_FORMAT = "ssl://%s:%s";
  private static final String ID_FORMAT = "projects/%s/locations/%s/registries/%s/devices/%s";
  private static final int PUBLISH_THREAD_COUNT = 10;
  private static final String ATTACH_MESSAGE_FORMAT = "/devices/%s/attach";
  private static final int TOKEN_EXPIRATION_SEC = 60 * 60;
  static final int TOKEN_EXPIRATION_MS = TOKEN_EXPIRATION_SEC * 1000;
  public static final String EMPTY_JSON = "{}";
  private static final String TICKLE_TOPIC = "events/tickle";
  private static final long TICKLE_PERIOD_SEC = 10;
  private final ExecutorService publisherExecutor =
      Executors.newFixedThreadPool(PUBLISH_THREAD_COUNT);
  private final Semaphore connectWait = new Semaphore(0);
  private final AtomicInteger publishCounter = new AtomicInteger(0);
  private final AtomicInteger errorCounter = new AtomicInteger(0);
  private final Map<String, Long> lastStateTime = Maps.newConcurrentMap();
  private final MqttClient mqttClient;
  private final Set<String> attachedClients = new ConcurrentSkipListSet<>();
  private final BiConsumer<String, String> onMessage;
  private final BiConsumer<MqttPublisher, Throwable> onError;
  private final String deviceId;
  private final byte[] keyBytes;
  private final String algorithm;
  private final String registryId;
  private final String projectId;
  private final String cloudRegion;
  private final String providerHostname;
  private final String clientId;
  private final ScheduledFuture<?> tickler;
  long mqttTokenSetTimeMs;
  private MqttConnectOptions mqttConnectOptions;

  MqttPublisher(ExecutionConfiguration executionConfiguration, byte[] keyBytes, String algorithm,
      BiConsumer<String, String> onMessage, BiConsumer<MqttPublisher, Throwable> onError) {
    this.onMessage = onMessage;
    this.onError = onError;
    this.projectId = executionConfiguration.project_id;
    this.cloudRegion = executionConfiguration.cloud_region;
    this.registryId = getRegistryId(executionConfiguration);
    this.deviceId = executionConfiguration.device_id;
    this.algorithm = algorithm;
    this.keyBytes = keyBytes;
    this.providerHostname = getProviderHostname(executionConfiguration);
    this.clientId = catchToNull(() -> executionConfiguration.reflector_endpoint.client_id);
    LOG.info(deviceId + " token expiration sec " + TOKEN_EXPIRATION_SEC);
    mqttClient = newMqttClient(deviceId);
    connectMqttClient(deviceId);
    tickler = scheduleTickler();
  }

  private static String getRegistryId(ExecutionConfiguration config) {
    String prefix = ifNotNullGet(config.udmi_namespace, name -> name + Common.PREFIX_SEPARATOR, "");
    return prefix + config.registry_id;
  }

  private static ThreadFactory getDaemonThreadFactory() {
    return runnable -> {
      Thread thread = Executors.defaultThreadFactory().newThread(runnable);
      thread.setDaemon(true);
      return thread;
    };
  }

  private static String getProviderHostname(ExecutionConfiguration executionConfiguration) {
    IotProvider iotProvider =
        ofNullable(executionConfiguration.iot_provider).orElse(IotProvider.IMPLICIT);
    return catchOrElse(() -> executionConfiguration.reflector_endpoint.hostname,
        () -> switch (iotProvider) {
          case JWT -> requireNonNull(executionConfiguration.bridge_host, "missing bridge_host");
          case GBOS -> DEFAULT_GBOS_HOSTNAME;
          case CLEARBLADE -> DEFAULT_CLEARBLADE_HOSTNAME;
          default -> throw new RuntimeException("Unsupported iot provider " + iotProvider);
        }
    );
  }

  private ScheduledFuture<?> scheduleTickler() {
    return Executors.newSingleThreadScheduledExecutor(getDaemonThreadFactory())
        .scheduleWithFixedDelay(this::tickleConnection,
            TICKLE_PERIOD_SEC, TICKLE_PERIOD_SEC, TimeUnit.SECONDS);
  }

  private void tickleConnection() {
    LOG.debug("Tickle " + mqttClient.getClientId());
    publish(deviceId, TICKLE_TOPIC, EMPTY_JSON);
  }

  @Override
  public String publish(String deviceId, String topic, String data) {
    Preconditions.checkNotNull(deviceId, "publish deviceId");
    LOG.debug(this.deviceId + " publishing in background " + registryId + "/" + deviceId);
    try {
      Instant now = Instant.now();
      publisherExecutor.submit(() -> publishCore(deviceId, topic, data, now));
    } catch (Exception e) {
      throw new RuntimeException("While publishing message", e);
    }
    return null;
  }

  private synchronized void publishCore(String deviceId, String topic, String payload,
      Instant start) {
    try {
      if (!connectWait.tryAcquire(INITIALIZE_TIME_MS, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Timeout waiting for connection");
      }
    } catch (Exception e) {
      throw new RuntimeException("Error acquiring lock", e);
    }
    try {
      if (!mqttClient.isConnected()) {
        throw new RuntimeException("MQTT Client not connected");
      }
      maybeRefreshJwt();
      if (!attachedClients.contains(deviceId)) {
        attachedClients.add(deviceId);
        attachClient(deviceId);
      }
      if (STATE_TOPIC.equals(topic)) {
        delayStateUpdate(deviceId);
      }
      sendMessage(getMessageTopic(deviceId, topic), payload.getBytes());
      LOG.debug(this.deviceId + " publishing complete " + registryId + "/" + deviceId);
    } catch (Exception e) {
      errorCounter.incrementAndGet();
      throw new RuntimeException(format("Publish failed for %s: %s", deviceId, e), e);
    } finally {
      connectWait.release();
    }
    long seconds = Duration.between(start, Instant.now()).getSeconds();
    LOG.debug(format("Publishing mqtt message took %ss", seconds));
  }

  private synchronized void delayStateUpdate(String deviceId) {
    long now = System.currentTimeMillis();
    long last = lastStateTime.getOrDefault(deviceId, now - STATE_RATE_LIMIT_MS);
    long delta = now - last;
    long delay = STATE_RATE_LIMIT_MS - delta;
    if (delay > 0) {
      LOG.warn("Delaying state message by " + delay);
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        LOG.warn("Interrupted sleep", e);
      }
      now += delay;
    } else {
      LOG.debug("Delta for state message was " + delta);
    }
    lastStateTime.put(deviceId, now);
  }

  private void sendMessage(String mqttTopic, byte[] mqttMessage) throws Exception {
    LOG.debug(deviceId + " sending message to " + mqttTopic);
    mqttClient.publish(mqttTopic, mqttMessage, MQTT_QOS, MQTT_SHOULD_RETAIN);
    publishCounter.incrementAndGet();
  }

  @Override
  public void close() {
    try {
      LOG.debug(format("Shutting down executor %x", publisherExecutor.hashCode()));
      tickler.cancel(false);
      publisherExecutor.shutdownNow();
      if (!publisherExecutor.awaitTermination(INITIALIZE_TIME_MS, TimeUnit.MILLISECONDS)) {
        LOG.error("Executor tasks still remaining");
      }
      if (mqttClient.isConnected()) {
        mqttClient.disconnect();
      }
      mqttClient.close();
    } catch (Exception e) {
      LOG.error(deviceId + " while terminating client: " + e, e);
    }
  }

  @Override
  public String getSubscriptionId() {
    return mqttClient.getClientId();
  }

  @Override
  public boolean isActive() {
    return mqttClient.isConnected();
  }

  @Override
  public Validator.MessageBundle takeNextMessage(QuerySpeed speed) {
    throw new IllegalStateException("Can't process message");
  }

  private void attachClient(String deviceId) {
    try {
      LOG.info(this.deviceId + " attaching " + deviceId);
      String topic = format(ATTACH_MESSAGE_FORMAT, deviceId);
      String payload = "";
      sendMessage(topic, payload.getBytes());
    } catch (Exception e) {
      LOG.error(this.deviceId + format(" error while binding client %s: %s", deviceId,
          e));
    }
  }

  private MqttClient newMqttClient(String deviceId) {
    try {
      String brokerUrl = getBrokerUrl();
      String clientId = getClientId(deviceId);
      MqttClient mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
      LOG.info(this.deviceId + " creating client " + clientId + " on " + brokerUrl);
      return mqttClient;
    } catch (Exception e) {
      errorCounter.incrementAndGet();
      throw new RuntimeException("Creating new MQTT client " + deviceId, e);
    }
  }

  private void connectMqttClient(String deviceId) {
    try {
      if (mqttClient.isConnected()) {
        return;
      }
      mqttClient.setCallback(new MqttCallbackHandler());
      mqttClient.setTimeToWait(INITIALIZE_TIME_MS);
      mqttClient.setManualAcks(false);

      mqttConnectOptions = new MqttConnectOptions();
      // Note that the the Google Cloud IoT only supports MQTT 3.1.1, and Paho requires that we
      // explicitly set this. If you don't set MQTT version, the server will immediately close its
      // connection to your device.
      mqttConnectOptions.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
      mqttConnectOptions.setUserName(UNUSED_ACCOUNT_NAME);
      mqttConnectOptions.setMaxInflight(PUBLISH_THREAD_COUNT * 2);
      connectAndSetupMqtt();
      connectWait.release();
    } catch (Exception e) {
      errorCounter.incrementAndGet();
      throw new RuntimeException("Connecting MQTT client " + deviceId, e);
    }
  }

  public void connectAndSetupMqtt() {
    try {
      LOG.info(deviceId + " creating new jwt");
      mqttConnectOptions.setPassword(createJwt());
      mqttTokenSetTimeMs = System.currentTimeMillis();
      LOG.info(deviceId + " connecting to mqtt server " + getBrokerUrl());
      mqttClient.connect(mqttConnectOptions);
      attachedClients.clear();
      attachedClients.add(deviceId);
      LOG.info(deviceId + " adding subscriptions");
      subscribeToUpdates(deviceId);
      subscribeToErrors(deviceId);
      subscribeToCommands(deviceId);
      LOG.info(deviceId + " done with setup connection");
    } catch (Exception e) {
      throw new RuntimeException("While setting up new mqtt connection to " + deviceId, e);
    }
  }

  private void maybeRefreshJwt() {
    long refreshTime = mqttTokenSetTimeMs + TOKEN_EXPIRATION_MS / 2;
    long currentTimeMillis = System.currentTimeMillis();
    long remaining = refreshTime - currentTimeMillis;
    LOG.debug(deviceId + " remaining until refresh " + remaining);
    if (remaining < 0 && mqttClient.isConnected()) {
      try {
        LOG.info(deviceId + " handling token refresh");
        mqttClient.disconnect();
        long disconnectTime = System.currentTimeMillis() - currentTimeMillis;
        LOG.debug(deviceId + " disconnect took " + disconnectTime);
        connectAndSetupMqtt();
      } catch (Exception e) {
        throw new RuntimeException("While processing disconnect", e);
      }
    }
  }

  String getClientId(String deviceId) {
    return ofNullable(clientId).orElse(
        format(ID_FORMAT, projectId, cloudRegion, registryId, deviceId));
  }

  private String getBrokerUrl() {
    return format(BROKER_URL_FORMAT, providerHostname, BRIDGE_PORT);
  }

  private String getMessageTopic(String deviceId, String topic) {
    return format(MESSAGE_TOPIC_FORMAT, deviceId, topic);
  }

  private void subscribeToUpdates(String deviceId) {
    String updateTopic = format(CONFIG_UPDATE_TOPIC_FMT, deviceId);
    try {
      mqttClient.subscribe(updateTopic);
    } catch (MqttException e) {
      throw new RuntimeException("While subscribing to MQTT topic " + updateTopic, e);
    }
  }

  private void subscribeToErrors(String deviceId) {
    String updateTopic = format(ERROR_TOPIC_FMT, deviceId);
    try {
      mqttClient.subscribe(updateTopic);
    } catch (MqttException e) {
      throw new RuntimeException("While subscribing to MQTT topic " + updateTopic, e);
    }
  }

  private void subscribeToCommands(String deviceId) {
    String updateTopic = format(COMMAND_TOPIC_FMT, deviceId);
    try {
      mqttClient.subscribe(updateTopic, COMMANDS_QOS);
    } catch (MqttException e) {
      throw new RuntimeException("While subscribing to MQTT topic " + updateTopic, e);
    }
  }

  String getDeviceId() {
    return deviceId;
  }

  /**
   * Load a PKCS8 encoded keyfile from the given path.
   */
  private PrivateKey loadKeyBytes(byte[] keyBytes, String algorithm) throws Exception {
    try {
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
      KeyFactory kf = KeyFactory.getInstance(algorithm);
      return kf.generatePrivate(spec);
    } catch (Exception e) {
      throw new IllegalArgumentException("Loading key bytes", e);
    }
  }

  private char[] createJwt() {
    try {
      return createJwt(projectId, keyBytes, algorithm).toCharArray();
    } catch (Throwable t) {
      throw new RuntimeException("While creating jwt", t);
    }
  }

  /**
   * Create a Cloud IoT JWT for the given project id, signed with the given private key.
   */
  private String createJwt(String projectId, byte[] privateKeyBytes, String algorithm)
      throws Exception {
    DateTime now = new DateTime();

    JwtBuilder jwtBuilder =
        Jwts.builder()
            .setIssuedAt(now.toDate())
            .setExpiration(now.plusMillis(TOKEN_EXPIRATION_MS).toDate())
            .setAudience(projectId);

    switch (algorithm) {
      case "RS256": {
        PrivateKey privateKey = loadKeyBytes(privateKeyBytes, "RSA");
        return jwtBuilder.signWith(SignatureAlgorithm.RS256, privateKey).compact();
      }
      case "ES256": {
        PrivateKey privateKey = loadKeyBytes(privateKeyBytes, "EC");
        return jwtBuilder.signWith(SignatureAlgorithm.ES256, privateKey).compact();
      }
      default:
        throw new IllegalArgumentException(
            "Invalid algorithm " + algorithm + ". Should be one of 'RS256' or 'ES256'.");
    }
  }

  public String getBridgeHost() {
    return providerHostname;
  }

  private class MqttCallbackHandler implements MqttCallback {

    MqttCallbackHandler() {
    }

    @Override
    public void connectionLost(Throwable cause) {
      LOG.warn("MQTT connection lost " + deviceId, cause);
      connectWait.release();
      onError.accept(MqttPublisher.this, cause);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
      try {
        onMessage.accept(topic, message.toString());
      } catch (Exception e) {
        onError.accept(MqttPublisher.this, e);
      }
    }
  }
}
