package com.oltpbenchmark.api.explain;

public final class ExplainAnalyzeState {

  private final ExplainAnalyzeRecorder recorder;
  private final int workerId;
  private final String transactionName;

  public ExplainAnalyzeState(
      ExplainAnalyzeRecorder recorder, int workerId, String transactionName) {
    this.recorder = recorder;
    this.workerId = workerId;
    this.transactionName = transactionName;
  }

  public ExplainAnalyzeRecorder getRecorder() {
    return recorder;
  }

  public int getWorkerId() {
    return workerId;
  }

  public String getTransactionName() {
    return transactionName;
  }
}
