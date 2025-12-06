package com.oltpbenchmark.api.explain;

import com.oltpbenchmark.api.SQLStmt;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExplainAnalyzeProxy implements InvocationHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ExplainAnalyzeProxy.class);

  private final PreparedStatement delegate;
  private final Connection connection;
  private final SQLStmt sqlStmt;
  private final ExplainAnalyzeState state;
  private final Map<Integer, ParameterValue> parameters = new HashMap<>();
  private final List<Map<Integer, ParameterValue>> batchParameters = new ArrayList<>();

  private ExplainAnalyzeProxy(
      Connection connection,
      SQLStmt sqlStmt,
      PreparedStatement delegate,
      ExplainAnalyzeState state) {
    this.connection = connection;
    this.sqlStmt = sqlStmt;
    this.delegate = delegate;
    this.state = state;
  }

  public static PreparedStatement wrap(
      Connection connection, SQLStmt sqlStmt, PreparedStatement delegate) {
    ExplainAnalyzeState state = ExplainAnalyzeHelper.getContext();
    if (state == null) {
      return delegate;
    }
    return (PreparedStatement)
        Proxy.newProxyInstance(
            delegate.getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            new ExplainAnalyzeProxy(connection, sqlStmt, delegate, state));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    String methodName = method.getName();

    if ("equals".equals(methodName)) {
      return proxy == args[0];
    } else if ("hashCode".equals(methodName)) {
      return System.identityHashCode(proxy);
    } else if ("toString".equals(methodName)) {
      return "ExplainAnalyzeProxy{" + delegate + "}";
    }

    if (methodName.startsWith("set")
        && args != null
        && args.length >= 2
        && args[0] instanceof Integer) {
      trackParameter(methodName, args);
    } else if ("clearParameters".equals(methodName)) {
      parameters.clear();
      batchParameters.clear();
    }

    if ("addBatch".equals(methodName)) {
      if (!parameters.isEmpty()) {
        batchParameters.add(copyParams(parameters));
      }
      return method.invoke(delegate, args);
    }

    switch (methodName) {
      case "executeQuery", "executeUpdate", "execute", "executeLargeUpdate" -> {
        runExplainForParameters(copyParams(parameters));
        return method.invoke(delegate, args);
      }
      case "executeBatch", "executeLargeBatch" -> {
        runExplainForBatch();
        Object result = method.invoke(delegate, args);
        batchParameters.clear();
        return result;
      }
      default -> {
        return method.invoke(delegate, args);
      }
    }
  }

  private void runExplainForBatch() {
    if (batchParameters.isEmpty()) {
      runExplainForParameters(copyParams(parameters));
      return;
    }
    for (Map<Integer, ParameterValue> paramSet : batchParameters) {
      runExplainForParameters(paramSet);
    }
  }

  private void runExplainForParameters(Map<Integer, ParameterValue> paramSet) {
    String explainSql = "EXPLAIN ANALYZE " + sqlStmt.getSQL();
    Savepoint sp = null;
    try {
      if (connection.getAutoCommit()) {
        LOG.warn("Auto-commit is enabled; skipping EXPLAIN ANALYZE to avoid side effects");
        return;
      }

      sp = connection.setSavepoint("bb_explain");
      try (PreparedStatement explainStmt = connection.prepareStatement(explainSql)) {
        applyParameters(explainStmt, paramSet);
        List<String> planLines = new ArrayList<>();
        try (ResultSet rs = explainStmt.executeQuery()) {
          while (rs.next()) {
            planLines.add(rs.getString(1));
          }
        }
        state
            .getRecorder()
            .record(
                state.getTransactionName(),
                state.getWorkerId(),
                sqlStmt.getSQL(),
                toSimpleMap(paramSet),
                planLines);
      }
    } catch (SQLException e) {
      LOG.warn("Failed to run EXPLAIN ANALYZE for sql [{}]", sqlStmt.getSQL(), e);
    } finally {
      if (sp != null) {
        try {
          connection.rollback(sp);
        } catch (SQLException rollbackEx) {
          LOG.warn("Failed to rollback explain analyze savepoint", rollbackEx);
        }
      }
    }
  }

  private void applyParameters(PreparedStatement statement, Map<Integer, ParameterValue> paramSet)
      throws SQLException {
    Map<Integer, ParameterValue> sorted = new TreeMap<>(paramSet);
    for (Map.Entry<Integer, ParameterValue> entry : sorted.entrySet()) {
      entry.getValue().apply(statement, entry.getKey());
    }
  }

  private void trackParameter(String methodName, Object[] args) {
    int index = (Integer) args[0];
    Integer sqlType = null;
    Object value = null;
    if (args.length > 1) {
      value = args[1];
    }
    if ("setNull".equals(methodName)) {
      if (args.length > 1 && args[1] instanceof Integer) {
        sqlType = (Integer) args[1];
      }
    } else if ("setObject".equals(methodName) && args.length > 2 && args[2] instanceof Integer) {
      sqlType = (Integer) args[2];
    }
    parameters.put(index, new ParameterValue(value, sqlType));
  }

  private Map<Integer, ParameterValue> copyParams(Map<Integer, ParameterValue> original) {
    return new HashMap<>(original);
  }

  private Map<Integer, Object> toSimpleMap(Map<Integer, ParameterValue> paramSet) {
    Map<Integer, Object> simple = new LinkedHashMap<>();
    Map<Integer, ParameterValue> sorted = new TreeMap<>(paramSet);
    for (Map.Entry<Integer, ParameterValue> entry : sorted.entrySet()) {
      simple.put(entry.getKey(), entry.getValue().value());
    }
    return simple;
  }

  private record ParameterValue(Object value, Integer sqlType) {
    private void apply(PreparedStatement statement, int index) throws SQLException {
      if (value == null) {
        statement.setNull(index, sqlType != null ? sqlType : Types.NULL);
      } else {
        statement.setObject(index, value);
      }
    }
  }
}
