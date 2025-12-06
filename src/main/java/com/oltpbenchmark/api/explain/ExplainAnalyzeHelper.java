package com.oltpbenchmark.api.explain;

public final class ExplainAnalyzeHelper {

  private static final ThreadLocal<ExplainAnalyzeState> CURRENT = new ThreadLocal<>();

  private ExplainAnalyzeHelper() {}

  public static void setContext(ExplainAnalyzeRecorder recorder, int workerId, String txnName) {
    if (recorder == null) {
      clear();
      return;
    }
    CURRENT.set(new ExplainAnalyzeState(recorder, workerId, txnName));
  }

  public static ExplainAnalyzeState getContext() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
