package com.oltpbenchmark.api.explain;

import com.oltpbenchmark.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONStringer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExplainAnalyzeRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(ExplainAnalyzeRecorder.class);

  private final List<Record> records = Collections.synchronizedList(new ArrayList<>());

  public void record(
      String transactionName,
      int workerId,
      String sql,
      Map<Integer, Object> parameters,
      List<String> planLines) {
    Map<Integer, Object> parameterCopy = new LinkedHashMap<>(parameters);
    List<String> planCopy = new ArrayList<>(planLines);
    records.add(new Record(transactionName, workerId, sql, parameterCopy, planCopy));
  }

  public void writeTo(String outputPath) throws IOException {
    JSONStringer stringer = new JSONStringer();
    stringer.array();
    synchronized (records) {
      for (Record record : records) {
        record.toJson(stringer);
      }
    }
    stringer.endArray();

    File outputFile = new File(outputPath);
    FileUtil.makeDirIfNotExists(outputFile.getParent());
    String prettyJson = new JSONArray(stringer.toString()).toString(1);
    FileUtil.writeStringToFile(outputFile, prettyJson);
    LOG.info("Explain analyze output written to {}", outputFile.getAbsolutePath());
  }

  private record Record(
      String transactionName,
      int workerId,
      String sql,
      Map<Integer, Object> parameters,
      List<String> planLines) {
    private void toJson(JSONStringer stringer) {
      stringer
          .object()
          .key("transaction")
          .value(transactionName)
          .key("worker")
          .value(workerId)
          .key("sql")
          .value(sql)
          .key("parameters")
          .object();

      for (Map.Entry<Integer, Object> entry : parameters.entrySet()) {
        String key = String.valueOf(entry.getKey());
        stringer.key(key).value(entry.getValue());
      }

      stringer.endObject().key("plan").array();
      for (String line : planLines) {
        stringer.value(line);
      }
      stringer.endArray().endObject();
    }
  }
}
