
## pointset_sample_rate (BETA)

Check that a device publishes pointset events not faster than config sample_rate_sec

1. Wait for measure initial sample rate
1. Update config before receive at least 5 pointset events:
    * Add `pointset.sample_rate_sec` = `5`
    * Add `pointset.sample_limit_sec` = `1`
1. Wait for receive at least 5 pointset events
1. Check that time period between successive pointset events is between 1 and 5 seconds
