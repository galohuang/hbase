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

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.regionserver.StoreConfigInformation;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreUtils;

/**
 * An abstract compaction policy that select files on seq id order.
 */
@InterfaceAudience.Private
public abstract class SortedCompactionPolicy extends CompactionPolicy {

  private static final Log LOG = LogFactory.getLog(SortedCompactionPolicy.class);

  public SortedCompactionPolicy(Configuration conf, StoreConfigInformation storeConfigInfo) {
    super(conf, storeConfigInfo);
  }

  public List<StoreFile> preSelectCompactionForCoprocessor(final Collection<StoreFile> candidates,
      final List<StoreFile> filesCompacting) {
    return getCurrentEligibleFiles(new ArrayList<StoreFile>(candidates), filesCompacting);
  }

  /**
   * @param candidateFiles candidate files, ordered from oldest to newest by seqId. We rely on
   *   DefaultStoreFileManager to sort the files by seqId to guarantee contiguous compaction based 
   *   on seqId for data consistency.
   * @return subset copy of candidate list that meets compaction criteria
   * @throws java.io.IOException
   */
  public CompactionRequest selectCompaction(Collection<StoreFile> candidateFiles,
      final List<StoreFile> filesCompacting, final boolean isUserCompaction,
      final boolean mayUseOffPeak, final boolean forceMajor) throws IOException {
    // Preliminary compaction subject to filters
    ArrayList<StoreFile> candidateSelection = new ArrayList<StoreFile>(candidateFiles);
    // Stuck and not compacting enough (estimate). It is not guaranteed that we will be
    // able to compact more if stuck and compacting, because ratio policy excludes some
    // non-compacting files from consideration during compaction (see getCurrentEligibleFiles).
    int futureFiles = filesCompacting.isEmpty() ? 0 : 1;
    boolean mayBeStuck = (candidateFiles.size() - filesCompacting.size() + futureFiles)
        >= storeConfigInfo.getBlockingFileCount();

    candidateSelection = getCurrentEligibleFiles(candidateSelection, filesCompacting);
    LOG.debug("Selecting compaction from " + candidateFiles.size() + " store files, " +
        filesCompacting.size() + " compacting, " + candidateSelection.size() +
        " eligible, " + storeConfigInfo.getBlockingFileCount() + " blocking");

    if (!forceMajor) {
      candidateSelection = skipLargeFiles(candidateSelection);
    }

    // Force a major compaction if this is a user-requested major compaction,
    // or if we do not have too many files to compact and this was requested
    // as a major compaction.
    // Or, if there are any references among the candidates.
    boolean tryingMajor = (forceMajor && isUserCompaction)
      || ((forceMajor || shouldPerformMajorCompaction(candidateSelection))
          && (candidateSelection.size() < comConf.getMaxFilesToCompact()))
      || StoreUtils.hasReferences(candidateSelection);

    if (tryingMajor) {
      LOG.debug("Trying to select files for major compaction with forceMajor:"
        + forceMajor + ", userCompaction:" + isUserCompaction);
    }

    return getCompactionRequest(candidateSelection, tryingMajor, isUserCompaction,
      mayUseOffPeak, mayBeStuck);
  }
  
  protected abstract CompactionRequest getCompactionRequest(ArrayList<StoreFile> candidateSelection,
    boolean tryingMajor, boolean isUserCompaction, boolean mayUseOffPeak, boolean mayBeStuck) throws IOException;
  
  /*
   * @param filesToCompact Files to compact. Can be null.
   * @return True if we should run a major compaction.
   */
  public abstract boolean shouldPerformMajorCompaction(final Collection<StoreFile> filesToCompact) throws IOException;

  public long getNextMajorCompactTime(final Collection<StoreFile> filesToCompact) {
    // default = 24hrs
    long ret = comConf.getMajorCompactionPeriod();
    if (ret > 0) {
      // default jitter = 20% = +/- 4.8 hrs
      double jitterPct = comConf.getMajorCompactionJitter();
      if (jitterPct > 0) {
        long jitter = Math.round(ret * jitterPct);
        // deterministic jitter avoids a major compaction storm on restart
        Integer seed = StoreUtils.getDeterministicRandomSeed(filesToCompact);
        if (seed != null) {
          double rnd = (new Random(seed)).nextDouble();
          ret += jitter - Math.round(2L * jitter * rnd);
        } else {
          ret = 0; // no storefiles == no major compaction
        }
      }
    }
    return ret;
  }

  /**
   * @param compactionSize Total size of some compaction
   * @return whether this should be a large or small compaction
   */
  public boolean throttleCompaction(long compactionSize) {
    return compactionSize > comConf.getThrottlePoint();
  }

  /**
   * A heuristic method to decide whether to schedule a compaction request
   * @param storeFiles files in the store.
   * @param filesCompacting files being scheduled to compact.
   * @return true to schedule a request.
   */
  public boolean needsCompaction(final Collection<StoreFile> storeFiles,
      final List<StoreFile> filesCompacting) {
    int numCandidates = storeFiles.size() - filesCompacting.size();
    return numCandidates >= comConf.getMinFilesToCompact();
  }
  

  protected ArrayList<StoreFile> getCurrentEligibleFiles(ArrayList<StoreFile> candidateFiles,
      final List<StoreFile> filesCompacting) {
    // candidates = all storefiles not already in compaction queue
    if (!filesCompacting.isEmpty()) {
      // exclude all files older than the newest file we're currently
      // compacting. this allows us to preserve contiguity (HBASE-2856)
      StoreFile last = filesCompacting.get(filesCompacting.size() - 1);
      int idx = candidateFiles.indexOf(last);
      Preconditions.checkArgument(idx != -1);
      candidateFiles.subList(0, idx + 1).clear();
    }
    return candidateFiles;
  }

  /**
   * @param candidates pre-filtrate
   * @return filtered subset exclude all files above maxCompactSize Also save all references. We
   *         MUST compact them
   */
  protected ArrayList<StoreFile> skipLargeFiles(ArrayList<StoreFile> candidates) {
    int pos = 0;
    while (pos < candidates.size() && !candidates.get(pos).isReference()
        && (candidates.get(pos).getReader().length() > comConf.getMaxCompactSize())) {
      ++pos;
    }
    if (pos > 0) {
      LOG.debug("Some files are too large. Excluding " + pos + " files from compaction candidates");
      candidates.subList(0, pos).clear();
    }
    return candidates;
  }

  /**
   * @param candidates pre-filtrate
   * @return filtered subset exclude all bulk load files if configured
   */
  protected ArrayList<StoreFile> filterBulk(ArrayList<StoreFile> candidates) {
    candidates.removeAll(Collections2.filter(candidates, new Predicate<StoreFile>() {
      @Override
      public boolean apply(StoreFile input) {
        return input.excludeFromMinorCompaction();
      }
    }));
    return candidates;
  }

  /**
   * @param candidates pre-filtrate
   * @return filtered subset take up to maxFilesToCompact from the start
   */
  protected void removeExcessFiles(ArrayList<StoreFile> candidates,
      boolean isUserCompaction, boolean isMajorCompaction) {
    int excess = candidates.size() - comConf.getMaxFilesToCompact();
    if (excess > 0) {
      if (isMajorCompaction && isUserCompaction) {
        LOG.debug("Warning, compacting more than " + comConf.getMaxFilesToCompact()
            + " files because of a user-requested major compaction");
      } else {
        LOG.debug("Too many admissible files. Excluding " + excess
            + " files from compaction candidates");
        candidates.subList(comConf.getMaxFilesToCompact(), candidates.size()).clear();
      }
    }
  }

  /**
   * @param candidates pre-filtrate
   * @return filtered subset forget the compactionSelection if we don't have enough files
   */
  protected ArrayList<StoreFile> checkMinFilesCriteria(ArrayList<StoreFile> candidates,
    int minFiles) {
    if (candidates.size() < minFiles) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Not compacting files because we only have " + candidates.size()
            + " files ready for compaction. Need " + minFiles + " to initiate.");
      }
      candidates.clear();
    }
    return candidates;
  }
}
