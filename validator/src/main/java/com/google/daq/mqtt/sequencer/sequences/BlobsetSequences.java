package com.google.daq.mqtt.sequencer.sequences;

import static com.google.daq.mqtt.util.TimePeriodConstants.NINETY_SECONDS_MS;
import static com.google.daq.mqtt.util.TimePeriodConstants.ONE_MINUTE_MS;
import static com.google.daq.mqtt.util.TimePeriodConstants.THREE_MINUTES_MS;
import static com.google.daq.mqtt.util.TimePeriodConstants.TWO_MINUTES_MS;
import static com.google.udmi.util.GeneralUtils.encodeBase64;
import static com.google.udmi.util.GeneralUtils.sha256;
import static com.google.udmi.util.JsonUtil.stringify;
import static org.junit.Assert.assertNotEquals;
import static udmi.schema.Bucket.ENDPOINT_CONFIG;
import static udmi.schema.Bucket.SYSTEM_MODE;
import static udmi.schema.Category.BLOBSET_BLOB_APPLY;
import static udmi.schema.FeatureEnumeration.FeatureStage.ALPHA;
import static udmi.schema.FeatureEnumeration.FeatureStage.PREVIEW;

import com.google.daq.mqtt.sequencer.Feature;
import com.google.daq.mqtt.sequencer.SequenceBase;
import com.google.daq.mqtt.sequencer.Summary;
import com.google.daq.mqtt.sequencer.ValidateSchema;
import com.google.daq.mqtt.sequencer.semantic.SemanticDate;
import com.google.daq.mqtt.sequencer.semantic.SemanticValue;
import java.util.Date;
import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;
import udmi.schema.BlobBlobsetConfig;
import udmi.schema.BlobBlobsetConfig.BlobPhase;
import udmi.schema.BlobBlobsetState;
import udmi.schema.BlobsetConfig;
import udmi.schema.BlobsetConfig.SystemBlobsets;
import udmi.schema.EndpointConfiguration;
import udmi.schema.EndpointConfiguration.Protocol;
import udmi.schema.Entry;
import udmi.schema.Envelope.SubFolder;
import udmi.schema.Level;
import udmi.schema.Operation.SystemMode;


/**
 * Validation tests for instances that involve blobset config messages.
 */

public class BlobsetSequences extends SequenceBase {

  public static final String JSON_MIME_TYPE = "application/json";
  public static final String DATA_URL_FORMAT = "data:%s;base64,%s";
  public static final String IOT_BLOB_KEY = SystemBlobsets.IOT_ENDPOINT_CONFIG.value();
  private static final String ENDPOINT_CONFIG_CLIENT_ID =
      "projects/%s/locations/%s/registries/%s/devices/%s";
  private static final String BOGUS_ENDPOINT_HOSTNAME = "twiddily.fiddily.fog";

  @Before
  public void setupExpectedParameters() {
    allowDeviceStateChange("blobset");
  }

  private String generateEndpointConfigClientId(String registryId) {
    return String.format(
        ENDPOINT_CONFIG_CLIENT_ID,
        projectId,
        cloudRegion,
        registryId,
        getDeviceId());
  }

  private String endpointConfigPayload(String hostname, String registryId) {
    EndpointConfiguration endpointConfiguration = new EndpointConfiguration();
    endpointConfiguration.protocol = Protocol.MQTT;
    endpointConfiguration.hostname = hostname;
    endpointConfiguration.client_id = generateEndpointConfigClientId(registryId);
    return stringify(endpointConfiguration);
  }

  private void untilClearedRedirect() {
    deviceConfig.blobset.blobs.remove(IOT_BLOB_KEY);
    untilTrue("endpoint config blobset state not defined", () -> deviceState.blobset == null
        || deviceState.blobset.blobs.get(IOT_BLOB_KEY) == null);
  }

  private void untilSuccessfulRedirect(BlobPhase blobPhase) {
    // This case is tracking the initial apply of a redirect, so it sets up the mirror config.
    if (blobPhase == BlobPhase.APPLY) {
      mirrorToOtherConfig();
    }
    untilTrue(String.format("blobset phase is %s and stateStatus is null", blobPhase), () -> {
      BlobBlobsetState blobBlobsetState = deviceState.blobset.blobs.get(IOT_BLOB_KEY);
      BlobBlobsetConfig blobBlobsetConfig = deviceConfig.blobset.blobs.get(IOT_BLOB_KEY);
      // Successful reconnect sends a state message with empty Entry.
      Entry blobStateStatus = blobBlobsetState.status;
      return blobPhase.equals(blobBlobsetState.phase)
          && blobBlobsetConfig.generation.equals(blobBlobsetState.generation)
          && blobStateStatus == null;
    });

    // This case is tracking the finalization of the redirect, so clear out the non-used one.
    if (blobPhase == BlobPhase.FINAL) {
      clearOtherConfig();
    }
  }

  private void untilErrorReported() {
    untilTrue("blobset entry config status is error", () -> {
      BlobBlobsetState blobBlobsetState = deviceState.blobset.blobs.get(IOT_BLOB_KEY);
      BlobBlobsetConfig blobBlobsetConfig = deviceConfig.blobset.blobs.get(IOT_BLOB_KEY);
      return blobBlobsetConfig.generation.equals(blobBlobsetState.generation)
          && blobBlobsetState.phase.equals(BlobPhase.FINAL)
          && blobBlobsetState.status.category.equals(BLOBSET_BLOB_APPLY)
          && blobBlobsetState.status.level == Level.ERROR.value();
    });
  }

  private void setDeviceConfigEndpointBlob(String hostname, String registryId, boolean badHash) {
    String payload = endpointConfigPayload(hostname, registryId);
    debug("Endpoint config", payload);
    BlobBlobsetConfig config = makeEndpointConfigBlob(payload, badHash);
    deviceConfig.blobset = new BlobsetConfig();
    deviceConfig.blobset.blobs = new HashMap<>();
    deviceConfig.blobset.blobs.put(IOT_BLOB_KEY, config);
  }

  private BlobBlobsetConfig makeEndpointConfigBlob(String payload, boolean badHash) {
    BlobBlobsetConfig config = new BlobBlobsetConfig();
    config.url = SemanticValue.describe("endpoint data", generateEndpointConfigDataUrl(payload));
    config.phase = BlobPhase.FINAL;
    config.generation = SemanticDate.describe("blob generation", new Date());
    String description = badHash ? "invalid blob data hash" : "blob data hash";
    config.sha256 = SemanticValue.describe(description,
        badHash ? sha256(payload + "X") : sha256(payload));
    return config;
  }

  private String generateEndpointConfigDataUrl(String payload) {
    return String.format(DATA_URL_FORMAT, JSON_MIME_TYPE, encodeBase64(payload));
  }

  @Feature(stage = PREVIEW, bucket = ENDPOINT_CONFIG)
  @Summary("Push endpoint config message to device that results in a connection error.")
  @Test(timeout = NINETY_SECONDS_MS) // TODO Is this enough? Does a client try X times?
  public void endpoint_connection_error() {
    setDeviceConfigEndpointBlob(BOGUS_ENDPOINT_HOSTNAME, registryId, false);
    untilErrorReported();
    untilClearedRedirect();
  }

  @Feature(stage = PREVIEW, bucket = ENDPOINT_CONFIG)
  @Summary("Check repeated endpoint with same information gets retried.")
  @Test(timeout = NINETY_SECONDS_MS)
  public void endpoint_connection_retry() {
    setDeviceConfigEndpointBlob(BOGUS_ENDPOINT_HOSTNAME, registryId, false);
    final Date savedGeneration = deviceConfig.blobset.blobs.get(IOT_BLOB_KEY).generation;
    untilErrorReported();
    setDeviceConfigEndpointBlob(BOGUS_ENDPOINT_HOSTNAME, registryId, false);
    // Semantically this is a different date; manually update for change-detection purposes.
    deviceConfig.blobset.blobs.get(IOT_BLOB_KEY).generation = SemanticDate.describe(
        "new generation", new Date());
    assertNotEquals("config generation", savedGeneration,
        deviceConfig.blobset.blobs.get(IOT_BLOB_KEY).generation);
    untilErrorReported();
    untilClearedRedirect();
  }

  @Feature(stage = PREVIEW, bucket = ENDPOINT_CONFIG)
  @Summary("Check a successful reconnect to the same endpoint.")
  @Test(timeout = NINETY_SECONDS_MS)
  public void endpoint_connection_success_reconnect() {
    setDeviceConfigEndpointBlob(getAlternateEndpointHostname(), registryId, false);
    untilSuccessfulRedirect(BlobPhase.FINAL);
    untilClearedRedirect();
  }

  @Feature(stage = ALPHA, bucket = ENDPOINT_CONFIG)
  @Summary("Failed connection because of bad hash.")
  @ValidateSchema(SubFolder.BLOBSET)
  @Test(timeout = ONE_MINUTE_MS)
  public void endpoint_connection_bad_hash() {
    setDeviceConfigEndpointBlob(getAlternateEndpointHostname(), registryId, true);
    untilTrue("blobset status is ERROR", () -> {
      BlobBlobsetState blobBlobsetState = deviceState.blobset.blobs.get(IOT_BLOB_KEY);
      BlobBlobsetConfig blobBlobsetConfig = deviceConfig.blobset.blobs.get(IOT_BLOB_KEY);
      // Successful reconnect sends a state message with empty Entry.
      Entry blobStateStatus = blobBlobsetState.status;
      return BlobPhase.FINAL.equals(blobBlobsetState.phase)
          && blobBlobsetConfig.generation.equals(blobBlobsetState.generation)
          && blobStateStatus.category.equals(BLOBSET_BLOB_APPLY)
          && blobStateStatus.level == Level.ERROR.value();
    });
  }

  @Test(timeout = NINETY_SECONDS_MS)
  @Feature(stage = PREVIEW, bucket = ENDPOINT_CONFIG)
  @Summary("Check connection to an alternate project.")
  public void endpoint_connection_success_alternate() {
    check_endpoint_connection_success(false);
  }

  @Test(timeout = THREE_MINUTES_MS)
  @Feature(stage = PREVIEW, bucket = ENDPOINT_CONFIG)
  public void endpoint_redirect_and_restart() {
    check_endpoint_connection_success(true);
  }

  @Test(timeout = TWO_MINUTES_MS)
  @Feature(stage = PREVIEW, bucket = ENDPOINT_CONFIG)
  public void endpoint_failure_and_restart() {
    setDeviceConfigEndpointBlob(BOGUS_ENDPOINT_HOSTNAME, registryId, false);
    untilErrorReported();
    check_system_restart();
    untilClearedRedirect();
  }

  private void check_endpoint_connection_success(boolean doRestart) {
    // Phase one: initiate connection to alternate registry.
    untilTrue("initial last_config matches config timestamp", this::lastConfigUpdated);
    setDeviceConfigEndpointBlob(getAlternateEndpointHostname(), altRegistry, false);
    untilSuccessfulRedirect(BlobPhase.APPLY);

    withAlternateClient(() -> {
      // Phase two: verify connection to alternate registry.
      untilSuccessfulRedirect(BlobPhase.FINAL);
      untilTrue("alternate last_config matches config timestamp",
          this::lastConfigUpdated);
      untilClearedRedirect();

      if (doRestart) {
        // Phase two.five: restart the system to make sure the change sticks.
        check_system_restart();
      }

      // Phase three: initiate connection back to initial registry.
      // Phase 3/4 test the same thing as phase 1/2, included to restore system to initial state.
      setDeviceConfigEndpointBlob(getAlternateEndpointHostname(), registryId, false);
      untilSuccessfulRedirect(BlobPhase.APPLY);
    });

    // Phase four: verify restoration of initial registry connection.
    whileDoing("restoring main connection", () -> {
      untilSuccessfulRedirect(BlobPhase.FINAL);
      untilTrue("restored last_config matches config timestamp", this::lastConfigUpdated);
      untilClearedRedirect();
    });
  }

  @Test
  @Summary("Restart and connect to same endpoint and expect it returns.")
  @Feature(stage = ALPHA, bucket = SYSTEM_MODE)
  public void system_mode_restart() {
    check_system_restart();
  }

  private void check_system_restart() {
    allowDeviceStateChange("system.operation.");

    // Prepare for the restart.
    final Date dateZero = new Date(0);
    untilTrue("last_start is not zero",
        () -> deviceState.system.operation.last_start.after(dateZero));

    final Integer initialCount = deviceState.system.operation.restart_count;
    checkThat("initial count is greater than 0", () -> initialCount > 0);

    deviceConfig.system.operation.mode = SystemMode.ACTIVE;

    untilTrue("system mode is ACTIVE",
        () -> deviceState.system.operation.mode.equals(SystemMode.ACTIVE));

    final Date last_config = deviceState.system.last_config;
    final Date last_start = deviceConfig.system.operation.last_start;

    // Send the restart mode.
    deviceConfig.system.operation.mode = SystemMode.RESTART;

    // Wait for the device to go through the correct states as it restarts.
    untilTrue("system mode is INITIAL",
        () -> deviceState.system.operation.mode.equals(SystemMode.INITIAL));

    checkThat("restart count increased by one",
        () -> deviceState.system.operation.restart_count == initialCount + 1);

    deviceConfig.system.operation.mode = SystemMode.ACTIVE;

    untilTrue("system mode is ACTIVE",
        () -> deviceState.system.operation.mode.equals(SystemMode.ACTIVE));

    // Capture error from last_start unexpectedly changing due to restart condition.
    try {
      untilTrue("last_config is newer than previous last_config before abort",
          () -> deviceState.system.last_config.after(last_config));
    } catch (AbortMessageLoop e) {
      info("Squelching aborted message loop: " + e.getMessage());
    }

    untilTrue("last_config is newer than previous last_config after abort",
        () -> deviceState.system.last_config.after(last_config));

    untilTrue("last_start is newer than previous last_start",
        () -> deviceConfig.system.operation.last_start.after(last_start));
  }
}
