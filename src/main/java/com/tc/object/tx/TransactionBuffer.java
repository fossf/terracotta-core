/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.object.tx;

import com.tc.io.TCByteBufferOutputStream;

import java.util.List;

public interface TransactionBuffer {

  public void writeTo(TCByteBufferOutputStream dest);

  public int write(ClientTransaction txn);

  public int getTxnCount();

  public TransactionID getTransactionID();

  public void addTransactionCompleteListeners(List<TransactionCompleteListener> transactionCompleteListeners);

  public List<TransactionCompleteListener> getTransactionCompleteListeners();

}
