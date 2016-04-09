/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver.compactions;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.regionserver.StoreConfigInformation;

/**
 * Compaction configuration for a particular instance of HStore.
 * Takes into account both global settings and ones set on the column family/store.
 * Control knobs for default compaction algorithm:
 * <p/>
 * maxCompactSize - upper bound on file size to be included in minor compactions
 * minCompactSize - lower bound below which compaction is selected without ratio test
 * minFilesToCompact - lower bound on number of files in any minor compaction
 * maxFilesToCompact - upper bound on number of files in any minor compaction
 * compactionRatio - Ratio used for compaction
 * <p/>
 * Set parameter as "hbase.hstore.compaction.<attribute>"
 */

//TODO: revisit this class for online parameter updating (both in xml and on the CF)
@InterfaceAudience.Private
public class CompactionConfiguration {

  static final Log LOG = LogFactory.getLog(CompactionConfiguration.class);

  private static final String CONFIG_PREFIX = "hbase.hstore.compaction.";
  public static final String HBASE_HSTORE_MIN_LOCALITY_TO_SKIP_MAJOR_COMPACT =
      "hbase.hstore.min.locality.to.skip.major.compact";
  public static final String RATIO_KEY = CONFIG_PREFIX + "ratio";
  public static final String MIN_KEY = CONFIG_PREFIX + "min";
  public static final String MAX_KEY = CONFIG_PREFIX + "max";
  
  /*
   * The epoch time length for the windows we no longer compact
   */
  public static final String MAX_AGE_MILLIS_KEY =CONFIG_PREFIX + "date.tiered.max.storefile.age.millis";
  public static final String BASE_WINDOW_MILLIS_KEY =
    CONFIG_PREFIX + "date.tiered.base.window.millis";
  public static final String WINDOWS_PER_TIER_KEY = CONFIG_PREFIX + "date.tiered.windows.per.tier";
  public static final String INCOMING_WINDOW_MIN_KEY =
    CONFIG_PREFIX + "date.tiered.incoming.window.min";
  public static final String COMPACTION_POLICY_CLASS_FOR_TIERED_WINDOWS_KEY =
      CONFIG_PREFIX + "date.tiered.window.policy.class";
  public static final String SINGLE_OUTPUT_FOR_MINOR_COMPACTION_KEY =
      CONFIG_PREFIX + "date.tiered.single.output.for.minor.compaction";

  private static final Class<? extends RatioBasedCompactionPolicy>
    DEFAULT_TIER_COMPACTION_POLICY_CLASS = ExploringCompactionPolicy.class;

  Configuration conf;
  StoreConfigInformation storeConfigInfo;

  long maxCompactSize;
  long minCompactSize;
  int minFilesToCompact;
  int maxFilesToCompact;
  double compactionRatio;
  double offPeekCompactionRatio;
  long throttlePoint;
  long majorCompactionPeriod;
  float majorCompactionJitter;
  final float minLocalityToForceCompact;
  private final long maxStoreFileAgeMillis;
  private final long baseWindowMillis;
  private final int windowsPerTier;
  private final int incomingWindowMin;
  private final String compactionPolicyForTieredWindow;
  private final boolean singleOutputForMinorCompaction;
  
  CompactionConfiguration(Configuration conf, StoreConfigInformation storeConfigInfo) {
    this.conf = conf;
    this.storeConfigInfo = storeConfigInfo;

    maxCompactSize = conf.getLong(CONFIG_PREFIX + "max.size", Long.MAX_VALUE);
    minCompactSize = conf.getLong(CONFIG_PREFIX + "min.size",
        storeConfigInfo.getMemstoreFlushSize());
    minFilesToCompact = Math.max(2, conf.getInt(MIN_KEY,
          /*old name*/ conf.getInt("hbase.hstore.compactionThreshold", 3)));
    maxFilesToCompact = conf.getInt(MAX_KEY, 10);
    compactionRatio = conf.getFloat(RATIO_KEY, 1.2F);
    offPeekCompactionRatio = conf.getFloat(CONFIG_PREFIX + "ratio.offpeak", 5.0F);

    throttlePoint =  conf.getLong("hbase.regionserver.thread.compaction.throttle",
          2 * maxFilesToCompact * storeConfigInfo.getMemstoreFlushSize());
    majorCompactionPeriod = conf.getLong(HConstants.MAJOR_COMPACTION_PERIOD, 1000*60*60*24*7);
    // Make it 0.5 so jitter has us fall evenly either side of when the compaction should run
    majorCompactionJitter = conf.getFloat("hbase.hregion.majorcompaction.jitter", 0.50F);
    minLocalityToForceCompact = conf.getFloat(HBASE_HSTORE_MIN_LOCALITY_TO_SKIP_MAJOR_COMPACT, 0f);

    maxStoreFileAgeMillis = conf.getLong(MAX_AGE_MILLIS_KEY, Long.MAX_VALUE);
    baseWindowMillis = conf.getLong(BASE_WINDOW_MILLIS_KEY, 3600000 * 6);
    windowsPerTier = conf.getInt(WINDOWS_PER_TIER_KEY, 4);
    incomingWindowMin = conf.getInt(INCOMING_WINDOW_MIN_KEY, 6);
    compactionPolicyForTieredWindow = conf.get(COMPACTION_POLICY_CLASS_FOR_TIERED_WINDOWS_KEY,
        DEFAULT_TIER_COMPACTION_POLICY_CLASS.getName());
    singleOutputForMinorCompaction = conf.getBoolean(SINGLE_OUTPUT_FOR_MINOR_COMPACTION_KEY,
      true);
    LOG.info(this);
  }

  @Override
  public String toString() {
    return String.format(
      "size [%d, %d); files [%d, %d); ratio %f; off-peak ratio %f; throttle point %d;"
      + " major period %d, major jitter %f, min locality to compact %f;"
      + " tiered compaction: max_age %d, base window in milliseconds %d, windows per tier %d, "
      + "incoming window threshold %d",
      minCompactSize,
      maxCompactSize,
      minFilesToCompact,
      maxFilesToCompact,
      compactionRatio,
      offPeekCompactionRatio,
      throttlePoint,
      majorCompactionPeriod,
      majorCompactionJitter,
      minLocalityToForceCompact,
      maxStoreFileAgeMillis,
      baseWindowMillis,
      windowsPerTier,
      incomingWindowMin);
  }

  /**
   * @return lower bound below which compaction is selected without ratio test
   */
  long getMinCompactSize() {
    return minCompactSize;
  }

  /**
   * @return upper bound on file size to be included in minor compactions
   */
  long getMaxCompactSize() {
    return maxCompactSize;
  }

  /**
   * @return upper bound on number of files to be included in minor compactions
   */
  public int getMinFilesToCompact() {
    return minFilesToCompact;
  }

  /**
   * Set upper bound on number of files to be included in minor compactions
   * @param threshold
   */
  public void setMinFilesToCompact(int threshold) {
    minFilesToCompact = threshold;
  }
  
  /**
   * @return upper bound on number of files to be included in minor compactions
   */
  int getMaxFilesToCompact() {
    return maxFilesToCompact;
  }

  /**
   * @return Ratio used for compaction
   */
  double getCompactionRatio() {
    return compactionRatio;
  }

  /**
   * @return Off peak Ratio used for compaction
   */
  double getCompactionRatioOffPeak() {
    return offPeekCompactionRatio;
  }

  /**
   * @return ThrottlePoint used for classifying small and large compactions
   */
  long getThrottlePoint() {
    return throttlePoint;
  }

  /**
   * @return Major compaction period from compaction.
   *   Major compactions are selected periodically according to this parameter plus jitter
   */
  long getMajorCompactionPeriod() {
    return majorCompactionPeriod;
  }

  /**
   * @return Major the jitter fraction, the fraction within which the major compaction
   *    period is randomly chosen from the majorCompactionPeriod in each store.
   */
  float getMajorCompactionJitter() {
    return majorCompactionJitter;
  }

  /**
   * @return Block locality ratio, the ratio at which we will include old regions with a single
   *   store file for major compaction.  Used to improve block locality for regions that
   *   haven't had writes in a while but are still being read.
   */
  float getMinLocalityToForceCompact() {
    return minLocalityToForceCompact;
  }

  public long getMaxStoreFileAgeMillis() {
    return maxStoreFileAgeMillis;
  }

  public long getBaseWindowMillis() {
    return baseWindowMillis;
  }

  public int getWindowsPerTier() {
    return windowsPerTier;
  }

  public int getIncomingWindowMin() {
    return incomingWindowMin;
  }

  public String getCompactionPolicyForTieredWindow() {
    return compactionPolicyForTieredWindow;
  }
  
  public boolean useSingleOutputForMinorCompaction() {
    return singleOutputForMinorCompaction;
  }
}
