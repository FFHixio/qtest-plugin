package com.qasymphony.ci.plugin.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.qasymphony.ci.plugin.model.SubmittedResult;
import com.qasymphony.ci.plugin.store.file.FileReader;
import com.qasymphony.ci.plugin.utils.JsonUtils;
import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import org.jenkinsci.remoting.RoleChecker;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;

/**
 * @author trongle
 * @version 10/22/2015 9:20 PM trongle $
 * @since 1.0
 */
public class StoreResultServiceImpl implements StoreResultService {
  /**
   * Folder store data for plugin
   */
  private static final String RESULT_FOLDER = "jqtest_result";

  /**
   * File name contain data submitted to qTest
   */
  private static final String RESULT_FILE = "result";

  @Override public Boolean store(FilePath workspace, final Object result) throws IOException, InterruptedException {
    FilePath projectPath = workspace.getParent();
    FilePath resultFolder = new FilePath(projectPath, RESULT_FOLDER);
    resultFolder.mkdirs();
    FilePath resultFile = new FilePath(resultFolder, RESULT_FILE);

    resultFile.act(new FilePath.FileCallable<String>() {
      @Override public String invoke(File file, VirtualChannel virtualChannel)
        throws IOException, InterruptedException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(file.getPath(), true));
        writer.newLine();
        writer.write(JsonUtils.toJson(result));
        writer.close();
        return null;
      }

      @Override public void checkRoles(RoleChecker roleChecker) throws SecurityException {
      }
    });
    return true;
  }

  @Override public String load(FilePath workspace) throws IOException, InterruptedException {
    FilePath projectPath = workspace.getParent();
    FilePath resultFolder = new FilePath(projectPath, RESULT_FOLDER);
    FilePath resultFile = new FilePath(resultFolder, RESULT_FILE);
    JsonNode node = JsonUtils.read(resultFile.read());
    return node == null ? "" : node.toString();
  }

  @Override public Map<Integer, SubmittedResult> fetchAll(FilePath filePath) throws IOException, InterruptedException {
    Map<Integer, SubmittedResult> buildResults = new HashMap<>();
    FilePath resultPath = new FilePath(filePath.getParent(), RESULT_FOLDER);
    FilePath resultFile = new FilePath(resultPath, RESULT_FILE);
    SortedMap<Integer, String> lines = resultFile.act(new FilePath.FileCallable<SortedMap<Integer, String>>() {
      @Override public SortedMap<Integer, String> invoke(File file, VirtualChannel virtualChannel)
        throws IOException, InterruptedException {
        return new FileReader(file).readAll();
      }

      @Override public void checkRoles(RoleChecker roleChecker) throws SecurityException {

      }
    });
    for (Map.Entry<Integer, String> entry : lines.entrySet()) {
      SubmittedResult submitResult = JsonUtils.fromJson(entry.getValue(), SubmittedResult.class);
      if (null != submitResult)
        buildResults.put(submitResult.getBuildNumber(), submitResult);
    }
    return buildResults;
  }
}