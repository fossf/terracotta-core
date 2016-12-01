/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.objectserver.entity;

import com.tc.exception.TCServerRestartException;
import com.tc.exception.ZapServerNodeException;
import com.tc.l2.msg.ReplicationResultCode;
import com.tc.net.NodeID;
import com.tc.util.Assert;
import java.util.HashMap;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * This type is used by ActiveToPassiveReplication in order to wait on all the passives either sending a RECEIVED or
 * COMPLETED acknowledgement for a specific message.
 */
public class ActivePassiveAckWaiter {
  private final Set<NodeID> receivedPending;
  private final Set<NodeID> completedPending;
  private final Map<NodeID, ReplicationResultCode> results;
  private final PassiveReplicationBroker parent;

  public ActivePassiveAckWaiter(Set<NodeID> allPassiveNodes, PassiveReplicationBroker parent) {
    this.receivedPending =  new HashSet<NodeID>(allPassiveNodes);
    this.completedPending =  new HashSet<NodeID>(allPassiveNodes);
    this.results = new HashMap<>();
    this.parent = parent;
  }

  public synchronized void waitForReceived() throws InterruptedException {
    while (!this.receivedPending.isEmpty()) {
      wait();
    }
  }

  public synchronized void waitForCompleted() throws InterruptedException {
    while (!this.completedPending.isEmpty()) {
      wait();
    }
  }
  
  public void verifyLifecycleResult(boolean success) {
    if(success || results.entrySet().stream().anyMatch(e->e.getValue() == ReplicationResultCode.SUCCESS)) {
      boolean zapped = false;
      for (Map.Entry<NodeID, ReplicationResultCode> r : results.entrySet()) {
        if (r.getValue() == ReplicationResultCode.FAIL) {
          parent.zapAndWait(r.getKey());
          zapped = true;
        }
      }
      if (!success) {
        throw new TCServerRestartException("inconsistent lifecycle");
      }
    }
  }

  public synchronized boolean isCompleted() {
    return this.completedPending.isEmpty();
  }

  public synchronized void didReceiveOnPassive(NodeID onePassive) {
    boolean didContain = this.receivedPending.remove(onePassive);
    // We must have contained this passive in order to receive.
    Assert.assertTrue(didContain);
    // Wake everyone up if this changed something.
    if (this.receivedPending.isEmpty()) {
      notifyAll();
    }
  }

  /**
   * Notifies the waiter that it is complete for the given node.
   * 
   * @param onePassive The passive which has completed the replicated message
   * @param isNormalComplete True if this was a normal complete ack, false if we are completing because the node disappeared
   * @param payload
   * @return True if this was the last outstanding completion required and the waiter is now done.
   */
  public synchronized boolean didCompleteOnPassive(NodeID onePassive, boolean isNormalComplete, ReplicationResultCode payload) {
    // Note that we will try to remove from the received set, but usually it will already have been removed.
    boolean didContainInReceived = this.receivedPending.remove(onePassive);
    // We know that it must still be in the completed set, though.
    boolean didContainInCompleted = this.completedPending.remove(onePassive);
    // We must have contained this passive in order to complete.
    if (isNormalComplete) {
      // In the unexpected case, we are just making sure this node is removed from all waiters, even though it might have
      // already completed on some of them.
      Assert.assertTrue(didContainInCompleted);
      this.results.put(onePassive, payload);
    }
    boolean isDoneWaiting = this.completedPending.isEmpty();
    // Wake everyone up if this changed something.
    if ((didContainInReceived && this.receivedPending.isEmpty()) || isDoneWaiting) {
      notifyAll();
    }
    return isDoneWaiting;
  }
}
