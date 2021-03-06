/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gemstone.gemfire.internal.cache.persistence;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.HighPriorityDistributionMessage;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.logging.LogService;

/**
 *
 */
public class PrepareNewPersistentMemberMessage extends
    HighPriorityDistributionMessage implements MessageWithReply {

  private static final Logger logger = LogService.getLogger();
  
  private String regionPath;
  private PersistentMemberID oldId;
  private PersistentMemberID newId;
  private int processorId;

  public PrepareNewPersistentMemberMessage() {
    
  }
  
  public PrepareNewPersistentMemberMessage(String regionPath, PersistentMemberID oldId, PersistentMemberID newId, int processorId) {
    this.regionPath = regionPath;
    this.newId = newId;
    this.oldId = oldId;
    this.processorId = processorId;
  }

  public static void send(
      Set<InternalDistributedMember> members, DM dm, String regionPath,
      PersistentMemberID oldId, PersistentMemberID newId) throws ReplyException {
    ReplyProcessor21 processor = new ReplyProcessor21(dm, members);
    PrepareNewPersistentMemberMessage msg = new PrepareNewPersistentMemberMessage(regionPath, oldId, newId, processor.getProcessorId());
    msg.setRecipients(members);
    dm.putOutgoing(msg);
    processor.waitForRepliesUninterruptibly();
  }

  @Override
  protected void process(DistributionManager dm) {
    int oldLevel =         // Set thread local flag to allow entrance through initialization Latch
      LocalRegion.setThreadInitLevelRequirement(LocalRegion.ANY_INIT);

    PersistentMemberState state = null;
    PersistentMemberID myId = null;
    ReplyException exception = null;
    try {
      // get the region from the path, but do NOT wait on initialization,
      // otherwise we could have a distributed deadlock

      Cache cache = CacheFactory.getInstance(dm.getSystem());
      Region region = cache.getRegion(this.regionPath);
      PersistenceAdvisor persistenceAdvisor = null;
      if(region instanceof DistributedRegion) {
        persistenceAdvisor = ((DistributedRegion) region).getPersistenceAdvisor();
      } else if ( region == null) {
        Bucket proxy = PartitionedRegionHelper.getProxyBucketRegion(GemFireCacheImpl.getInstance(), this.regionPath, false);
        if(proxy != null) {
          persistenceAdvisor = proxy.getPersistenceAdvisor();
        }
      }
      
      if(persistenceAdvisor != null) {
        persistenceAdvisor.prepareNewMember(getSender(), oldId, newId);
      }
      
    } catch (RegionDestroyedException e) {
      logger.debug("<RegionDestroyed> {}", this);
    }
    catch (CancelException e) {
      logger.debug("<CancelException> {}", this);
    }
    catch(VirtualMachineError e) {
      SystemFailure.initiateFailure(e);
      throw e;
    }
    catch(Throwable t) {
      SystemFailure.checkFailure();
      exception = new ReplyException(t);
    }
    finally {
      LocalRegion.setThreadInitLevelRequirement(oldLevel);
      ReplyMessage replyMsg = new ReplyMessage();
      replyMsg.setRecipient(getSender());
      replyMsg.setProcessorId(processorId);
      if(exception != null) {
        replyMsg.setException(exception);
      }
      dm.putOutgoing(replyMsg);
    }
  }

  public int getDSFID() {
    return PREPARE_NEW_PERSISTENT_MEMBER_REQUEST;
  }
  
  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
    regionPath = DataSerializer.readString(in);
    processorId = in.readInt();
    boolean hasOldId = in.readBoolean();
    if(hasOldId) {
      oldId = new PersistentMemberID();
      InternalDataSerializer.invokeFromData(oldId, in);
    }
    newId = new PersistentMemberID();
    InternalDataSerializer.invokeFromData(newId, in);
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(regionPath, out);
    out.writeInt(processorId);
    out.writeBoolean(oldId != null);
    if(oldId != null) {
      InternalDataSerializer.invokeToData(oldId, out);
    }
    InternalDataSerializer.invokeToData(newId, out);
  }
}
